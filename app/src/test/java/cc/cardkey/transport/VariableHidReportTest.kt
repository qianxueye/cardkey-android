package cc.cardkey.transport

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test

class VariableHidReportTest {
    private val nonce = ByteArray(8) { it.toByte() }
    private val init = nonce + byteArrayOf(0, 0, 0, 1, 2, 0, 0, 0, 4)

    @Test fun acceptsIndependentRawReportCountsAndRejectsUnsupportedState() {
        for (size in listOf(7, 8, 16, 32, 64, 1024)) {
            assertEquals(FidoHidReports(size, 32), FidoHidDescriptor.validate(descriptor(size, 32)))
        }
        for (invalid in listOf(descriptor(6, 64), descriptor(1025, 64),
            byteArrayOf(0x85.toByte(), 1) + descriptor(64, 64),
            byteArrayOf(0xa4.toByte()) + descriptor(64, 64),
            byteArrayOf(0xb4.toByte()) + descriptor(64, 64),
            descriptor(64, 64).copyOf(23),
            descriptor(64, 64).also { it[8] = 0x21 },
            descriptor(64, 64).also { it[10] = 7 },
            descriptor(64, 64).also { it[13] = 0xff.toByte() },
            descriptor(64, 64) + descriptor(64, 64))) {
            try { FidoHidDescriptor.validate(invalid); fail() } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun framingBoundariesUseDeclaredDirectionalLengthAndZeroPadding() {
        for (size in listOf(7, 8, 16, 32, 64, 1024)) {
            val reports = FidoHidDescriptor.validate(descriptor(size, size))
            val capacity = minOf(7609, reports.outputCapacity)
            for (length in listOf(1, maxOf(1, size - 7), size - 6, (size - 7) + (size - 5),
                (size - 7) + (size - 5) + 1, capacity).distinct()) {
                val payload = ByteArray(length) { (it + 7).toByte() }
                val io = Peer(size, frames(size, 6, init, -1) + frames(size, 0x10, payload))
                val channel = ready(io, reports)
                val before = io.writes.size
                assertArrayEquals(payload, channel.exchangeCtap2(payload))
                val sent = io.writes.drop(before)
                assertTrue(sent.all { it.size == size })
                var offset = 0
                sent.forEachIndexed { index, report ->
                    val header = if (index == 0) 7 else 5
                    if (index > 0) assertEquals(index - 1, report[4].toInt() and 255)
                    val count = minOf(length - offset, size - header)
                    assertArrayEquals(payload.copyOfRange(offset, offset + count), report.copyOfRange(header, header + count))
                    assertTrue(report.drop(header + count).all { it == 0.toByte() })
                    offset += count
                }
                assertEquals(length, offset)
                if (size <= 64 && length == capacity) assertEquals(127, sent.last()[4].toInt() and 255)
                val writes = io.writes.size
                assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.exchangeCtap2(ByteArray(capacity + 1)) }.dispatch)
                assertEquals(writes, io.writes.size)
                channel.close()
            }
        }
        assertEquals(listOf(256, 385, 1417, 3481, 7609, 65535),
            listOf(7, 8, 16, 32, 64, 1024).map { FidoHidReports.capacity(it) })
    }

    @Test fun fragmentedInitAcceptsExtensionsAndRejectsShortOrMismatchedNonce() {
        for (size in listOf(7, 8, 16)) {
            ready(Peer(size, frames(size, 6, init + ByteArray(9), -1)), FidoHidReports(size, size)).close()
            for (reply in listOf(init.copyOf(16), init.copyOf().also { it[0] = 99 })) {
                val io = Peer(size, frames(size, 6, reply, -1))
                assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { ready(io, FidoHidReports(size, size)) }.dispatch)
                assertTrue(io.closed)
            }
        }
    }

    @Test fun sevenByteControlMessagesAreAssembledAndBusyEffectsPreserved() {
        for (processing in listOf(false, true)) {
            val incoming = frames(7, 6, init, -1) +
                (if (processing) frames(7, 0x3b, byteArrayOf(2)) else emptyList()) +
                frames(7, 0x3f, byteArrayOf(6))
            val io = Peer(7, incoming)
            val result = CardSession(ready(io, FidoHidReports(7, 7))).run {
                it.exchangeCtap2(byteArrayOf(1), effectful = true)
            } as OperationResult.Failed
            assertEquals(if (processing) Effect.UNKNOWN else Effect.NONE, result.effect)
            assertEquals(if (processing) FailureCode.TRANSPORT_FAILURE else FailureCode.BUSY, result.code)
        }
    }

    @Test fun asymmetricU2fRequestAndResponseUseSeparateCapacities() {
        val request = ByteArray(329) { it.toByte() }
        val response = ByteArray(500).also { it[it.lastIndex - 1] = 0x90.toByte() }
        val io = Peer(8, frames(64, 6, init, -1) + frames(64, 3, response))
        assertArrayEquals(response, ready(io, FidoHidReports(64, 8)).exchangeCtap1(request))
        val reversed = Peer(64, frames(8, 6, init, -1) + frames(8, 3, response))
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure {
            ready(reversed, FidoHidReports(8, 64)).exchangeCtap1(request)
        }.dispatch)
    }

    @Test fun inputCapacityIsCheckedBeforeContinuationReadsAndCtap2DefaultBudgetStays1024() {
        for (size in listOf(8, 16)) {
            val io = Peer(size, frames(size, 6, init, -1) + frames(size, 0x10, byteArrayOf(0)))
            val channel = ready(io, FidoHidReports(size, size))
            if (size == 8) {
                val writes = io.writes.size
                assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.exchangeCtap2(ByteArray(1024)) }.dispatch)
                assertEquals(writes, io.writes.size)
                assertArrayEquals(byteArrayOf(0), channel.exchangeCtap2(byteArrayOf(4)))
            } else assertArrayEquals(byteArrayOf(0), channel.exchangeCtap2(ByteArray(1024)))
        }
        val bad = frames(7, 0x10, ByteArray(257))
        val io = Peer(7, frames(7, 6, init, -1) + bad)
        val channel = ready(io, FidoHidReports(7, 7))
        val before = io.reads
        failure { channel.exchangeCtap2(byteArrayOf(4)) }
        assertEquals(before + 1, io.reads)
    }

    @Test fun foreignCidAndInitNonceAreIgnoredUnderOriginalDeadline() {
        val foreign = init.copyOf().also { it[0] = 99 }
        for (size in listOf(7, 8, 16, 64)) {
            val io = Peer(size, frames(size, 6, foreign, -1) + frames(size, 6, init, -1) +
                frames(size, 0x10, byteArrayOf(8), 2) + frames(size, 0x10, byteArrayOf(0)))
            assertArrayEquals(byteArrayOf(0), ready(io, FidoHidReports(size, size)).exchangeCtap2(byteArrayOf(4)))
        }
        var time = 0L
        val io = Peer(8, frames(8, 6, foreign, -1))
        io.afterRead = { time += 10_000_000L }
        val channel = CtaphidChannel(io, 60, { time }, { nonce.copyOf() }, FidoHidReports(8, 8))
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.initialize() }.dispatch)
        assertEquals(6, io.reads)
        assertTrue(io.closed)
    }

    @Test fun framedU2fWorkflowVerifiesRegistrationAndAuthenticationSignatures() {
        val pair = java.security.KeyPairGenerator.getInstance("EC").run {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val publicKey = pair.public as java.security.interfaces.ECPublicKey
        val challenge = ByteArray(32) { 2 }
        val application = ByteArray(32) { 3 }
        val handle = ByteArray(255) { it.toByte() }
        val point = byteArrayOf(4) + cc.cardkey.protocol.fido.fixed32(publicKey.w.affineX) +
            cc.cardkey.protocol.fido.fixed32(publicKey.w.affineY)
        val name = org.bouncycastle.asn1.x500.X500Name("CN=Synthetic HID test")
        val certificate = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(name,
            java.math.BigInteger.ONE, java.util.Date(0), java.util.Date(4102444800000L), name, pair.public)
            .build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)).encoded
        fun sign(bytes: ByteArray) = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(bytes); sign()
        }
        val registration = byteArrayOf(5) + point + byteArrayOf(255.toByte()) + handle + certificate +
            sign(byteArrayOf(0) + application + challenge + handle + point) + byteArrayOf(0x90.toByte(), 0)
        val presenceCounter = byteArrayOf(1, 0, 0, 0, 9)
        val assertion = presenceCounter + sign(application + presenceCounter + challenge) + byteArrayOf(0x90.toByte(), 0)
        val io = Peer(8, frames(16, 6, init, -1) + frames(16, 3, registration) + frames(16, 3, assertion))
        val result = CardSession(ready(io, FidoHidReports(16, 8))).run {
            val client = cc.cardkey.protocol.fido.U2fClient(it).apply { select() }
            val credential = client.register(challenge, application)
            assertTrue(credential.signatureValid)
            assertArrayEquals(handle, credential.keyHandle)
            val verified = client.authenticate(challenge, application, handle, credential.publicKey, 8)
            assertTrue(verified.signatureValid)
            assertEquals(true, verified.counterAdvanced)
            verified.counter
        }
        assertEquals(OperationResult.Completed(9L, Effect.APPLIED), result)
        val authenticate = io.writes.dropWhile { it[4] != 0x83.toByte() }.drop(1)
            .dropWhile { it[4] != 0x83.toByte() }
        assertEquals(329, ((authenticate.first()[5].toInt() and 255) shl 8) or
            (authenticate.first()[6].toInt() and 255))
        val command = authenticate.first().drop(7) + authenticate.drop(1).flatMap { it.drop(5) }
        assertEquals(2, command[1].toInt())
        assertEquals(255, command[71].toInt() and 255)
        assertArrayEquals(handle, command.drop(72).take(255).toByteArray())
        assertTrue(io.closed)
    }

    @Test fun cancelUsesOutputLengthAndEmptyInitialPayload() {
        for (size in listOf(7, 8, 16, 32, 64, 1024)) {
            val io = Peer(size, frames(16, 6, init, -1))
            val channel = ready(io, FidoHidReports(16, size))
            io.afterWrite = { channel.cancel() }
            val failed = failure { channel.exchangeCtap2(byteArrayOf(4)) }
            assertTrue(failed.cancelled)
            val cancel = io.writes.last()
            assertEquals(size, cancel.size)
            assertEquals(0x91, cancel[4].toInt() and 255)
            assertTrue(cancel.drop(5).all { it == 0.toByte() })
        }
    }

    private fun ready(io: Peer, reports: FidoHidReports) = CtaphidChannel(io, nonce = { nonce.copyOf() }, reports = reports).also { it.initialize() }
    private fun failure(block: () -> Unit): TransportException {
        try { block(); fail() } catch (failure: TransportException) { return failure }
        error("unreachable")
    }
    private class Peer(private val outputSize: Int, reports: List<ByteArray>) : CtaphidIo {
        private val incoming = ArrayDeque(reports)
        val writes = mutableListOf<ByteArray>()
        var reads = 0
        var afterRead: () -> Unit = {}
        var afterWrite: () -> Unit = {}
        var closed = false
        override fun write(report: ByteArray, timeoutMillis: Int): Int {
            assertEquals(outputSize, report.size); writes += report.copyOf(); afterWrite(); return report.size
        }
        override fun read(report: ByteArray, timeoutMillis: Int): Int {
            val next = incoming.removeFirst(); assertEquals(next.size, report.size)
            next.copyInto(report); reads++; afterRead(); return report.size
        }
        override fun interrupt() = Unit
        override fun close() { closed = true }
    }
    private fun frames(size: Int, command: Int, payload: ByteArray, cid: Int = 1): List<ByteArray> {
        val reports = mutableListOf<ByteArray>()
        var offset = 0
        var sequence = -1
        do {
            val report = ByteArray(size)
            for (index in 0..3) report[index] = (cid ushr (24 - index * 8)).toByte()
            val header = if (sequence == -1) {
                report[4] = (command or 128).toByte()
                report[5] = (payload.size ushr 8).toByte(); report[6] = payload.size.toByte(); 7
            } else { report[4] = sequence.toByte(); 5 }
            val count = minOf(payload.size - offset, size - header)
            payload.copyInto(report, header, offset, offset + count)
            reports += report; offset += count; sequence++
        } while (offset < payload.size)
        return reports
    }
    private fun descriptor(input: Int, output: Int) = listOf(
        0x06, 0xd0, 0xf1, 0x09, 1, 0xa1, 1,
        0x09, 0x20, 0x75, 8, 0x96, input and 255, input ushr 8, 0x81, 2,
        0x09, 0x21, 0x96, output and 255, output ushr 8, 0x91, 2, 0xc0,
    ).map { it.toByte() }.toByteArray()
}
