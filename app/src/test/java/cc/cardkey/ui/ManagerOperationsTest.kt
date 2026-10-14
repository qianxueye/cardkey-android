package cc.cardkey.ui

import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Test

class ManagerOperationsTest {
    @Test fun pivSlotReportsActualPoliciesForEmptyAndPopulatedMetadata() {
        val pins = listOf("无需 PIN", "会话内验证一次", "每次操作验证")
        val touches = listOf("无需触摸", "每次操作触摸", "允许缓存触摸确认")
        for (empty in listOf(true, false)) for (pin in 1..3) for (touch in 1..3) {
            val card = EnrollmentCard().apply {
                val policy = PivTlv.encode(2, byteArrayOf(pin.toByte(), touch.toByte()))
                slotMetadata = if (empty) policy + PivTlv.encode(3, byteArrayOf(0))
                    else PivTlv.parse(metadata()).entries.fold(byteArrayOf()) { bytes, (tag, value) ->
                        bytes + if (tag == 2) policy else PivTlv.encode(tag, value)
                    }
            }
            val result = CardSession(card).run {
                ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_SLOT, slot = 0x9c))
            } as OperationResult.Completed
            val rows = result.value.rows.associate { it.label to it.value }
            assertEquals(pins[pin - 1], rows["PIN 验证策略"])
            assertEquals(touches[touch - 1], rows["触摸确认策略"])
            assertEquals(if (empty) null else "P256", rows["算法"])
            assertEquals(!empty, rows.containsKey("公钥"))
            assertEquals(Effect.NONE, result.effect)
            assertTrue(card.commands.all { it.ins in listOf(0xa4, 0xf7, 0xcb) })
        }
    }

    @Test fun pivGenerationReportsReadbackPolicies() {
        val card = EnrollmentCard()
        val result = CardSession(card).run {
            ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_GENERATE, slot = 0x9c,
                algorithm = KeyAlgorithm.P256, pinPolicy = PinPolicy.ONCE,
                managementAlgorithm = ManagementAlgorithm.TDES, adminKey = card.managementKey.copyOf()))
        } as OperationResult.Completed
        val rows = result.value.rows.associate { it.label to it.value }
        assertEquals("P256", rows["算法"])
        assertEquals("会话内验证一次", rows["PIN 验证策略"])
        assertEquals("无需触摸", rows["触摸确认策略"])
        assertTrue(rows.containsKey("公钥"))
        assertEquals(Effect.APPLIED, result.effect)
        assertEquals(1, card.commands.count { it.ins == 0x47 })
    }

    @Test fun pivAbsentMetadataDoesNotInventPolicies() {
        val commands = mutableListOf<Int>()
        val card = object : CardChannel {
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 261
            override fun close() = Unit
            override fun transmit(command: ByteArray): ByteArray {
                val ins = command[1].toInt() and 255
                commands += ins
                return when (ins) {
                    0xa4 -> "9000".hexBytes()
                    0xf7 -> "6a88".hexBytes()
                    0xcb -> "6a82".hexBytes()
                    else -> error("Unexpected APDU")
                }
            }
        }
        val result = CardSession(card).run {
            ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_SLOT))
        } as OperationResult.Completed
        assertTrue(result.value.rows.isEmpty())
        assertEquals(listOf(0xa4, 0xf7, 0xcb), commands)
    }

    @Test fun fidoInformationWorkflowSelectsBeforeReading() {
        val requests = mutableListOf<ByteArray>()
        val channel = object : CardChannel {
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 261
            override fun transmit(command: ByteArray): ByteArray {
                requests += command.copyOf()
                return if (requests.size == 1) "FIDO_2_0".toByteArray() + "9000".hexBytes()
                else "00a30181684649444f5f325f3003500000000000000000000000000000000004a162726bf59000".hexBytes()
            }
            override fun close() = Unit
        }
        val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
        assertTrue(result is OperationResult.Completed)
        val report = (result as OperationResult.Completed).value
        assertEquals("FIDO_2_0", report.rows.first { it.label == "支持的协议" }.value)
        assertEquals(0xa4, requests.first()[1].toInt() and 255)
        assertArrayEquals("80100000010400".hexBytes(), requests.last())
    }

    @Test fun fidoStatusUsesApprovedMeaningsAndUnits() {
        val channel = FidoChannel { code, _ ->
            assertEquals(4, code)
            success(info(mapOf("clientPin" to true, "alwaysUv" to false, "ep" to true, "rk" to true),
                extensions = listOf("hmac-secret", "largeBlobKey"), extra = cborMap(
                    13 to 6.cbor())))
        }
        val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
        val rows = (result as OperationResult.Completed).value.rows.associate { it.label to it.value }
        assertEquals("FIDO_2_1", rows["支持的协议"])
        assertEquals("hmac-secret, largeBlobKey", rows["支持的扩展"])
        assertEquals("已设置", rows["PIN"])
        assertEquals("关闭", rows["始终验证"])
        assertEquals("开启", rows["企业证明"])
        assertEquals("6 个字符", rows["最短 PIN 长度"])
        assertFalse(rows.containsKey("rk"))
    }

    @Test fun missingOptionalStatusIsOmittedInsteadOfDefaulting() {
        val channel = FidoChannel { _, _ -> success(cborMap(
            1 to Cbor.Array(listOf("FIDO_2_0".cbor())), 3 to ByteArray(16).cbor())) }
        val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
        val rows = (result as OperationResult.Completed).value.rows
        assertEquals(listOf("支持的协议", "支持的扩展", "PIN · 协议"), rows.map { it.label })
        assertEquals("无", rows.first { it.label == "支持的扩展" }.value)
    }

    @Test fun falseStatesUseTheirOwnMeaning() {
        val channel = FidoChannel { _, _ -> success(info(mapOf("clientPin" to false, "alwaysUv" to true, "ep" to false))) }
        val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
        val rows = (result as OperationResult.Completed).value.rows.associate { it.label to it.value }
        assertEquals("未设置", rows["PIN"])
        assertEquals("开启", rows["始终验证"])
        assertEquals("关闭", rows["企业证明"])
    }

    @Test fun advertisedAlgorithmsAndPinChangeUseActualOptionalStatus() {
        for (algorithms in listOf(null, listOf(-7), listOf(-257, -7, 12345))) {
            for (forced in listOf(null, false, true)) {
                val channel = FidoChannel { code, _ ->
                    assertEquals(4, code)
                    success(info(extra = cborMap(
                        10 to algorithms?.let { Cbor.Array(it.map { id -> textMap("type" to "public-key".cbor(), "alg" to id.cbor()) }) },
                        12 to forced?.cbor())))
                }
                val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
                val rows = (result as OperationResult.Completed).value.rows.associate { it.label to it.value }
                assertEquals(algorithms?.joinToString(), rows["算法"])
                assertEquals(forced?.let { if (it) "是" else "否" }, rows["需要修改 PIN"])
                assertEquals(listOf(4), channel.commands.map { it.first })
            }
        }
    }

    @Test fun minimumPinConfigurationRendersReturnedAlgorithmsAndPinChange() {
        for (forced in listOf(null, false, true)) {
            var configured = false
            val channel = FidoChannel { code, parameters -> when (code) {
                4 -> success(info(mapOf("authnrCfg" to true, "setMinPINLength" to true), extensions = listOf("minPinLength"), extra = cborMap(
                    13 to (if (configured) 6 else 4).cbor(),
                    10 to Cbor.Array(listOf(textMap("type" to "public-key".cbor(), "alg" to 12345.cbor()))),
                    12 to (if (configured) forced?.cbor() else null))))
                13 -> {
                    assertEquals(3L, parameters!![1].number())
                    assertEquals(6L, parameters[2].map()[1].number())
                    configured = true
                    success()
                }
                else -> error("Unexpected command")
            } }
            val input = OperationInput(ManagerOperation.FIDO_MIN_PIN, text = "6", enabled = forced == true)
            val result = CardSession(channel).run { ManagerOperations().execute(it, input) } as OperationResult.Completed
            val rows = result.value.rows.associate { it.label to it.value }
            assertEquals(Effect.APPLIED, result.effect)
            assertEquals("12345", rows["算法"])
            assertEquals(forced?.let { if (it) "是" else "否" }, rows["需要修改 PIN"])
            assertEquals("6 个字符", rows["最短 PIN 长度"])
            assertEquals(listOf(4, 13, 4), channel.commands.map { it.first })
        }
    }

    @Test fun statusPreservesUnknownCredentialTypeWithItsAlgorithm() {
        val channel = FidoChannel { _, _ -> success(info(extra = cborMap(10 to Cbor.Array(listOf(
            textMap("type" to "future-type".cbor(), "alg" to (-7).cbor()),
            textMap("type" to "public-key".cbor(), "alg" to (-7).cbor())))))) }
        val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
        assertEquals("future-type: -7, -7", (result as OperationResult.Completed).value.rows.first { it.label == "算法" }.value)
    }

    @Test fun malformedAlgorithmStatusFailsWithoutWriting() {
        for (field in listOf(Cbor.Null, Cbor.Array(emptyList()), Cbor.Array(listOf(textMap("type" to "public-key".cbor()))))) {
            val channel = FidoChannel { _, _ -> success(info(extra = cborMap(10 to field))) }
            val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
            assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.NONE), result)
            assertEquals(listOf(4), channel.commands.map { it.first })
        }
    }

    @Test fun malformedConfigurationStatusPreservesAcceptedWriteAndDoesNotRetry() {
        var configured = false
        val channel = FidoChannel { code, _ -> when (code) {
            4 -> success(info(mapOf("authnrCfg" to true, "alwaysUv" to configured), extra =
                if (configured) cborMap(10 to Cbor.Array(emptyList())) else cborMap()))
            13 -> { configured = true; success() }
            else -> error("Unexpected command")
        } }
        val result = CardSession(channel).run {
            ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_ALWAYS_UV, enabled = true))
        }
        assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED, writeAccepted = true), result)
        assertEquals(listOf(4, 13, 4), channel.commands.map { it.first })
    }

    @Test fun pinRetriesOnlyShowPhysicalInstructionWhenRequired() {
        for (required in listOf(null, false, true)) {
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> success(info())
                6 -> success(cborMap(3 to 5.cbor(), 4 to required?.cbor()))
                else -> error("Unexpected command")
            } }
            val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_RETRIES)) }
            val rows = (result as OperationResult.Completed).value.rows
            assertEquals(ResultRow("PIN 剩余重试次数", "5 次"), rows.first())
            assertEquals(if (required == true) 2 else 1, rows.size)
            if (required == true) assertEquals("请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。", rows.last().value)
        }
    }

    @Test fun hidPowerCycleStateDoesNotDisplayApduRemovalInstruction() {
        val channel = ManagerTestChannel(CardWire.CTAP) { code, _ -> when (code) {
            4 -> success(info())
            6 -> success(cborMap(3 to 5.cbor(), 4 to true.cbor()))
            else -> error("Unexpected command")
        } }
        val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_RETRIES)) }
        assertEquals(listOf(ResultRow("PIN 剩余重试次数", "5 次")), (result as OperationResult.Completed).value.rows)
        assertEquals(listOf(cc.cardkey.R.string.error_hidPowerCycleRequired), result.value.messages)
    }

    @Test fun uvRetriesUseApprovedUnitsOnlyForReturnedValues() {
        for (count in listOf(null, 0, 3, 8)) {
            val channel = FidoChannel { code, parameters -> when (code) {
                4 -> success(info(mapOf("uv" to true)))
                6 -> when (parameters!![2].number()) {
                    1L -> success(cborMap(3 to 5.cbor()))
                    7L -> success(cborMap(5 to count?.cbor()))
                    else -> error("Unexpected PIN subcommand")
                }
                else -> error("Unexpected command")
            } }
            val result = CardSession(channel).run {
                ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_RETRIES))
            }
            val rows = (result as OperationResult.Completed).value.rows
            val expected = listOf(ResultRow("PIN 剩余重试次数", "5 次")) +
                listOfNotNull(count?.let { ResultRow("UV 剩余重试次数", "$it 次") })
            assertEquals(expected, rows)
        }
    }

    @Test fun managerDoesNotSelectPivBeforeSensitiveTransportGuard() {
        var count = 0
        val channel = object : CardChannel {
            override val medium = Medium.NFC
            override val maxCommandBytes = 261
            override fun transmit(command: ByteArray): ByteArray { count++; error("No APDU expected") }
            override fun close() = Unit
        }
        val input = OperationInput(ManagerOperation.PIV_CHANGE_PIN, "123456".toCharArray(), "654321".toCharArray())
        val result = input.use { CardSession(channel).run { ManagerOperations().execute(it, input) } }
        assertEquals(FailureCode.CONTACT_REQUIRED, (result as OperationResult.Failed).code)
        assertEquals(0, count)
        assertTrue(input.pin.all { it == '\u0000' })
        assertTrue(input.newPin.all { it == '\u0000' })
    }
    @Test fun everyPivOperationRejectsTypedCtapBeforeAnyCommand() {
        for (operation in ManagerOperation.entries.filter { it.piv }) {
            val channel = ManagerTestChannel(CardWire.CTAP) { _, _ -> error("PIV sent CTAP") }
            val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(operation)) }
            assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE), result)
            assertTrue(channel.commands.isEmpty())
            assertTrue(channel.closed)
        }
    }

    @Test fun typedPinSetAndChangeAuthenticateEncryptedPayloadAndRetireInputs() {
        for (version in 1..2) for (changing in listOf(false, true)) {
            val server = FidoClientTest.PinServer(version)
            var writes = 0
            val channel = ManagerTestChannel(CardWire.CTAP) { code, parameters -> when (code) {
                4 -> success(info(mapOf("clientPin" to changing), protocols = listOf(version)))
                6 -> if (parameters!![2].number() == 2L) success(cborMap(1 to server.cose)) else {
                    writes++
                    assertEquals(if (changing) 4L else 3L, parameters[2].number())
                    val key = server.keyFor(parameters[3].map())
                    try {
                        val encrypted = parameters[5].bytes()
                        val old = parameters[6]?.bytes()
                        assertArrayEquals("5678".toByteArray().copyOf(64), PinProtocol.decrypt(version, key, encrypted))
                        if (changing) assertArrayEquals(sha256("1234".toByteArray()).copyOf(16), PinProtocol.decrypt(version, key, old!!))
                        else assertNull(old)
                        assertArrayEquals(PinProtocol.authenticate(version, key, encrypted + (old ?: byteArrayOf())), parameters[4].bytes())
                    } finally { key.fill(0) }
                    success()
                }
                else -> error("Unexpected command")
            } }
            val input = OperationInput(if (changing) ManagerOperation.FIDO_CHANGE_PIN else ManagerOperation.FIDO_SET_PIN,
                pin = "1234".toCharArray(), newPin = "5678".toCharArray())
            val result = input.use { CardSession(channel).run { ManagerOperations().execute(it, input) } }
            assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect)
            assertEquals(listOf(4, 6, 6), channel.commands.map { it.first })
            assertEquals(1, writes)
            assertArrayEquals(CharArray(4), input.pin)
            assertArrayEquals(CharArray(4), input.newPin)
            assertTrue(channel.closed)
        }
    }

}
