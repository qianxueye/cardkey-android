package cc.cardkey.provider

import cc.cardkey.integration.SyntheticHidPeer
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class TypedProviderWorkflowTest(numbered: Boolean, private val pinVersion: Int) {
    private val reports = if (numbered) cc.cardkey.transport.FidoHidReports(16, 32, 3, 9) else cc.cardkey.transport.FidoHidReports()
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "reports={0},PIN={1}")
        fun reports() = listOf(
            arrayOf<Any>(false, 2),
            arrayOf<Any>(true, 2),
            arrayOf<Any>(true, 1),
        )
    }
    private val rp = "cardkey.example"
    private val credential = ByteArray(100) { it.toByte() }
    private val data = ClientData(byteArrayOf(1, 2, 3), sha256(byteArrayOf(1, 2, 3)), false)
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private val key = (pair.public as ECPublicKey).let {
        cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
            -2 to fixed32(it.w.affineX).cbor(), -3 to fixed32(it.w.affineY).cbor())
    }
    private fun sign(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private); update(bytes); sign()
    }
    private fun request(extensions: GetExtensionInputs = GetExtensionInputs()) =
        ParsedGet(rp, "AQID", listOf(credential), UserVerificationRequirement.REQUIRED, extensions)
    private fun creation() = ParsedCreate(rp, "Synthetic RP", FidoUser(byteArrayOf(1), "synthetic", "Synthetic"),
        "AQID", ResidentKeyRequirement.DISCOURAGED, UserVerificationRequirement.REQUIRED,
        emptyList(), AttestationPreference.DIRECT)
    private fun assertion(blobKey: ByteArray? = null): Cbor.Map {
        val auth = sha256(rp.toByteArray()) + byteArrayOf(5, 0, 0, 0, 1)
        return cborMap(1 to descriptor(credential), 2 to auth.cbor(),
            3 to sign(auth + data.hash).cbor(), 7 to blobKey?.cbor())
    }
    private fun claim(registry: RequestRegistry): RequestRegistry.Claim = registry.claim(registry.offer(
        OfferedRequest(ProviderRequestKind.GET, "{}", data.hash,
            CallerSnapshot("test.package", setOf("certificate"), null), "option")))

    @Test fun framedRegistrationAndAssertionKeepRealSignaturesAndOneInitializationPerOperation() {
        val registration = SyntheticHidPeer(reports) { code, parameters ->
            assertNull(CardOperationGate.tryAcquire())
            when (code) {
                4 -> success(info(mapOf("uv" to true)))
                1 -> {
                    assertArrayEquals(data.hash, parameters!![1].bytes())
                    assertEquals(rp, parameters[2].map()["id"].text())
                    val auth = sha256(rp.toByteArray()) + byteArrayOf(0x45, 0, 0, 0, 0) +
                        ByteArray(16) + byteArrayOf(0, credential.size.toByte()) + credential + CborCodec.encode(key)
                    success(cborMap(1 to "packed".cbor(), 2 to auth.cbor(),
                        3 to textMap("alg" to (-7).cbor(), "sig" to sign(auth + data.hash).cbor())))
                }
                else -> error("Unexpected command $code")
            }
        }
        val registry = RequestRegistry()
        val claim = claim(registry)
        val created = ProviderOperationRunner().executeAndConfirm(creation(), null, data, null, claim = claim) {
            assertFalse(claim.finish(cancelled = true))
            assertThrows(IllegalArgumentException::class.java) { registry.claim(claim.id) }
            registration.connect()
        } as OperationResult.Completed
        val output = (created.value as ProviderOutput.Created).credential
        assertTrue(FidoDiagnostics.verifyRegistration(output.result, rp, data.hash, true).signatureValid == true)
        assertArrayEquals(credential, output.credentialId)
        assertEquals(1, registration.initializationCount)
        assertTrue(registration.closed)

        val authentication = SyntheticHidPeer(reports) { code, parameters ->
            assertNull(CardOperationGate.tryAcquire())
            when (code) {
                4 -> success(info(mapOf("uv" to true)))
                2 -> {
                    assertArrayEquals(credential, parseDescriptor(parameters!![3].array().single()))
                    assertArrayEquals(data.hash, parameters[2].bytes())
                    success(assertion())
                }
                else -> error("Unexpected command $code")
            }
        }
        val result = ProviderOperationRunner().executeAndConfirm(null, request(), data, null) { authentication.connect() }
            as OperationResult.Completed
        val provided = (result.value as ProviderOutput.Assertions).values.single()
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public); update(provided.assertion.authData); update(data.hash); verify(provided.assertion.signature)
        })
        assertEquals(1, authentication.initializationCount)
        assertEquals(1, authentication.commands.count { it == 2 })
        assertTrue(authentication.closed)
        provided.close()
    }

    @Test fun framedPinTokensAssertionAndFragmentedBlobWriteHoldOneLeaseAndConnection() {
        val server = FidoClientTest.PinServer(pinVersion)
        val blobKey = ByteArray(32) { 11 }
        val plaintext = ByteArray(600).also { java.util.Random(23).nextBytes(it) }
        val inputs = GetExtensionInputs(largeBlobWrite = plaintext.copyOf())
        var stored = LargeBlobs.encode(emptyList())
        var asserted = false
        var writes = 0
        val peer = SyntheticHidPeer(reports) { code, parameters ->
            assertNull(CardOperationGate.tryAcquire())
            when (code) {
                4 -> success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true, "largeBlobs" to true),
                    protocols = listOf(pinVersion), extensions = listOf("largeBlobKey"),
                    extra = cborMap(5 to 256.cbor(), 11 to 4096.cbor())))
                6 -> server.respond(parameters!!, if (asserted) 16 else 2, if (asserted) null else rp)
                2 -> {
                    assertEquals(pinVersion.toLong(), parameters!![7].number())
                    assertArrayEquals(PinProtocol.authenticate(pinVersion, server.token, data.hash), parameters[6].bytes())
                    assertTrue(parameters[4].map()["largeBlobKey"].bool())
                    asserted = true
                    success(assertion(blobKey.copyOf()))
                }
                12 -> {
                    assertTrue(asserted)
                    val offset = parameters!![3].number().toInt()
                    if (parameters[1] != null) {
                        val count = parameters[1].number().toInt()
                        success(cborMap(1 to stored.copyOfRange(minOf(offset, stored.size),
                            minOf(offset + count, stored.size)).cbor()))
                    } else {
                        val fragment = parameters[2].bytes()
                        val authenticated = ByteArray(32) { -1 } + byteArrayOf(12, 0) +
                            ByteArray(4) { (offset ushr (it * 8)).toByte() } + sha256(fragment)
                        assertArrayEquals(PinProtocol.authenticate(pinVersion, server.token, authenticated), parameters[5].bytes())
                        assertEquals(pinVersion.toLong(), parameters[6].number())
                        if (offset == 0) stored = ByteArray(parameters[4].number().toInt())
                        fragment.copyInto(stored, offset)
                        writes++
                        success()
                    }
                }
                else -> error("Unexpected command $code")
            }
        }
        val pin = "1234".toCharArray()
        val result = ProviderOperationRunner().executeAndConfirm(null, request(inputs), data, pin) { peer.connect() }
            as OperationResult.Completed
        val provided = (result.value as ProviderOutput.Assertions).values.single()
        assertEquals(true, provided.extensions.largeBlobWritten)
        assertNull(provided.assertion.largeBlobKey)
        assertArrayEquals(plaintext, LargeBlobs.decrypt(blobKey, LargeBlobs.decode(stored).single()))
        assertTrue(writes > 1)
        assertEquals(2, server.tokenRequests)
        assertEquals(1, peer.initializationCount)
        assertEquals(1, peer.commands.count { it == 2 })
        assertTrue(peer.closed)
        assertArrayEquals(CharArray(4), pin)
        assertArrayEquals(ByteArray(plaintext.size), inputs.largeBlobWrite)
        requireNotNull(CardOperationGate.tryAcquire()).close()
        provided.close()
    }

    @Test fun incorrectPinIsKnownWithoutAssertionOrReplay() {
        val server = FidoClientTest.PinServer(pinVersion)
        val peer = SyntheticHidPeer(reports) { code, parameters -> when (code) {
            4 -> success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true), protocols = listOf(pinVersion)))
            6 -> if (parameters!![2].number() == 2L) server.respond(parameters, 2, rp) else byteArrayOf(0x31)
            else -> error("Incorrect PIN must not dispatch an assertion")
        } }
        val pin = "9999".toCharArray()
        val result = ProviderOperationRunner().executeAndConfirm(null, request(), data, pin) { peer.connect() } as OperationResult.Failed
        assertEquals(FailureCode.PIN_INVALID, result.code)
        assertEquals(Effect.APPLIED, result.effect)
        assertEquals(1, peer.initializationCount)
        assertEquals(listOf(4, 6, 6), peer.commands)
        assertTrue(peer.closed)
        assertArrayEquals(CharArray(4), pin)
    }

    @Test fun framedUncertainAssertionTerminalsNeverPublishOrReplay() {
        for (terminal in listOf("bridge", "cancelled", "malformed", "lost")) {
            val peer = SyntheticHidPeer(reports) { code, _ -> when (code) {
                4 -> success(info(mapOf("uv" to true)))
                2 -> when (terminal) {
                    "bridge" -> byteArrayOf(0x7f)
                    "cancelled" -> byteArrayOf(0x2d)
                    "malformed" -> byteArrayOf(0, 0xff.toByte())
                    else -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                }
                else -> error("Unexpected command $code")
            } }
            val runner = ProviderOperationRunner()
            var connections = 0
            val result = runner.executeAndConfirm(null, request(), data, null) { connections++; peer.connect() }
                as OperationResult.Failed
            assertEquals(Effect.UNKNOWN, result.effect)
            assertEquals(FailureCode.CANCELLED, (runner.executeAndConfirm(null, request(), data, null) {
                connections++; error("Uncertain assertion replayed")
            } as OperationResult.Failed).code)
            assertEquals(1, connections)
            assertEquals(1, peer.commands.count { it == 2 })
            assertTrue(peer.closed)
        }
    }

    @Test fun invalidRunnerEnvelopeNeverReservesClaimOrInitializesTransport() {
        for (kind in listOf("missing", "ambiguous", "hash", "json")) {
            val registry = RequestRegistry()
            val claim = claim(registry)
            val result = ProviderOperationRunner().executeAndConfirm(
                if (kind == "ambiguous") creation() else null,
                if (kind == "missing") null else request(),
                when (kind) { "hash" -> data.copy(hash = ByteArray(31)); "json" -> data.copy(json = byteArrayOf(9)); else -> data },
                null, claim = claim) { error("Invalid input initialized transport") }
            assertEquals(FailureCode.INVALID_INPUT, (result as OperationResult.Failed).code)
            assertEquals(Dispatch.NOT_DISPATCHED, result.dispatch)
            assertTrue(claim.reserveTransport())
        }
    }

    @Test fun typedCancellationRetainsLeaseAndInputsUntilWorkerAndCloseRetire() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val runner = ProviderOperationRunner()
        val pin = "1234".toCharArray()
        val inputs = GetExtensionInputs(largeBlobWrite = byteArrayOf(9))
        val request = request(inputs)
        val result = AtomicReference<OperationResult<ProviderOutput>>()
        var cancelled = false
        val channel = object : CardChannel {
            override val wire = CardWire.CTAP
            override val supportsCtap2 = true
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 7609
            override fun transmit(command: ByteArray): ByteArray = error("APDU on typed channel")
            override fun exchangeCtap2(command: ByteArray): ByteArray {
                assertEquals(4, command[0].toInt())
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                assertArrayEquals("1234".toCharArray(), pin)
                assertArrayEquals(byteArrayOf(9), inputs.largeBlobWrite)
                throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, cancelled = true)
            }
            override fun cancel() { cancelled = true }
            override fun close() { closing.countDown(); check(releaseClose.await(5, TimeUnit.SECONDS)) }
        }
        val worker = Thread { result.set(runner.executeAndConfirm(null, request, data, pin) { channel }) }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val duplicate = runner.executeAndConfirm(null, request, data, null) { error("Duplicate connect") }
            assertEquals(FailureCode.CANCELLED, (duplicate as OperationResult.Failed).code)
            assertArrayEquals("1234".toCharArray(), pin)
            val duplicatePin = "9876".toCharArray()
            val duplicateInputs = GetExtensionInputs(largeBlobWrite = byteArrayOf(7))
            runner.executeAndConfirm(null, request(duplicateInputs), data, duplicatePin) { error("Duplicate connect") }
            assertArrayEquals(CharArray(4), duplicatePin)
            assertArrayEquals(byteArrayOf(0), duplicateInputs.largeBlobWrite)
            assertArrayEquals(byteArrayOf(9), inputs.largeBlobWrite)
            runner.cancel()
            assertTrue(cancelled)
            assertNull(CardOperationGate.tryAcquire())
            assertArrayEquals(byteArrayOf(9), inputs.largeBlobWrite)
            resume.countDown()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertNull(CardOperationGate.tryAcquire())
        } finally { resume.countDown(); releaseClose.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertEquals(FailureCode.CANCELLED, (result.get() as OperationResult.Failed).code)
        assertArrayEquals(CharArray(4), pin)
        assertArrayEquals(byteArrayOf(0), inputs.largeBlobWrite)
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    @Test fun cancellationWhileClosingSuppressesKnownSuccessfulAssertion() {
        val runner = ProviderOperationRunner()
        val peer = SyntheticHidPeer(reports) { code, _ -> when (code) {
            4 -> success(info(mapOf("uv" to true)))
            2 -> success(assertion())
            else -> error("Unexpected command $code")
        } }
        val result = runner.executeAndConfirm(null, request(), data, null) {
            val connected = peer.connect()
            object : CardChannel by connected {
                override fun close() { runner.cancel(); connected.close() }
            }
        } as OperationResult.Failed
        assertEquals(FailureCode.CANCELLED, result.code)
        assertEquals(Effect.APPLIED, result.effect)
        assertTrue(peer.closed)
        assertEquals(1, peer.commands.count { it == 2 })
    }

    @Test fun hidPresenceFailureNeverRequiresCcidRemoval() {
        val readerId = "synthetic-hid-interface"
        val peer = SyntheticHidPeer(reports) { code, _ -> when (code) {
            4 -> success(info(mapOf("uv" to true)))
            2 -> byteArrayOf(0x2f)
            else -> error("Unexpected command $code")
        } }
        val result = ProviderOperationRunner().executeAndConfirm(null, request(), data, null, readerId) { peer.connect() }
            as OperationResult.Failed
        assertEquals(FailureCode.PHYSICAL_ACTION_REQUIRED, result.code)
        assertFalse(CardOperationGate.isRemovalRequired(readerId))
        assertTrue(peer.closed)
    }
}
