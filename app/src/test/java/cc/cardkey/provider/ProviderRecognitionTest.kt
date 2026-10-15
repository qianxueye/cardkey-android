package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderRecognitionTest {
    private val data = ClientData(byteArrayOf(), ByteArray(32), true)
    private fun get() = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.REQUIRED)
    private fun create() = ParsedCreate("example.com", "Example", FidoUser(byteArrayOf(1), "one", "One"), "AQ",
        ResidentKeyRequirement.DISCOURAGED, UserVerificationRequirement.REQUIRED, emptyList(), AttestationPreference.NONE)
    private fun claim(create: Boolean = false): RequestRegistry.Claim = RequestRegistry().let { registry ->
        registry.claim(registry.offer(OfferedRequest(if (create) ProviderRequestKind.CREATE else ProviderRequestKind.GET,
            "{}", data.hash, CallerSnapshot("synthetic.provider", setOf("certificate"), null), "fixture")))
    }
    private fun output(create: Boolean): ByteArray {
        val auth = sha256("example.com".toByteArray()) + byteArrayOf(if (create) 0x45 else 5, 0, 0, 0, 1)
        if (!create) return success(cborMap(1 to descriptor(byteArrayOf(1)), 2 to auth.cbor(),
            3 to byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 1).cbor()))
        val key = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair().public as ECPublicKey
        }
        val cose = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
            -2 to fixed32(key.w.affineX).cbor(), -3 to fixed32(key.w.affineY).cbor())
        return success(cborMap(1 to "none".cbor(), 2 to (auth + ByteArray(16) + byteArrayOf(0, 1, 1) + CborCodec.encode(cose)).cbor(),
            3 to cborMap()))
    }

    @Test fun createAndGetReserveOnceProbeOnceAndWaitForExplicitConfirmationOnHeldSession() = runTest {
        for (creating in listOf(false, true)) {
            val runner = ProviderOperationRunner()
            val claim = claim(creating)
            var connects = 0
            var observed: ProviderRecognition? = null
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> success(info(mapOf("uv" to true)))
                if (creating) 1 else 2 -> output(creating)
                else -> error("Unexpected command $code")
            } }
            val result = async { runner.recognizeAndExecute(if (creating) create() else null,
                if (creating) null else get(), data, claim = claim, onRecognized = { observed = it }) {
                connects++
                assertFalse(claim.reserveTransport())
                channel
            } }
            runCurrent()
            assertEquals(ProviderRecognition(false), observed)
            assertEquals(listOf(4), channel.commands.map { it.first })
            assertEquals(1, channel.selectCount)
            assertFalse(result.isCompleted)
            assertFalse(channel.closed)
            assertNull(CardOperationGate.tryAcquire())
            assertTrue(runner.confirm(null))
            assertFalse(runner.confirm(null))
            val completed = result.await() as OperationResult.Completed
            assertEquals(listOf(4, if (creating) 1 else 2), channel.commands.map { it.first })
            assertEquals(1, connects)
            assertEquals(1, channel.selectCount)
            assertTrue(channel.closed)
            when (val value = completed.value) {
                is ProviderOutput.Created -> value.credential.extensions.close()
                is ProviderOutput.Assertions -> value.values.forEach { it.close() }
            }
            assertFalse(claim.finish(cancelled = false))
            assertEquals(FailureCode.CANCELLED, (runner.recognizeAndExecute(null, get(), data,
                claim = claim, onRecognized = { error("Repeated observation") }) { error("Reconnected") } as OperationResult.Failed).code)
            CardOperationGate.tryAcquire()!!.close()
        }
    }

    @Test fun invalidRecognitionNeverPublishesOrAuthenticates() = runTest {
        for (response in listOf(byteArrayOf(1), success(cborMap()),
                success(info(extra = cborMap(1 to Cbor.Array(listOf("U2F_V2".cbor()))))))) {
            val runner = ProviderOperationRunner()
            val channel = FidoChannel { code, _ -> assertEquals(4, code); response }
            val result = runner.recognizeAndExecute(null, get(), data, claim = claim(),
                onRecognized = { error("Invalid info enabled confirmation") }) { channel }
            assertTrue(result is OperationResult.Failed)
            assertEquals(Effect.NONE, (result as OperationResult.Failed).effect)
            assertEquals(listOf(4), channel.commands.map { it.first })
            assertTrue(channel.closed)
            val stale = "1234".toCharArray()
            assertFalse(runner.confirm(stale))
            assertArrayEquals(CharArray(4), stale)
        }
    }

    @Test fun cancellationBeforeConfirmationClosesLeaseAndWipesLateInputWithoutCommands() = runTest {
        val runner = ProviderOperationRunner()
        val tooEarly = "1234".toCharArray()
        assertFalse(runner.confirm(tooEarly))
        assertArrayEquals(CharArray(4), tooEarly)
        val request = get().copy(extensions = GetExtensionInputs(largeBlobWrite = byteArrayOf(9)))
        val channel = FidoChannel { code, _ -> assertEquals(4, code); success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true))) }
        val result = async { runner.recognizeAndExecute(null, request, data, claim = claim(),
            onRecognized = { assertTrue(it.pinRequired) }) { channel } }
        runCurrent()
        runner.cancel()
        val late = "9876".toCharArray()
        assertFalse(runner.confirm(late))
        assertArrayEquals(CharArray(4), late)
        val failed = result.await() as OperationResult.Failed
        assertEquals(FailureCode.CANCELLED, failed.code)
        assertEquals(Effect.NONE, failed.effect)
        assertFalse(failed.writeAccepted)
        assertEquals(listOf(4), channel.commands.map { it.first })
        assertArrayEquals(byteArrayOf(0), request.extensions.largeBlobWrite)
        assertTrue(channel.closed)
        CardOperationGate.tryAcquire()!!.close()
    }

    @Test fun cancellationAfterConfirmationBeforeResumeDisposesCapturedPin() = runTest {
        val runner = ProviderOperationRunner()
        val channel = FidoChannel { code, _ -> assertEquals(4, code); success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true))) }
        val result = async { runner.recognizeAndExecute(null, get(), data, claim = claim(), onRecognized = {}) { channel } }
        runCurrent()
        val pin = "1234".toCharArray()
        assertTrue(runner.confirm(pin))
        assertFalse(runner.confirm(pin))
        assertArrayEquals("1234".toCharArray(), pin)
        runner.cancel()
        assertArrayEquals(CharArray(4), pin)
        assertEquals(Effect.NONE, (result.await() as OperationResult.Failed).effect)
        assertEquals(listOf(4), channel.commands.map { it.first })
    }

    @Test fun coroutineRetirementCancelsPendingConfirmationAndReleasesTransport() = runTest {
        val runner = ProviderOperationRunner()
        val channel = FidoChannel { _, _ -> success(info(mapOf("uv" to true))) }
        val result = launch { runner.recognizeAndExecute(null, get(), data, claim = claim(), onRecognized = {}) { channel } }
        runCurrent()
        result.cancelAndJoin()
        assertFalse(runner.confirm(null))
        assertFalse(runner.isActive)
        assertTrue(channel.closed)
        assertEquals(listOf(4), channel.commands.map { it.first })
        CardOperationGate.tryAcquire()!!.close()
    }

    @Test fun nfcConnectionLossAfterRecognitionUsesSameSessionAndNeverReconnects() = runTest {
        val runner = ProviderOperationRunner()
        var connects = 0
        var lost = false
        val delegate = FidoChannel { code, _ -> assertEquals(4, code); success(info(mapOf("uv" to true))) }
        val channel = object : CardChannel by delegate {
            override val medium = Medium.NFC
            override fun transmit(command: ByteArray): ByteArray {
                if (lost) throw TransportException(Dispatch.NOT_DISPATCHED)
                return delegate.transmit(command)
            }
        }
        val result = async { runner.recognizeAndExecute(null, get(), data, claim = claim(), onRecognized = {}) {
            connects++; channel
        } }
        runCurrent()
        lost = true
        assertTrue(runner.confirm(null))
        val failed = result.await() as OperationResult.Failed
        assertEquals(FailureCode.TRANSPORT_FAILURE, failed.code)
        assertEquals(Effect.NONE, failed.effect)
        assertEquals(Dispatch.NOT_DISPATCHED, failed.dispatch)
        assertEquals(1, connects)
        assertEquals(1, delegate.selectCount)
        assertEquals(listOf(4), delegate.commands.map { it.first })
        assertTrue(delegate.closed)
        assertFalse(runner.confirm(null))
    }

    @Test fun pinPolicyRetainsBuiltInUvAndNoPinFlowsAndRejectsUnavailableRequiredVerification() {
        fun recognition(options: Map<String, Boolean>, requirement: UserVerificationRequirement): ProviderRecognition {
            val channel = FidoChannel { _, _ -> success(info(options)) }
            return (CardSession(channel).run {
                val client = FidoClient(it); client.select()
                ProviderRecognition.from(client.getInfo(), null, get().copy(userVerification = requirement))
            } as OperationResult.Completed).value
        }
        assertFalse(recognition(mapOf("uv" to true, "clientPin" to true, "pinUvAuthToken" to true), UserVerificationRequirement.REQUIRED).pinRequired)
        assertFalse(recognition(emptyMap(), UserVerificationRequirement.DISCOURAGED).pinRequired)
        assertFalse(recognition(mapOf("clientPin" to true, "pinUvAuthToken" to true), UserVerificationRequirement.DISCOURAGED).pinRequired)
        assertTrue(recognition(mapOf("clientPin" to true, "pinUvAuthToken" to true), UserVerificationRequirement.REQUIRED).pinRequired)
        val channel = FidoChannel { _, _ -> success(info()) }
        val denied = CardSession(channel).run {
            val client = FidoClient(it); client.select()
            ProviderRecognition.from(client.getInfo(), null, get())
        } as OperationResult.Failed
        assertEquals(FailureCode.AUTHORIZATION_DENIED, denied.code)
    }

    @Test fun unavailablePermissionTokenRejectsMandatoryUvWithoutPublishingOrAuthenticating() = runTest {
        for (version in listOf(1, 2)) {
            for (capability in listOf("absent", "disabled", "force-change")) {
                for (requirement in listOf("required", "always-uv", "protected-create")) {
                    val options = mutableMapOf("clientPin" to true)
                    if (capability != "absent") options["pinUvAuthToken"] = capability == "force-change"
                    if (requirement == "always-uv") options["alwaysUv"] = true
                    val channel = FidoChannel { code, _ ->
                        assertEquals(4, code)
                        success(info(options, protocols = listOf(version),
                            extra = if (capability == "force-change") cborMap(12 to true.cbor()) else cborMap()))
                    }
                    val runner = ProviderOperationRunner()
                    val creating = requirement == "protected-create"
                    val result = runner.recognizeAndExecute(
                        if (creating) create().copy(userVerification = UserVerificationRequirement.PREFERRED) else null,
                        if (creating) null else get().copy(userVerification = if (requirement == "required")
                            UserVerificationRequirement.REQUIRED else UserVerificationRequirement.PREFERRED),
                        data, claim = claim(creating),
                        onRecognized = { error("Mandatory UV without an executable path enabled confirmation") }) { channel }
                        as OperationResult.Failed
                    assertEquals(FailureCode.AUTHORIZATION_DENIED, result.code)
                    assertEquals(Effect.NONE, result.effect)
                    assertFalse(result.writeAccepted)
                    assertEquals(listOf(4), channel.commands.map { it.first })
                    assertFalse(runner.confirm(null))
                    assertTrue(channel.closed)
                    CardOperationGate.tryAcquire()!!.close()
                }
            }
        }
    }

    @Test fun supportedPinTokenPathWaitsForConfirmationThenAuthenticatesOnTheHeldSession() = runTest {
        for (version in listOf(1, 2)) {
            val server = FidoClientTest.PinServer(version)
            val runner = ProviderOperationRunner()
            val claim = claim()
            var connects = 0
            val channel = FidoChannel { code, parameters -> when (code) {
                4 -> success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true), protocols = listOf(version)))
                6 -> server.respond(requireNotNull(parameters), 2, "example.com")
                2 -> {
                    assertArrayEquals(PinProtocol.authenticate(version, server.token, data.hash), requireNotNull(parameters)[6].bytes())
                    assertEquals(version.toLong(), parameters[7].number())
                    output(false)
                }
                else -> error("Unexpected command $code")
            } }
            val result = async { runner.recognizeAndExecute(null, get(), data, claim = claim,
                onRecognized = { assertTrue(it.pinRequired) }) {
                connects++
                assertFalse(claim.reserveTransport())
                channel
            } }
            runCurrent()
            assertEquals(listOf(4), channel.commands.map { it.first })
            assertEquals(0, server.tokenRequests)
            assertFalse(result.isCompleted)
            assertNull(CardOperationGate.tryAcquire())
            val pin = "1234".toCharArray()
            assertTrue(runner.confirm(pin))
            val completed = result.await() as OperationResult.Completed
            assertEquals(listOf(4, 6, 6, 2), channel.commands.map { it.first })
            assertEquals(1, server.tokenRequests)
            assertEquals(1, connects)
            assertEquals(1, channel.selectCount)
            assertTrue(channel.closed)
            assertArrayEquals(CharArray(4), pin)
            (completed.value as ProviderOutput.Assertions).values.forEach { it.close() }
            assertFalse(runner.confirm(null))
            assertEquals(FailureCode.CANCELLED, (runner.recognizeAndExecute(null, get(), data,
                claim = claim, onRecognized = { error("Repeated observation") }) { error("Reconnected") } as OperationResult.Failed).code)
            CardOperationGate.tryAcquire()!!.close()
        }
    }

}
