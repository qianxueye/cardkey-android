package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test

class FidoExtensionsTest {
    @Test fun hmacSecretEncryptsSaltsAndDecryptsOutputForBothProtocols() {
        for (version in 1..2) {
            val server = FidoClientTest.PinServer(version); val salts = ByteArray(64) { it.toByte() }; val expectedSalts = salts.copyOf()
            val output = ByteArray(64) { (it + 2).toByte() }; val rp = "cardkey.example"
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(protocols = listOf(version), extensions = listOf("hmac-secret")))
                6 -> success(cborMap(1 to server.cose))
                2 -> {
                    val extension = map!![4].map()["hmac-secret"].map()
                    assertEquals(version.toLong(), extension[4].number())
                    val secret = server.keyFor(extension[1].map())
                    assertArrayEquals(expectedSalts, PinProtocol.decrypt(version, secret, extension[2].bytes()))
                    assertArrayEquals(PinProtocol.authenticate(version, secret, extension[2].bytes()), extension[3].bytes())
                    val encoded = CborCodec.encode(textMap("hmac-secret" to PinProtocol.encrypt(version, secret, output).cbor()))
                    val auth = sha256(rp.toByteArray()) + byteArrayOf(0x81.toByte(), 0, 0, 0, 0) + encoded
                    secret.fill(0)
                    success(cborMap(1 to descriptor(byteArrayOf(1)), 2 to auth.cbor(), 3 to byteArrayOf(1).cbor()))
                }
                else -> error("unexpected command")
            } }
            val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.getAssertion(
                GetAssertionRequest(rp, ByteArray(32), listOf(byteArrayOf(1)), requireUv = false,
                    extensions = AssertionExtensions(hmacSalts = salts))) }
            assertArrayEquals(output, (result as OperationResult.Completed).value.single().hmacSecret)
            assertArrayEquals(ByteArray(64), salts)
        }
    }
    @Test fun unsupportedExtensionIsRejectedBeforeCredentialMutationAndSaltsAreWiped() {
        val salts = ByteArray(32) { 5 }
        val channel = FidoChannel { code, _ -> assertEquals(4, code); success(info()) }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.getAssertion(
            GetAssertionRequest("cardkey.example", ByteArray(32), extensions = AssertionExtensions(hmacSalts = salts))) }
        assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE), result)
        assertArrayEquals(ByteArray(32), salts)
    }
    @Test fun rpEnumerationUsesOneCmTokenAndUnauthenticatedContinuation() {
        val server = FidoClientTest.PinServer(2)
        var index = 0
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true)))
            6 -> server.respond(map!!, 4, null)
            10 -> {
                assertEquals(if (index == 0) 2L else 3L, map!![1].number())
                if (index > 0) assertEquals(1, map.value.size)
                index++
                success(cborMap(3 to textMap("id" to "rp$index.example".cbor()), 4 to ByteArray(32) { index.toByte() }.cbor(),
                    5 to if (index == 1) 2.cbor() else null))
            }
            else -> error("unexpected command")
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.enumerateRelyingParties("1234".toCharArray()) }
        assertEquals(listOf("rp1.example", "rp2.example"), (result as OperationResult.Completed).value.map { it.id })
        assertEquals(1, server.tokenRequests)
    }
    @Test fun minPinLengthCanExplicitlyClearDisclosureAllowlistAndChecksReadback() {
        var length = 4
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("authnrCfg" to true, "setMinPINLength" to true), extensions = listOf("minPinLength"),
                extra = cborMap(13 to length.cbor(), 16 to 2.cbor())))
            13 -> {
                assertEquals(3L, map!![1].number()); val params = map[2].map()
                assertTrue(params[2].array().isEmpty()); length = params[1].count(); success()
            }
            else -> error("unexpected command")
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.setMinPinLength(null, 6, emptyList()) }
        assertEquals(6, (result as OperationResult.Completed).value.minPinLength)
    }
}
