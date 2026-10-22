package cc.cardkey.integration

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.transport.CtaphidChannel
import cc.cardkey.transport.CtaphidIo
import cc.cardkey.transport.FidoHidReports
import cc.cardkey.transport.HidInterruptIo
import cc.cardkey.transport.SyntheticNativeHidConnection
import cc.cardkey.ui.*
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.*
import org.junit.Test

internal class SyntheticHidPeer(private val reports: FidoHidReports = FidoHidReports(), private val respond: (Int, Cbor.Map?) -> ByteArray) : CtaphidIo {
    private val responseReports = ArrayDeque<ByteArray>()
    private val pending = ByteArrayOutputStream()
    private var expected = 0
    private var command = 0
    private var sequence = 0
    var initializationCount = 0
        private set
    val commands = mutableListOf<Int>()
    var closed = false
        private set
    override fun write(report: ByteArray, timeoutMillis: Int): Int {
        check(!closed)
        assertEquals(reports.outputBytes, report.size)
        val marker = report[4].toInt() and 255
        val offset: Int
        if (marker and 128 != 0) {
            assertEquals(0, pending.size())
            command = marker
            expected = ((report[5].toInt() and 255) shl 8) or (report[6].toInt() and 255)
            sequence = 0
            offset = 7
        } else {
            assertEquals(sequence++, marker)
            offset = 5
        }
        val cid = report.copyOfRange(0, 4)
        assertArrayEquals(if (command == 0x86) ByteArray(4) { -1 } else byteArrayOf(0, 0, 0, 1), cid)
        pending.write(report, offset, minOf(expected - pending.size(), report.size - offset))
        if (pending.size() == expected) {
            val payload = pending.toByteArray()
            pending.reset()
            if (command == 0x86) {
                initializationCount++
                assertEquals(8, payload.size)
                enqueue(cid, command, payload + byteArrayOf(0, 0, 0, 1, 2, 1, 0, 0, 4))
            } else {
                assertEquals(0x90, command)
                val code = payload[0].toInt() and 255
                commands += code
                val parameters = payload.takeIf { it.size > 1 }?.let {
                    CborCodec.decode(it.copyOfRange(1, it.size)).map()
                }
                enqueue(cid, 0xbb, byteArrayOf(1))
                enqueue(cid, command, respond(code, parameters))
            }
        }
        return report.size
    }
    private fun enqueue(cid: ByteArray, command: Int, payload: ByteArray) {
        var offset = 0
        var sequence = -1
        do {
            val report = ByteArray(reports.inputBytes)
            cid.copyInto(report)
            val start = if (sequence == -1) {
                report[4] = command.toByte()
                report[5] = (payload.size ushr 8).toByte()
                report[6] = payload.size.toByte()
                7
            } else {
                report[4] = sequence.toByte()
                5
            }
            val count = minOf(payload.size - offset, reports.inputBytes - start)
            payload.copyInto(report, start, offset, offset + count)
            responseReports += report
            offset += count
            sequence++
        } while (offset < payload.size)
    }
    override fun read(report: ByteArray, timeoutMillis: Int): Int {
        check(!closed)
        responseReports.removeFirst().copyInto(report)
        return reports.inputBytes
    }
    override fun interrupt() = Unit
    override fun close() { closed = true }
    val native = SyntheticNativeHidConnection(this, reports)
    fun connect() = CtaphidChannel(HidInterruptIo(native, reports), reports = reports).also { it.initialize() }
}

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class FramedHidWorkflowTest(input: Int, output: Int, inputId: Int, outputId: Int) {
    private val reports = FidoHidReports(input, output, inputId, outputId)
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "reports={0}")
        fun reports() = listOf(arrayOf(64, 64, 0, 0), arrayOf(16, 32, 0, 0), arrayOf(16, 32, 3, 9))
    }
    private fun peer(respond: (Int, Cbor.Map?) -> ByteArray) = SyntheticHidPeer(reports, respond)

    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private val publicKey = pair.public as ECPublicKey
    private val key = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(publicKey.w.affineX).cbor(), -3 to fixed32(publicKey.w.affineY).cbor())
    private val credential = ByteArray(120) { it.toByte() }
    private val blobKey = ByteArray(32) { 17 }
    private val manager = ManagerOperations()
    private fun sign(bytes: ByteArray, valid: Boolean = true) = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private); update(bytes); if (!valid) update(1); sign()
    }
    private fun registration(valid: Boolean = true) = peer { code, parameters ->
        when (code) {
            4 -> success(info(mapOf("rk" to true, "uv" to true), extensions = listOf("largeBlobKey")))
            1 -> {
                val auth = sha256("cardkey.example".toByteArray()) + byteArrayOf(0x41, 0, 0, 0, 0) +
                    ByteArray(16) + byteArrayOf(0, credential.size.toByte()) + credential + CborCodec.encode(key)
                success(cborMap(1 to "packed".cbor(), 2 to auth.cbor(),
                    3 to textMap("alg" to (-7).cbor(), "sig" to sign(auth + parameters!![1].bytes(), valid).cbor()),
                    5 to blobKey.cbor()))
            }
            else -> error("Unexpected CTAP command $code")
        }
    }
    private fun run(peer: SyntheticHidPeer, input: OperationInput): OperationResult<ManagerReport> =
        input.use { CardSession(peer.connect()).run { session -> manager.execute(session, input) } }
    private fun register(valid: Boolean = true): OperationResult<ManagerReport> = run(registration(valid),
        OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true, makeExtensions = MakeExtensions(largeBlobKey = true)))

    @Test fun managerVerifiesMultiReportRegistrationAndAssertion() {
        val first = registration()
        assertTrue(run(first, OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true,
            makeExtensions = MakeExtensions(largeBlobKey = true))) is OperationResult.Completed)
        assertEquals(1, first.initializationCount)
        assertEquals(listOf(4, 1), first.commands)
        assertTrue(first.closed)
        val second = peer { code, parameters -> when (code) {
            4 -> success(info())
            2 -> {
                assertArrayEquals(credential, parameters!![3].array().single().map()["id"].bytes())
                val auth = sha256("cardkey.example".toByteArray()) + byteArrayOf(1, 0, 0, 0, 1)
                success(cborMap(1 to descriptor(credential), 2 to auth.cbor(),
                    3 to sign(auth + parameters[2].bytes()).cbor()))
            }
            else -> error("Unexpected CTAP command $code")
        } }
        assertTrue(run(second, OperationInput(ManagerOperation.FIDO_ASSERT)) is OperationResult.Completed)
        assertEquals(listOf(4, 2), second.commands)
        assertEquals(1, second.initializationCount)
        assertTrue(second.closed)
    }

    @Test fun invalidSignedRegistrationCannotCreateManagerCredential() {
        val result = register(valid = false) as OperationResult.Failed
        assertEquals(FailureCode.MALFORMED_RESPONSE, result.code)
        assertEquals(Effect.APPLIED, result.effect)
        assertTrue(result.writeAccepted)
        val peer = peer { _, _ -> error("Unverified credential must not be used") }
        assertEquals(FailureCode.INVALID_INPUT,
            (run(peer, OperationInput(ManagerOperation.FIDO_ASSERT)) as OperationResult.Failed).code)
        assertTrue(peer.commands.isEmpty())
    }

    @Test fun managerPinSetAndChangeAuthenticateEncryptedMutationsOnce() {
        for (version in 1..2) for (changing in listOf(false, true)) {
            val server = FidoClientTest.PinServer(version)
            var mutations = 0
            val peer = peer { code, parameters -> when (code) {
                4 -> success(info(mapOf("clientPin" to changing), protocols = listOf(version)))
                6 -> if (parameters!![2].number() == 2L) server.respond(parameters, 0, null) else {
                    assertEquals(if (changing) 4L else 3L, parameters[2].number())
                    val shared = server.keyFor(parameters[3].map())
                    try {
                        val encryptedNew = parameters[5].bytes()
                        val encryptedOld = parameters[6]?.bytes()
                        assertArrayEquals("5678".toByteArray().copyOf(64), PinProtocol.decrypt(version, shared, encryptedNew))
                        if (changing) assertArrayEquals(sha256("1234".toByteArray()).copyOf(16),
                            PinProtocol.decrypt(version, shared, requireNotNull(encryptedOld)))
                        else assertNull(encryptedOld)
                        assertArrayEquals(PinProtocol.authenticate(version, shared,
                            encryptedNew + (encryptedOld ?: byteArrayOf())), parameters[4].bytes())
                        mutations++
                        success()
                    } finally { shared.fill(0) }
                }
                else -> error("Unexpected PIN workflow command $code")
            } }
            val old = "1234".toCharArray()
            val next = "5678".toCharArray()
            val result = run(peer, OperationInput(if (changing) ManagerOperation.FIDO_CHANGE_PIN else ManagerOperation.FIDO_SET_PIN,
                pin = old, newPin = next)) as OperationResult.Completed
            assertEquals(Effect.APPLIED, result.effect)
            assertEquals(1, mutations)
            assertEquals(1, peer.initializationCount)
            assertTrue(peer.closed)
            assertArrayEquals(CharArray(4), old)
            assertArrayEquals(CharArray(4), next)
        }
    }

    @Test fun freshSignedBlobProofAndReadShareOneInitializedConnection() {
        assertTrue(register() is OperationResult.Completed)
        val plaintext = ByteArray(250) { (it * 3).toByte() }
        val stored = LargeBlobs.encode(listOf(LargeBlobs.encrypt(blobKey, plaintext)))
        val peer = peer { code, parameters -> when (code) {
            4 -> success(info(mapOf("largeBlobs" to true, "uv" to true),
                extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
            2 -> {
                assertFalse(parameters!![5].map()["up"].bool())
                assertTrue(parameters[5].map()["uv"].bool())
                val auth = sha256("cardkey.example".toByteArray()) + byteArrayOf(4, 0, 0, 0, 1)
                success(cborMap(1 to descriptor(credential), 2 to auth.cbor(),
                    3 to sign(auth + parameters[2].bytes()).cbor(), 7 to blobKey.cbor()))
            }
            12 -> {
                val offset = parameters!![3].number().toInt()
                val count = parameters[1].number().toInt()
                success(cborMap(1 to stored.copyOfRange(minOf(offset, stored.size),
                    minOf(offset + count, stored.size)).cbor()))
            }
            else -> error("Unexpected CTAP command $code")
        } }
        val result = run(peer, OperationInput(ManagerOperation.FIDO_BLOB_READ)) as OperationResult.Completed
        assertArrayEquals(plaintext, result.value.export)
        assertTrue(result.value.protectedExport)
        assertEquals(1, peer.initializationCount)
        assertTrue(peer.commands.indexOf(2) < peer.commands.indexOf(12))
        assertTrue(peer.closed)
        result.value.export?.fill(0)
    }

    @Test fun managerEnumeratesUpdatesAndDeletesAuthenticatedCredentialsWithReadback() {
        for (operation in listOf(ManagerOperation.FIDO_UPDATE, ManagerOperation.FIDO_DELETE)) {
            val server = FidoClientTest.PinServer(2)
            val rpHash = sha256("cardkey.example".toByteArray())
            var user = FidoUser(byteArrayOf(8, 9), "old", "display")
            var deleted = false
            val subcommands = mutableListOf<Int>()
            var mutations = 0
            val respond: (Int, Cbor.Map?) -> ByteArray = { code, parameters -> when (code) {
                4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true)))
                6 -> server.respond(parameters!!, 4, null)
                10 -> {
                    val params = parameters!!
                    val sub = params[1].number().toInt()
                    subcommands += sub
                    val message = byteArrayOf(sub.toByte()) + (params[2]?.map()?.let(CborCodec::encode) ?: byteArrayOf())
                    assertEquals(2L, params[3].number())
                    assertArrayEquals(PinProtocol.authenticate(2, server.token, message), params[4].bytes())
                    when (sub) {
                        2 -> success(cborMap(3 to textMap("id" to "cardkey.example".cbor()),
                            4 to rpHash.cbor(), 5 to 1.cbor()))
                        4 -> {
                            assertArrayEquals(rpHash, params[2].map()[1].bytes())
                            if (deleted) byteArrayOf(0x2e) else success(cborMap(6 to user.encode(),
                                7 to descriptor(credential), 8 to key, 9 to 1.cbor()))
                        }
                        6, 7 -> {
                            assertArrayEquals(credential, parseDescriptor(params[2].map()[2]))
                            mutations++
                            if (sub == 6) deleted = true else {
                                val next = params[2].map()[3].map()
                                assertArrayEquals(user.id, next["id"].bytes())
                                assertEquals("updated", next["name"].text())
                                assertEquals("display", next["displayName"].text())
                                user = FidoUser(user.id, next["name"].text(), next["displayName"].text())
                            }
                            success()
                        }
                        else -> error("Unexpected credential command $sub")
                    }
                }
                else -> error("Unexpected CTAP command $code")
            } }
            val inventory = FidoInventory()
            val peers = mutableListOf<SyntheticHidPeer>()
            fun execute(input: OperationInput): ManagerReport = input.use {
                input.inventoryRequest = inventory.begin(input)
                val native = peer(respond).also { peers += it }
                val result = run(native, input) as OperationResult.Completed
                inventory.settle(input.inventoryRequest!!, result.value)
                result.value
            }
            execute(OperationInput(ManagerOperation.FIDO_RPS, pin = "1234".toCharArray()))
            inventory.selectRp(inventory.state.value.rps.single())
            val enumerated = execute(OperationInput(ManagerOperation.FIDO_CREDENTIALS, pin = "1234".toCharArray()))
            assertArrayEquals(credential, enumerated.credentials!!.single().id)
            inventory.selectCredential(inventory.state.value.credentials.single())
            execute(OperationInput(operation, pin = "1234".toCharArray(), secondary = "updated",
                inventoryGeneration = inventory.state.value.generation,
                inventorySelection = inventory.state.value.credential))
            assertEquals(listOf(2, 4, 2, 4, if (operation == ManagerOperation.FIDO_DELETE) 6 else 7, 4), subcommands)
            assertEquals(1, mutations)
            assertEquals(3, server.tokenRequests)
            assertEquals(operation == ManagerOperation.FIDO_DELETE, deleted)
            if (!deleted) assertEquals("updated", user.name)
            assertTrue(peers.all { it.initializationCount == 1 && it.closed })
            assertTrue(peers.all { native -> native.native.buffers.all { buffer ->
                (0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() }
            } })
        }
    }

    @Test fun authenticatedBlobWriteReadAndDeleteVerifySignedProofAndStoredReadback() {
        assertTrue(register() is OperationResult.Completed)
        val server = FidoClientTest.PinServer(2)
        val otherKey = ByteArray(32) { 44 }
        val other = LargeBlobs.encrypt(otherKey, byteArrayOf(7, 8))
        var stored = LargeBlobs.encode(listOf(other))
        var staged = byteArrayOf()
        var total = 0
        var writes = 0
        var readsAfterWrite = 0
        var counter = 0
        var permission = 2
        val plaintext = ByteArray(900).also { java.util.Random(42).nextBytes(it) }
        val respond: (Int, Cbor.Map?) -> ByteArray = { code, parameters -> when (code) {
            4 -> success(info(mapOf("largeBlobs" to true, "uv" to true, "clientPin" to true,
                "pinUvAuthToken" to true), extensions = listOf("largeBlobKey"),
                extra = cborMap(5 to 512.cbor(), 11 to 4096.cbor())))
            6 -> server.respond(parameters!!, permission, if (permission == 2) "cardkey.example" else null)
            2 -> {
                val params = parameters!!
                assertArrayEquals(credential, parseDescriptor(params[3].array().single()))
                assertArrayEquals(PinProtocol.authenticate(2, server.token, params[2].bytes()), params[6].bytes())
                assertFalse(params[5].map()["up"].bool())
                assertTrue(params[4].map()["largeBlobKey"].bool())
                val auth = sha256("cardkey.example".toByteArray()) + byteArrayOf(4, 0, 0, 0, (++counter).toByte())
                permission = 16
                success(cborMap(1 to descriptor(credential), 2 to auth.cbor(),
                    3 to sign(auth + params[2].bytes()).cbor(), 7 to blobKey.cbor()))
            }
            12 -> {
                val params = parameters!!
                val offset = params[3].count()
                if (params[1] != null) {
                    if (writes > 0) readsAfterWrite++
                    success(cborMap(1 to stored.copyOfRange(minOf(offset, stored.size),
                        minOf(offset + params[1].count(), stored.size)).cbor()))
                } else {
                    val fragment = params[2].bytes()
                    assertTrue(fragment.size <= 448)
                    if (offset == 0) { total = params[4].count(); staged = byteArrayOf() }
                    else assertNull(params[4])
                    assertEquals(staged.size, offset)
                    val message = ByteArray(32) { 0xff.toByte() } + byteArrayOf(12, 0) +
                        ByteArray(4) { (offset ushr (it * 8)).toByte() } + sha256(fragment)
                    assertEquals(2L, params[6].number())
                    assertArrayEquals(PinProtocol.authenticate(2, server.token, message), params[5].bytes())
                    staged += fragment
                    writes++
                    if (staged.size == total) stored = staged
                    success()
                }
            }
            else -> error("Unexpected blob command $code")
        } }
        val peers = mutableListOf<SyntheticHidPeer>()
        fun execute(input: OperationInput): OperationResult.Completed<ManagerReport> {
            permission = 2
            val native = peer(respond).also { peers += it }
            return run(native, input) as OperationResult.Completed
        }
        val bytes = plaintext.copyOf()
        val written = execute(OperationInput(ManagerOperation.FIDO_BLOB_WRITE, pin = "1234".toCharArray(), bytes = bytes))
        assertEquals(Effect.APPLIED, written.effect)
        assertTrue(writes > 1)
        assertTrue(readsAfterWrite > 0)
        assertArrayEquals(ByteArray(bytes.size), bytes)
        val entries = LargeBlobs.decode(stored)
        assertEquals(other, entries.first())
        assertArrayEquals(plaintext, LargeBlobs.decrypt(blobKey, entries.last()))
        val read = execute(OperationInput(ManagerOperation.FIDO_BLOB_READ, pin = "1234".toCharArray()))
        assertArrayEquals(plaintext, read.value.export)
        assertTrue(read.value.protectedExport)
        read.value.export?.fill(0)
        val beforeDelete = writes
        val beforeReadback = readsAfterWrite
        execute(OperationInput(ManagerOperation.FIDO_BLOB_WRITE, pin = "1234".toCharArray(), deleteBlob = true))
        assertTrue(writes > beforeDelete)
        assertTrue(readsAfterWrite > beforeReadback)
        assertEquals(listOf(other), LargeBlobs.decode(stored))
        val absent = execute(OperationInput(ManagerOperation.FIDO_BLOB_READ, pin = "1234".toCharArray()))
        assertNull(absent.value.export)
        assertEquals(6, server.tokenRequests)
        assertEquals(4, counter)
        assertTrue(peers.all { it.initializationCount == 1 && it.closed })
    }

    @Test fun lostAuthenticatedBlobMutationResponseRemainsUnknownWithoutReplay() {
        assertTrue(register() is OperationResult.Completed)
        val server = FidoClientTest.PinServer(2)
        var permission = 2
        var writes = 0
        val initial = LargeBlobs.encode(emptyList())
        val native = peer { code, parameters -> when (code) {
            4 -> success(info(mapOf("largeBlobs" to true, "uv" to true, "clientPin" to true,
                "pinUvAuthToken" to true), extensions = listOf("largeBlobKey"),
                extra = cborMap(5 to 512.cbor(), 11 to 4096.cbor())))
            6 -> server.respond(parameters!!, permission, if (permission == 2) "cardkey.example" else null)
            2 -> {
                val params = parameters!!
                assertArrayEquals(PinProtocol.authenticate(2, server.token, params[2].bytes()), params[6].bytes())
                val auth = sha256("cardkey.example".toByteArray()) + byteArrayOf(4, 0, 0, 0, 1)
                permission = 16
                success(cborMap(1 to descriptor(credential), 2 to auth.cbor(),
                    3 to sign(auth + params[2].bytes()).cbor(), 7 to blobKey.cbor()))
            }
            12 -> if (parameters!![1] != null) success(cborMap(1 to initial.cbor())) else {
                val fragment = parameters[2].bytes()
                assertEquals(0L, parameters[3].number())
                val message = ByteArray(32) { 0xff.toByte() } + byteArrayOf(12, 0) + ByteArray(4) + sha256(fragment)
                assertArrayEquals(PinProtocol.authenticate(2, server.token, message), parameters[5].bytes())
                writes++
                throw java.io.IOException("Synthetic USB disconnect after mutation dispatch")
            }
            else -> error("Unexpected blob command $code")
        } }
        val input = OperationInput(ManagerOperation.FIDO_BLOB_WRITE, pin = "1234".toCharArray(), bytes = ByteArray(250) { it.toByte() })
        val result = run(native, input) as OperationResult.Failed
        assertEquals(FailureCode.TRANSPORT_FAILURE, result.code)
        assertEquals(Effect.UNKNOWN, result.effect)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, result.dispatch)
        assertEquals(1, writes)
        assertEquals(2, server.tokenRequests)
        assertEquals(1, native.initializationCount)
        assertTrue(native.closed)
        assertArrayEquals(CharArray(4), input.pin)
        assertArrayEquals(ByteArray(250), input.bytes)
        assertTrue(native.native.buffers.all { buffer -> (0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() } })
    }

}
