package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Test

class ManagerOperationsTest {
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
}
