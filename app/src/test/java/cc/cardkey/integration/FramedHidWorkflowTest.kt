package cc.cardkey.integration

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.transport.CtaphidChannel
import cc.cardkey.transport.CtaphidIo
import cc.cardkey.transport.FidoHidReports
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
    fun connect() = CtaphidChannel(this, reports = reports).also { it.initialize() }
}

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class FramedHidWorkflowTest(input: Int, output: Int) {
    private val reports = FidoHidReports(input, output)
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "reports={0}")
        fun reports() = listOf(arrayOf(64, 64), arrayOf(16, 32))
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
}
