package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

class PinManagementTest {
    @Test fun settingAndChangingPinEncryptAndAuthenticateBothProtocols() {
        for (version in 1..2) for (changing in listOf(false, true))
            for ((oldValue, newValue) in listOf("1234" to "5678", "é1234" to "é5678", "e\u03011234" to "e\u03015678")) {
            val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
            val pub = pair.public as ECPublicKey
            val cose = cborMap(1 to 2.cbor(), 3 to (-25).cbor(), -1 to 1.cbor(),
                -2 to fixed32(pub.w.affineX).cbor(), -3 to fixed32(pub.w.affineY).cbor())
            var writes = 0
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("clientPin" to changing), listOf(version)))
                6 -> if (map!![2].number() == 2L) success(cborMap(1 to cose)) else {
                    writes++; assertEquals(if (changing) 4L else 3L, map[2].number())
                    val z = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(coseEcPublic(map[3].map(), -25), true); generateSecret() }
                    val shared = PinProtocol.derive(version, z)
                    val newEncrypted = map[5].bytes(); val oldEncrypted = map[6]?.bytes()
                    assertArrayEquals((if (newValue == "5678") "5678" else "é5678").toByteArray().copyOf(64), PinProtocol.decrypt(version, shared, newEncrypted))
                    if (changing) assertArrayEquals(sha256((if (oldValue == "1234") "1234" else "é1234").toByteArray()).copyOf(16), PinProtocol.decrypt(version, shared, oldEncrypted!!))
                    else assertNull(oldEncrypted)
                    assertArrayEquals(PinProtocol.authenticate(version, shared, newEncrypted + (oldEncrypted ?: byteArrayOf())), map[4].bytes())
                    success()
                }
                else -> error("unexpected command")
            } }
            val old = oldValue.toCharArray(); val new = newValue.toCharArray()
            val result = CardSession(channel).run { session ->
                val client = FidoClient(session).apply { select() }
                if (changing) client.changePin(old, new) else client.setPin(new)
            }
            assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect); assertEquals(1, writes)
            assertArrayEquals(CharArray(newValue.length), new); if (changing) assertArrayEquals(CharArray(oldValue.length), old)
        }
    }
    @Test fun configurationUsesAcfgPermissionAndDomainSeparatedAuthentication() {
        val server = FidoClientTest.PinServer(2); var enabled = false
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true, "authnrCfg" to true, "ep" to enabled)))
            6 -> server.respond(map!!, 32, null)
            13 -> {
                val message = ByteArray(32) { 0xff.toByte() } + byteArrayOf(13, 1)
                assertEquals(1L, map!![1].number()); assertNull(map[2])
                assertArrayEquals(PinProtocol.authenticate(2, server.token, message), map[4].bytes())
                enabled = true; success()
            }
            else -> error("unexpected command")
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.enableEnterpriseAttestation("1234".toCharArray()) }
        assertEquals(true, (result as OperationResult.Completed).value.options["ep"])
        assertEquals(listOf(4, 6, 6, 13, 4), channel.commands.map { it.first })
    }
    @Test fun normalizedShortPinIsRejectedBeforeAgreementOrMutationAndErased() {
        for (changing in listOf(false, true)) {
            val channel = FidoChannel { code, _ -> check(code == 4); success(info(mapOf("clientPin" to changing))) }
            val pin = "e\u0301ab".toCharArray(); val old = "é1234".toCharArray()
            val result = CardSession(channel).run {
                val client = FidoClient(it).apply { select() }
                if (changing) client.changePin(old, pin) else client.setPin(pin)
            }
            assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), result)
            assertEquals(listOf(4), channel.commands.map { it.first })
            assertArrayEquals(CharArray(pin.size), pin)
            if (changing) assertArrayEquals(CharArray(old.size), old)
        }
    }
    @Test fun tokenHashesCanonicalCurrentPinForBothProtocolsAndErasesOnUnknownResult() {
        for (version in 1..2) for (value in listOf("é1234", "e\u03011234")) for (lost in listOf(false, true)) {
            val server = FidoClientTest.PinServer(version, "é1234".toByteArray())
            val channel = FidoChannel { code, params -> when(code) {
                4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true), protocols = listOf(version)))
                6 -> server.respond(params!!, 4, null).also {
                    if (lost && params[2].number() == 9L) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                }
                10 -> success(cborMap(1 to 0.cbor(), 2 to 1.cbor()))
                else -> error("Unexpected command")
            } }
            val pin = value.toCharArray()
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.capacity(pin) }
            if (lost) assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
                dispatch = Dispatch.RESPONSE_UNAVAILABLE), result)
            else assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect)
            assertEquals(1, server.tokenRequests)
            assertArrayEquals(CharArray(value.length), pin)
        }
    }

}
