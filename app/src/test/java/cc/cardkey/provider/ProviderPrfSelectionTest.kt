package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class ProviderPrfSelectionTest {
    private val rp = "cardkey.example"
    private val firstId = byteArrayOf(1)
    private val selectedId = byteArrayOf(2)
    private val account = FidoUser(byteArrayOf(9), "Account", "Account")
    private val data = ClientData(byteArrayOf(1), sha256(byteArrayOf(1)), false)
    private val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private fun request(fallback: Boolean = true): ParsedGet = RequestParser.parseGet("""{
        "rpId":"cardkey.example","challenge":"AQ","userVerification":"discouraged",
        "allowCredentials":[{"type":"public-key","id":"AQ"},{"type":"public-key","id":"Ag"}],
        "extensions":{"prf":{${if (fallback) "\"eval\":{\"first\":\"Aw\"}," else ""}
            "evalByCredential":{"Ag":{"first":"BA","second":"BQ"}}}}
    }""")
    private fun sign(message: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message); sign() }
    private fun response(id: ByteArray = selectedId, up: Boolean = false, uv: Boolean = true,
        count: Int? = null, rpId: String = rp, extensions: Cbor.Map? = null, user: FidoUser? = account): Cbor.Map {
        val flags = (if (up) 1 else 0) or (if (uv) 4 else 0) or (if (extensions != null) 0x80 else 0)
        val auth = sha256(rpId.toByteArray()) + byteArrayOf(flags.toByte(), 0, 0, 0, if (up) 2 else 1) +
            (extensions?.let(CborCodec::encode) ?: byteArrayOf())
        return cborMap(1 to descriptor(id), 2 to auth.cbor(), 3 to sign(auth + data.hash).cbor(),
            4 to user?.encode(), 5 to count?.cbor())
    }
    private fun execute(channel: FidoChannel, request: ParsedGet = request(), pin: CharArray? = null) =
        CardSession(channel).run { CardCredentialOperation.get(it, request, data, pin) }

    @Test fun nonuniformInputsUseUvPreflightThenOnePresenceAssertionForSelectedCredential() {
        for (version in 1..2) for (id in listOf(firstId, selectedId)) {
            val server = FidoClientTest.PinServer(version)
            var assertions = 0
            val expected = if (id.contentEquals(selectedId)) PrfInputs(byteArrayOf(4), byteArrayOf(5)).salts()
                else PrfInputs(byteArrayOf(3)).salts()
            val output = ByteArray(expected.size) { 11 }
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true), listOf(version), listOf("hmac-secret")))
                6 -> server.respond(map!!, 2, rp)
                2 -> {
                    assertions++
                    assertEquals(rp, map!![1].text())
                    assertArrayEquals(data.hash, map[2].bytes())
                    assertArrayEquals(PinProtocol.authenticate(version, server.token, data.hash), map[6].bytes())
                    assertNull(map[5].map()["uv"])
                    if (assertions == 1) {
                        assertFalse(map[5].map()["up"].bool())
                        assertEquals(2, map[3].array().size)
                        assertArrayEquals(firstId, parseDescriptor(map[3].array()[0]))
                        assertArrayEquals(selectedId, parseDescriptor(map[3].array()[1]))
                        assertNull(map[4])
                        success(response(id))
                    } else {
                        assertEquals(2, assertions)
                        assertTrue(map[5].map()["up"].bool())
                        assertArrayEquals(id, parseDescriptor(map[3].array().single()))
                        val hmac = map[4].map()["hmac-secret"].map()
                        val shared = server.keyFor(hmac[1].map())
                        assertArrayEquals(expected, PinProtocol.decrypt(version, shared, hmac[2].bytes()))
                        assertArrayEquals(PinProtocol.authenticate(version, shared, hmac[2].bytes()), hmac[3].bytes())
                        val ext = textMap("hmac-secret" to PinProtocol.encrypt(version, shared, output).cbor())
                        shared.fill(0)
                        success(response(id, up = true, extensions = ext))
                    }
                }
                else -> error("Unexpected command $code")
            } }
            val pin = "1234".toCharArray()
            val result = (execute(channel, pin = pin) as OperationResult.Completed).value.single()
            assertArrayEquals(id, result.assertion.credentialId)
            assertArrayEquals(account.id, result.assertion.user!!.id)
            assertArrayEquals(output, result.extensions.prfResult)
            assertTrue(AuthenticatorData.parse(result.assertion.authData).userPresent)
            assertEquals(2, assertions)
            assertEquals(2, server.tokenRequests)
            assertEquals(1, channel.selectCount)
            assertTrue(pin.all { it == '\u0000' })
            assertFalse(channel.commands.any { it.first == 8 })
            result.close()
        }
    }

    @Test fun cancellationAfterSuccessfulPreflightBlocksFurtherDispatchWhenCloseIsAdvisory() {
        for (version in 1..2) {
            val runner = ProviderOperationRunner()
            val server = FidoClientTest.PinServer(version)
            var assertions = 0
            var closeCalls = 0
            var cancelledAtPreflight = false
            var commandsAtPreflight = 0
            val underlying = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true), listOf(version), listOf("hmac-secret")))
                6 -> server.respond(map!!, 2, rp)
                2 -> {
                    assertions++
                    if (assertions == 1) {
                        assertFalse(map!![5].map()["up"].bool())
                        assertNull(map[4])
                        success(response())
                    } else {
                        val hmac = map!![4].map()["hmac-secret"].map()
                        val shared = server.keyFor(hmac[1].map())
                        val ext = textMap("hmac-secret" to PinProtocol.encrypt(version, shared, ByteArray(64)).cbor())
                        shared.fill(0)
                        success(response(up = true, extensions = ext))
                    }
                }
                else -> error("Unexpected command $code")
            } }
            val advisoryCloseChannel = object : CardChannel by underlying {
                override fun transmit(command: ByteArray): ByteArray {
                    val response = underlying.transmit(command)
                    if (assertions == 1 && !cancelledAtPreflight) {
                        cancelledAtPreflight = true
                        commandsAtPreflight = underlying.commands.size
                        runner.cancel()
                        assertNull(CardOperationGate.tryAcquire())
                    }
                    return response
                }
                override fun cancel() { closeCalls++ }
                override fun close() { closeCalls++ }
            }
            val pin = "1234".toCharArray()
            val result = runner.executeAndConfirm(null, request(), data, pin) { advisoryCloseChannel }
            assertTrue(result is OperationResult.Failed)
            val failure = result as OperationResult.Failed
            assertEquals(FailureCode.CANCELLED, failure.code)
            assertEquals(Effect.APPLIED, failure.effect)
            assertTrue(failure.writeAccepted)
            assertEquals(commandsAtPreflight, underlying.commands.size)
            assertEquals(1, assertions)
            assertEquals(1, server.tokenRequests)
            assertEquals(1, underlying.selectCount)
            assertFalse(underlying.closed)
            assertTrue(closeCalls > 0)
            assertTrue(pin.all { it == '\u0000' })
            requireNotNull(CardOperationGate.tryAcquire()).close()
        }
    }

    @Test fun cancellationAfterFinalDispatchPreservesUnknownOutcomeAndKnownPreflightEffect() {
        val runner = ProviderOperationRunner()
        val server = FidoClientTest.PinServer(2)
        var assertions = 0
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("uv" to true), extensions = listOf("hmac-secret")))
            6 -> success(cborMap(1 to server.cose))
            2 -> if (++assertions == 1) success(response()) else {
                assertTrue(map!![5].map()["up"].bool())
                assertArrayEquals(selectedId, parseDescriptor(map[3].array().single()))
                runner.cancel()
                assertNull(CardOperationGate.tryAcquire())
                throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            }
            else -> error("Unexpected command $code")
        } }
        val result = runner.executeAndConfirm(null, request(), data, null) { channel }
        assertTrue(result is OperationResult.Failed)
        val failure = result as OperationResult.Failed
        assertEquals(FailureCode.TRANSPORT_FAILURE, failure.code)
        assertEquals(Effect.UNKNOWN, failure.effect)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure.dispatch)
        assertTrue(failure.writeAccepted)
        assertEquals(2, assertions)
        assertEquals(1, channel.selectCount)
        assertTrue(channel.closed)
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    @Test fun preflightMalformedIdentityFlagsExtensionsAndCountAbortBeforeFinalAssertion() {
        val cases = listOf(response(id = byteArrayOf(3)), response(uv = false),
            response(rpId = "wrong.example"), response(count = 2), response(count = 0),
            response(extensions = textMap("uvm" to true.cbor())),
            Cbor.Map(response().value + cborMap(4 to textMap("id" to byteArrayOf().cbor())).value),
            Cbor.Map(response().value + cborMap(3 to byteArrayOf(1).cbor()).value))
        for (bad in cases) {
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> success(info(mapOf("uv" to true), extensions = listOf("hmac-secret")))
                2 -> success(bad)
                else -> error("No continuation, key agreement, or final assertion is allowed")
            } }
            assertTrue(execute(channel) is OperationResult.Failed)
            assertEquals(1, channel.commands.count { it.first == 2 })
            assertFalse(channel.commands.any { it.first == 6 || it.first == 8 })
        }
    }

    @Test fun preflightRemovalUnknownAndNoCredentialDoNotRetryOrSendFinalAssertion() {
        for (failure in listOf("removed", "unknown", "noCredential")) {
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> success(info(mapOf("uv" to true), extensions = listOf("hmac-secret")))
                2 -> when (failure) {
                    "removed" -> throw TransportException(Dispatch.NOT_DISPATCHED)
                    "unknown" -> throw IOException("lost preflight response")
                    else -> byteArrayOf(0x2e)
                }
                else -> error("No final assertion is allowed")
            } }
            val result = execute(channel) as OperationResult.Failed
            if (failure == "unknown") assertEquals(Effect.UNKNOWN, result.effect)
            assertEquals(1, channel.commands.count { it.first == 2 })
            assertFalse(channel.commands.any { it.first == 6 || it.first == 8 })
            assertTrue(channel.closed)
        }
    }

    @Test fun finalMustRetainSelectedCredentialAndUserAndRequirePresence() {
        for (failure in listOf("id", "user", "up")) {
            val server = FidoClientTest.PinServer(2)
            var count = 0
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> success(info(mapOf("uv" to true), extensions = listOf("hmac-secret")))
                6 -> success(cborMap(1 to server.cose))
                2 -> if (++count == 1) success(response()) else success(response(
                    id = if (failure == "id") firstId else selectedId,
                    up = failure != "up", user = if (failure == "user") account.copy(id = byteArrayOf(8)) else account))
                else -> error("Unexpected command")
            } }
            assertTrue(execute(channel) is OperationResult.Failed)
            assertEquals(2, count)
        }
    }

    @Test fun selectedCredentialWithoutPrfInputsReturnsNoEvaluationAndPreflightNeverBecomesLogin() {
        var assertions = 0
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("uv" to true), extensions = listOf("hmac-secret")))
            2 -> {
                assertions++
                assertTrue(map!![5].map()["uv"].bool())
                assertNull(map[4])
                success(response(firstId, up = assertions == 2, user = null))
            }
            else -> error("No key agreement or continuation allowed")
        } }
        val result = (execute(channel, request(fallback = false)) as OperationResult.Completed).value.single()
        assertEquals(2, assertions)
        assertNull(result.extensions.prfResult)
        assertEquals("{}", result.extensions.json()["prf"].toString())
        assertTrue(AuthenticatorData.parse(result.assertion.authData).userPresent)
    }
    @Test fun builtInUvPreflightMaySetPresenceButPinTokenPathMayNot() {
        for (usePin in listOf(false, true)) {
            val server = FidoClientTest.PinServer(2)
            var count = 0
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("uv" to true, "clientPin" to true, "pinUvAuthToken" to true),
                    listOf(2), listOf("hmac-secret")))
                6 -> if (usePin) server.respond(map!!, 2, rp) else success(cborMap(1 to server.cose))
                2 -> {
                    count++
                    if (count == 1) {
                        assertFalse(map!![5].map()["up"].bool())
                        if (usePin) { assertNull(map[5].map()["uv"]); assertNotNull(map[6]) }
                        else { assertTrue(map[5].map()["uv"].bool()); assertNull(map[6]) }
                        success(response(up = true))
                    } else {
                        assertFalse(usePin)
                        assertTrue(map!![5].map()["up"].bool())
                        assertArrayEquals(selectedId, parseDescriptor(map[3].array().single()))
                        val hmac = map[4].map()["hmac-secret"].map()
                        val shared = server.keyFor(hmac[1].map())
                        val ext = textMap("hmac-secret" to PinProtocol.encrypt(2, shared, ByteArray(64) { 7 }).cbor())
                        shared.fill(0)
                        success(response(up = true, extensions = ext))
                    }
                }
                else -> error("unexpected")
            } }
            val result = execute(channel, pin = if (usePin) "1234".toCharArray() else null)
            if (usePin) { assertTrue(result is OperationResult.Failed); assertEquals(1, count) }
            else { assertEquals(2, count); (result as OperationResult.Completed).value.single().close() }
        }
    }

}
