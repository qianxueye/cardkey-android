package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import org.junit.Assert.*
import org.junit.Test

class FidoClientTest {
    @Test fun getInfoPreservesOptionalAlgorithmsAndPinChangeState() {
        val lists = listOf(null, listOf(FidoAlgorithmParameter("public-key", -7)),
            listOf(FidoAlgorithmParameter("public-key", -257), FidoAlgorithmParameter("public-key", -7),
                FidoAlgorithmParameter("future-type", 12345), FidoAlgorithmParameter("public-key", Int.MIN_VALUE),
                FidoAlgorithmParameter("public-key", Int.MAX_VALUE), FidoAlgorithmParameter("public-key", 0)))
        for (algorithms in lists) for (forced in listOf(null, false, true)) {
            val channel = FidoChannel { code, _ ->
                assertEquals(4, code)
                success(info(extra = cborMap(
                    10 to algorithms?.let { Cbor.Array(it.map { parameter ->
                        textMap("type" to parameter.type.cbor(), "alg" to parameter.algorithm.cbor()) }) },
                    12 to forced?.cbor())))
            }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.getInfo() } as OperationResult.Completed
            assertEquals(algorithms, result.value.algorithms)
            assertEquals(forced, result.value.forcePinChange)
            assertEquals(Effect.NONE, result.effect)
            assertEquals(listOf(4), channel.commands.map { it.first })
        }
    }

    @Test fun getInfoRejectsMalformedAdvertisedAlgorithms() {
        val valid = textMap("type" to "public-key".cbor(), "alg" to (-7).cbor())
        val malformedEntries = listOf(Cbor.Null, (-7).cbor(), textMap(),
            textMap("type" to "public-key".cbor()), textMap("alg" to (-7).cbor()),
            textMap("type" to 1.cbor(), "alg" to (-7).cbor()),
            textMap("type" to "public-key".cbor(), "alg" to "-7".cbor()),
            textMap("type" to "public-key".cbor(), "alg" to true.cbor()),
            textMap("type" to "public-key".cbor(), "alg" to Cbor.Integer(Int.MAX_VALUE.toLong() + 1)),
            textMap("type" to "public-key".cbor(), "alg" to Cbor.Integer(Int.MIN_VALUE.toLong() - 1)))
        val malformedFields = listOf(Cbor.Null, valid, Cbor.Array(emptyList()), Cbor.Array(listOf(valid, valid)),
            Cbor.Array(listOf(valid, Cbor.Map(valid.value + ("extra".cbor() to true.cbor()))))) +
            malformedEntries.map { Cbor.Array(listOf(valid, it)) }
        for (field in malformedFields) {
            val channel = FidoChannel { _, _ -> success(info(extra = cborMap(10 to field))) }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.getInfo() }
            assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.NONE), result)
            assertEquals(listOf(4), channel.commands.map { it.first })
        }
    }

    @Test fun getInfoRejectsNonBooleanForcedPinState() {
        for (field in listOf(Cbor.Null, 0.cbor(), "false".cbor(), Cbor.Array(emptyList()))) {
            val channel = FidoChannel { _, _ -> success(info(extra = cborMap(12 to field))) }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.getInfo() }
            assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.NONE), result)
        }
    }

    @Test fun getInfoAcceptsMessageLimitsAboveGenericCollectionCount() {
        for (maximum in listOf(4097, 7609)) {
            val channel = FidoChannel { _, _ -> success(info(extra = cborMap(5 to maximum.cbor()))) }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.getInfo() }
            assertTrue(result.toString(), result is OperationResult.Completed)
        }
    }

    @Test fun malformedErrorPayloadCannotProveWriteOutcome() {
        for (wire in CardWire.entries) for (effectful in listOf(false, true)) for (accepted in listOf(false, true)) {
            for (suffix in listOf("ff", "a000", "a101")) {
                val response = byteArrayOf(0x31) + suffix.hexBytes()
                val apdu = FidoChannel { _, _ -> response }
                var nativeCount = 0
                val channel = if (wire == CardWire.APDU) apdu else object : CardChannel {
                    override val wire = CardWire.CTAP
                    override val medium = Medium.CONTACT
                    override val maxCommandBytes = 7609
                    override val supportsCtap2 = true
                    override fun transmit(command: ByteArray): ByteArray = error("Unexpected APDU")
                    override fun exchangeCtap2(command: ByteArray): ByteArray { nativeCount++; return response }
                    override fun close() = Unit
                }
                val result = CardSession(channel).run { session ->
                    val client = FidoClient(session).apply { select() }
                    if (accepted) session.applied()
                    client.command(6, effectful = effectful)
                } as OperationResult.Failed
                assertEquals(FailureCode.MALFORMED_RESPONSE, result.code)
                assertEquals(if (effectful) Effect.UNKNOWN else if (accepted) Effect.APPLIED else Effect.NONE, result.effect)
                assertEquals(accepted, result.writeAccepted)
                assertEquals(1, if (wire == CardWire.APDU) apdu.commands.size else nativeCount)
            }
        }
    }

    @Test fun optionalValidErrorCborRetainsStatusSemantics() {
        for (suffix in listOf("", "a0", "a10101")) {
            val channel = FidoChannel { _, _ -> byteArrayOf(0x31) + suffix.hexBytes() }
            val result = CardSession(channel).run { session ->
                FidoClient(session).apply { select() }.command(6, effectful = true)
            }
            assertEquals(OperationResult.Failed(FailureCode.PIN_INVALID, Effect.APPLIED, 0x31), result)
        }
    }

    @Test fun advertisedLimitRejectsOversizedRegistrationBeforeMutationDispatch() {
        for (wire in CardWire.entries) {
            val apdu = FidoChannel { code, _ ->
                assertEquals(4, code)
                success(info(extra = cborMap(5 to 256.cbor())))
            }
            var typedCount = 0
            val channel = if (wire == CardWire.APDU) apdu else object : CardChannel {
                override val wire = CardWire.CTAP
                override val medium = Medium.CONTACT
                override val maxCommandBytes = 7609
                override val supportsCtap2 = true
                override fun transmit(command: ByteArray): ByteArray = error("Unexpected APDU")
                override fun exchangeCtap2(command: ByteArray): ByteArray {
                    typedCount++
                    assertArrayEquals(byteArrayOf(4), command)
                    return success(info(extra = cborMap(5 to 256.cbor())))
                }
                override fun close() = Unit
            }
            val request = MakeCredentialRequest("example.test", "example", FidoUser(byteArrayOf(1), null, null),
                ByteArray(32), residentKey = false, excludeIds = listOf(ByteArray(512)))
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.makeCredential(request) }
            assertEquals(OperationResult.Failed(FailureCode.CAPACITY_EXCEEDED, Effect.NONE), result)
            assertEquals(1, if (wire == CardWire.APDU) apdu.commands.size else typedCount)
        }
    }

    @Test fun sessionKeepsDefaultLimitAndAdvertisedLargerLimitAcrossClients() {
        val channel = FidoChannel { code, _ -> if (code == 4) success(info(extra = cborMap(5 to 2048.cbor()))) else success() }
        val session = CardSession(channel)
        val client = FidoClient(session).apply { select() }
        val parameters = cborMap(1 to ByteArray(1100).cbor())
        try { client.command(6, parameters); fail("Default maximum must reject oversized command") }
        catch (failure: CardFailure) { assertEquals(FailureCode.CAPACITY_EXCEEDED, failure.code) }
        assertTrue(channel.commands.isEmpty())
        client.getInfo()
        FidoClient(session).apply { select() }.command(6, parameters)
        assertEquals(listOf(4, 6), channel.commands.map { it.first })
        session.close()
    }

    @Test fun typedFidoSelectUsesCapabilityAndNoSyntheticSelect() {
        for (supported in listOf(false, true)) {
            var count = 0
            val channel = object : CardChannel {
                override val wire = CardWire.CTAP
                override val medium = Medium.CONTACT
                override val maxCommandBytes = 7609
                override val supportsCtap2 = supported
                override fun transmit(command: ByteArray): ByteArray = error("Unexpected APDU SELECT")
                override fun exchangeCtap2(command: ByteArray): ByteArray {
                    count++
                    assertArrayEquals(byteArrayOf(4), command)
                    return byteArrayOf(0) + CborCodec.encode(info())
                }
                override fun close() = Unit
            }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.getInfo() }
            if (supported) assertTrue(result is OperationResult.Completed)
            else assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE), result)
            assertEquals(if (supported) 1 else 0, count)
        }
    }

    @Test fun ambiguousTerminalWriteStatusesPreserveUnknownEffectAndEarlierAcceptedWrite() {
        for (status in listOf(0x7f, 0x2d)) {
            val channel = FidoChannel { _, _ -> byteArrayOf(status.toByte()) }
            val result = CardSession(channel).run { session ->
                val client = FidoClient(session).apply { select() }
                session.applied()
                client.command(0x0d, effectful = true, applied = true)
            } as OperationResult.Failed
            assertEquals(Effect.UNKNOWN, result.effect)
            assertTrue(result.writeAccepted)
            assertEquals(status, result.status)
        }
    }

    @Test fun malformedEffectfulCborPreservesUnknownEffect() {
        val channel = FidoChannel { _, _ -> byteArrayOf(0, 0xff.toByte()) }
        val result = CardSession(channel).run { session ->
            FidoClient(session).apply { select() }.command(6, effectful = true)
        } as OperationResult.Failed
        assertEquals(FailureCode.MALFORMED_RESPONSE, result.code)
        assertEquals(Effect.UNKNOWN, result.effect)
    }

    @Test fun userPresenceErrorReleasesSessionWithoutReconnectOrReplay() {
        val channel = FidoChannel { _, _ -> byteArrayOf(0x3b) }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.getInfo() }
        assertEquals(OperationResult.Failed(FailureCode.PHYSICAL_ACTION_REQUIRED, Effect.NONE, 0x3b), result)
        assertTrue(channel.closed); assertEquals(1, channel.selectCount); assertEquals(1, channel.commands.size)
    }
    @Test fun resetDenialClosesSessionWithoutStatusReadOrReplay() {
        val channel = FidoChannel { code, parameters ->
            assertEquals(7, code); assertNull(parameters); byteArrayOf(0x27)
        }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.reset() }
        assertEquals(OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE, 0x27), result)
        assertEquals(listOf(7), channel.commands.map { it.first })
        assertEquals(1, channel.selectCount)
        assertTrue(channel.closed)
    }
    @Test fun alwaysUvChecksCurrentStateThenTogglesExactlyOnceAndReadsBack() {
        var enabled = false
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("authnrCfg" to true, "alwaysUv" to enabled)))
            0x0d -> { assertEquals(2L, map!![1].number()); assertNull(map[3]); enabled = !enabled; success() }
            else -> error("unexpected command")
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.setAlwaysUv(null, true) }
        assertTrue(result is OperationResult.Completed)
        assertEquals(listOf(4, 13, 4), channel.commands.map { it.first })
        assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect)
    }
    @Test fun alwaysUvAlreadySetDoesNotToggle() {
        val channel = FidoChannel { code, _ -> assertEquals(4, code); success(info(mapOf("alwaysUv" to true))) }
        val pin = "1234".toCharArray()
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.setAlwaysUv(pin, true) }
        assertEquals(Effect.NONE, (result as OperationResult.Completed).effect)
        assertArrayEquals(CharArray(4), pin); assertEquals(1, channel.commands.size)
    }
    @Test fun unknownToggleResultDoesNotRetryOrReadBack() {
        val channel = FidoChannel { code, _ -> when (code) {
            4 -> success(info(mapOf("authnrCfg" to true, "alwaysUv" to false)))
            else -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.setAlwaysUv(null, true) }
        assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE), result)
        assertEquals(listOf(4, 13), channel.commands.map { it.first }); assertTrue(channel.closed)
    }
    @Test fun missingCtapStatusAfterDispatchedToggleIsUnknown() {
        val channel = FidoChannel { code, _ -> if (code == 4)
            success(info(mapOf("authnrCfg" to true, "alwaysUv" to false))) else byteArrayOf() }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.setAlwaysUv(null, true) }
        assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.UNKNOWN), result)
        assertEquals(listOf(4, 13), channel.commands.map { it.first })
    }
    @Test fun confirmedToggleWithMalformedReadbackRetainsAppliedEffect() {
        var read = 0
        val channel = FidoChannel { code, _ -> if (code == 4) {
            if (++read == 1) success(info(mapOf("authnrCfg" to true, "alwaysUv" to false))) else success(cborMap())
        } else success() }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.setAlwaysUv(null, true) }
        assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED, writeAccepted = true), result)
    }
    @Test fun managementUsesFreshUnscopedCmTokenAndAuthenticatedDelete() {
        for (version in 1..2) {
            val server = PinServer(version)
            val id = byteArrayOf(9, 8, 7)
            var deleted = false
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true), listOf(version)))
                6 -> server.respond(map!!, expectedPermission = 4, expectedRp = null)
                0x0a -> {
                    val sub = map!![1].number().toInt()
                    assertEquals(version.toLong(), map[3].number())
                    val message = byteArrayOf(sub.toByte()) + (map[2]?.map()?.let(CborCodec::encode) ?: byteArrayOf())
                    assertArrayEquals(PinProtocol.authenticate(version, server.token, message), map[4].bytes())
                    when (sub) {
                        2 -> success(cborMap(3 to textMap("id" to "example.test".cbor()), 4 to ByteArray(32).cbor(), 5 to 1.cbor()))
                        4 -> if (deleted) byteArrayOf(0x2e) else success(cborMap(6 to FidoUser(byteArrayOf(1), null, null).encode(),
                            7 to descriptor(id), 8 to cborMap(), 9 to 1.cbor()))
                        6 -> { assertArrayEquals(id, parseDescriptor(map[2].map()[2])); deleted = true; success() }
                        else -> error("Unexpected management command")
                    }
                }
                else -> error("unexpected command")
            } }
            val pin = "1234".toCharArray()
            val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.deleteCredential(pin, id) }
            assertTrue(result is OperationResult.Completed); assertArrayEquals(CharArray(4), pin)
            assertEquals(1, server.tokenRequests)
            assertEquals(listOf(4, 6, 6, 10, 10, 10, 10), channel.commands.map { it.first })
        }
    }
    @Test fun wrongPinDoesNotAcquireAgainAndAlwaysWipesCallerPin() {
        val server = PinServer(2)
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true)))
            6 -> if (map!![2].number() == 9L) byteArrayOf(0x31) else server.respond(map, 4, null)
            else -> error("mutation after failed PIN")
        } }
        val pin = "9999".toCharArray()
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.deleteCredential(pin, byteArrayOf(1)) }
        assertTrue(result is OperationResult.Failed); assertArrayEquals(CharArray(4), pin)
        assertEquals(Effect.APPLIED, (result as OperationResult.Failed).effect)
        assertEquals(1, channel.commands.count { it.second?.get(2)?.let { value -> value.number() == 9L } == true })
    }
    @Test fun retryCounterResetDoesNotClaimRejectedDeletionWasAccepted() {
        val server = PinServer(2)
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true)))
            6 -> server.respond(map!!, 4, null)
            10 -> when (map!![1].number().toInt()) {
                2 -> success(cborMap(3 to textMap("id" to "example.test".cbor()), 4 to ByteArray(32).cbor(), 5 to 1.cbor()))
                4 -> success(cborMap(6 to FidoUser(byteArrayOf(1), null, null).encode(), 7 to descriptor(byteArrayOf(1)), 8 to cborMap(), 9 to 1.cbor()))
                6 -> byteArrayOf(0x27)
                else -> error("Unexpected management command")
            }
            else -> error("unexpected command")
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.deleteCredential("1234".toCharArray(), byteArrayOf(1)) }
        assertEquals(OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.APPLIED, 0x27, writeAccepted = false), result)
    }

    @Test fun blockedAndPolicyFailuresReportRetryEffectsWithoutClaimingTargetWrite() {
        for ((status, effect) in listOf(0x32 to Effect.UNKNOWN, 0x34 to Effect.APPLIED, 0x37 to Effect.APPLIED)) {
            val server = PinServer(2)
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true)))
                6 -> if (map!![2].number() == 9L) byteArrayOf(status.toByte()) else server.respond(map, 4, null)
                else -> error("target write must not execute")
            } }
            val pin = "1234".toCharArray()
            val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.deleteCredential(pin, byteArrayOf(1)) }
            assertTrue(result is OperationResult.Failed)
            val failure = result as OperationResult.Failed
            assertEquals(effect, failure.effect); assertFalse(failure.writeAccepted); assertEquals(status, failure.status)
            assertArrayEquals(CharArray(4), pin); assertTrue(channel.closed)
        }
    }
    @Test fun uncertainRetryStatePreservesEarlierAcceptedWriteLatch() {
        val channel = FidoChannel { _, _ -> byteArrayOf(0x32) }
        val result = CardSession(channel).run { session ->
            val client = FidoClient(session).apply { select() }
            session.applied()
            client.command(6, cborMap(2 to 9.cbor()), effectful = true)
        }
        assertEquals(OperationResult.Failed(FailureCode.PIN_BLOCKED, Effect.UNKNOWN, 0x32, writeAccepted = true), result)
    }

    @Test fun assertionTokenIsRpBoundAndRequiresPresenceAndUv() {
        val server = PinServer(2); val rp = "cardkey.example"; val id = byteArrayOf(1, 2, 3)
        val auth = sha256(rp.toByteArray()) + byteArrayOf(5, 0, 0, 0, 1)
        val hash = ByteArray(32) { 3 }
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("pinUvAuthToken" to true)))
            6 -> server.respond(map!!, 2, rp)
            2 -> {
                assertEquals(rp, map!![1].text())
                assertArrayEquals(PinProtocol.authenticate(2, server.token, hash), map[6].bytes())
                success(cborMap(1 to descriptor(id), 2 to auth.cbor(), 3 to byteArrayOf(1).cbor()))
            }
            else -> error("unexpected command")
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }
            .getAssertion(GetAssertionRequest(rp, hash, listOf(id)), "1234".toCharArray()) }
        assertEquals(1, (result as OperationResult.Completed).value.size)
        assertEquals(Effect.APPLIED, result.effect)
    }

    internal class PinServer(private val version: Int, private val expectedPin: ByteArray = "1234".toByteArray()) {
        private val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        private val pub = pair.public as ECPublicKey
        val cose = cborMap(1 to 2.cbor(), 3 to (-25).cbor(), -1 to 1.cbor(),
            -2 to fixed32(pub.w.affineX).cbor(), -3 to fixed32(pub.w.affineY).cbor())
        val token = ByteArray(32) { (it + 19).toByte() }
        var tokenRequests = 0
        fun keyFor(host: Cbor.Map): ByteArray {
            val secret = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(coseEcPublic(host, -25), true); generateSecret() }
            return try { PinProtocol.derive(version, secret) } finally { secret.fill(0) }
        }
        fun respond(map: Cbor.Map, expectedPermission: Int, expectedRp: String?): ByteArray {
            assertEquals(version.toLong(), map[1].number())
            if (map[2].number() == 2L) return success(cborMap(1 to cose))
            assertEquals(9L, map[2].number()); tokenRequests++
            assertEquals(expectedPermission.toLong(), map[9].number())
            assertEquals(expectedRp, map[10]?.text())
            val key = keyFor(map[3].map())
            try {
                assertArrayEquals(sha256(expectedPin).copyOf(16), PinProtocol.decrypt(version, key, map[6].bytes()))
                return success(cborMap(2 to PinProtocol.encrypt(version, key, token).cbor()))
            } finally { key.fill(0) }
        }
    }
    @Test fun retryHelperPreservesThePublicPinAndUvReadbackWithOneInfoRead() {
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("uv" to true)))
            6 -> when (map!![2].number()) {
                1L -> success(cborMap(3 to 8.cbor(), 4 to false.cbor()))
                7L -> success(cborMap(5 to 3.cbor()))
                else -> error("Retry inspection authenticated")
            }
            else -> error("Unexpected retry command")
        } }
        val result = CardSession(channel).run { FidoClient(it).apply { select() }.retries() } as OperationResult.Completed
        assertEquals(PinRetries(8, false, 3), result.value)
        assertEquals(listOf(4, 6, 6), channel.commands.map { it.first })
    }

}
