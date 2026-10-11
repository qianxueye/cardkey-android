package cc.cardkey.transport

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.TransportException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CtaphidChannelTest {
    private val challenge = ByteArray(8) { (it + 1).toByte() }

    @Test fun initializesFromNonceAndNegotiatedCapabilitiesAndKeepsCid() {
        val io = FakeIo(init() + frames(0x10, byteArrayOf(0, 1)) + frames(3, byteArrayOf(0x90.toByte(), 0)))
        val channel = ready(io)
        assertEquals(CardWire.CTAP, channel.wire)
        assertTrue(channel.supportsCtap1)
        assertTrue(channel.supportsCtap2)
        assertArrayEquals(byteArrayOf(0, 1), channel.exchangeCtap2(byteArrayOf(4)))
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0), channel.exchangeCtap1(byteArrayOf(0, 3, 0, 0, 0, 0, 0)))
        assertEquals(listOf(0x86, 0x90, 0x83), io.writes.map { it[4].toInt() and 255 })
        assertEquals(listOf(255, 1, 1), io.writes.map { it[3].toInt() and 255 })
    }

    @Test fun capabilitiesBlockUnsupportedWireCommandsWithoutDispatch() {
        val io = FakeIo(init(capabilities = 8 or 4))
        val channel = ready(io)
        for (call in listOf<() -> Unit>({ channel.transmit(ByteArray(7)) }, { channel.exchangeCtap1(ByteArray(7)) })) {
            try { call(); fail() } catch (failure: CardFailure) { assertEquals(FailureCode.UNSUPPORTED, failure.code) }
        }
        assertEquals(1, io.writes.size)
    }

    @Test fun initAcceptsBoundedFutureExtensionFields() {
        val io = FakeIo(frames(6, initReply() + ByteArray(70) { 42 }, 0xffffffffL) + frames(0x10, byteArrayOf(0)))
        val channel = ready(io)
        assertTrue(channel.supportsCtap2)
        assertArrayEquals(byteArrayOf(0), channel.exchangeCtap2(byteArrayOf(4)))
    }

    @Test fun invalidInitNonceCidProtocolOrLengthRetiresConnection() {
        val malformed = listOf(
            initReply().also { it[0] = 99 },
            initReply().also { it.fill(0, 8, 12) },
            initReply().also { it.fill(255.toByte(), 8, 12) },
            initReply().also { it[12] = 1 },
            initReply().copyOf(16),
        )
        for (reply in malformed) {
            val io = FakeIo(frames(6, reply, 0xffffffffL))
            failure { ready(io) }
            assertEquals(1, io.closes)
        }
    }

    @Test fun completeMaximumPayloadUsesSequence0Through127AndReassemblesResponse() {
        val payload = ByteArray(7609) { it.toByte() }
        val io = FakeIo(init() + frames(0x10, payload))
        assertArrayEquals(payload, ready(io).exchangeCtap2(payload))
        assertEquals(130, io.writes.size)
        assertEquals((0..127).toList(), io.writes.drop(2).map { it[4].toInt() and 255 })
        assertArrayEquals(payload, io.writes[1].copyOfRange(7, 64) + io.writes.drop(2).flatMap { it.drop(5) }.toByteArray())
    }

    @Test fun oversizeOrEmptyCommandDoesNotPoisonOrDispatch() {
        val io = FakeIo(init() + frames(0x10, byteArrayOf(0)))
        val channel = ready(io)
        for (payload in listOf(ByteArray(76010), byteArrayOf())) assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.exchangeCtap2(payload) }.dispatch)
        assertArrayEquals(byteArrayOf(0), channel.exchangeCtap2(byteArrayOf(4)))
        assertEquals(2, io.writes.size)
    }

    @Test fun keepaliveIsIntermediateAndDoesNotResetDeadline() {
        var time = 0L
        val io = FakeIo(init() + frames(0x3b, byteArrayOf(1)) + frames(0x3b, byteArrayOf(2)) + frames(0x10, byteArrayOf(0)))
        val channel = CtaphidChannel(io, 100, { time }, { challenge.copyOf() }).also { it.initialize() }
        io.afterRead = { time += 60_000_000 }
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.exchangeCtap2(byteArrayOf(4)) }.dispatch)
        assertEquals(3, io.reads)
        assertEquals(1, io.closes)
    }

    @Test fun finalResponseArrivingAfterDeadlineCannotSucceed() {
        var time = 0L
        val io = FakeIo(init() + frames(0x10, byteArrayOf(0)))
        val channel = CtaphidChannel(io, 100, { time }, { challenge.copyOf() }).also { it.initialize() }
        io.afterRead = { time = 101_000_000 }
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.exchangeCtap2(byteArrayOf(4)) }.dispatch)
        assertEquals(1, io.closes)
    }

    @Test fun userPresenceProgressTracksUpNeededProcessingAndTerminalResponse() {
        val io = FakeIo(init() + frames(0x3b, byteArrayOf(2)) + frames(0x3b, byteArrayOf(1)) + frames(0x10, byteArrayOf(0)))
        val channel = ready(io)
        val progress = mutableListOf<Boolean>()
        channel.setUserPresenceListener { progress += it }
        channel.exchangeCtap2(byteArrayOf(4))
        assertEquals(listOf(false, true, false, false), progress)
        channel.close()
        assertEquals(false, progress.last())
        channel.setUserPresenceListener { fail("Closed channel retained a listener") }
    }

    @Test fun userPresenceProgressClearsOnMalformedTerminalResponse() {
        val io = FakeIo(init() + frames(0x3b, byteArrayOf(2)) + frames(3, byteArrayOf(0)))
        val channel = ready(io)
        val progress = mutableListOf<Boolean>()
        channel.setUserPresenceListener { progress += it }
        failure { channel.exchangeCtap2(byteArrayOf(4)) }
        assertEquals(listOf(false, true, false), progress)
    }

    @Test fun cancellationClearsProgressAndRejectsLateUpNeeded() {
        val io = FakeIo(init() + frames(0x3b, byteArrayOf(2)))
        val channel = ready(io)
        val progress = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        channel.setUserPresenceListener { progress += it }
        val readEntered = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        io.afterRead = { readEntered.countDown(); check(readReleased.await(5, TimeUnit.SECONDS)) }
        io.onInterrupt = { readReleased.countDown() }
        val outcome = AtomicReference<TransportException>()
        val worker = Thread { outcome.set(failure { channel.exchangeCtap2(byteArrayOf(4)) }) }
        worker.start()
        assertTrue(readEntered.await(5, TimeUnit.SECONDS))
        channel.cancel()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(outcome.get().cancelled)
        assertTrue(progress.isNotEmpty() && progress.none { it })
        val count = progress.size
        channel.close()
        assertEquals(count, progress.size)
    }

    @Test fun keepaliveCanPrecedeSuccessfulCborResponse() {
        val io = FakeIo(init() + frames(0x3b, byteArrayOf(1)) + frames(0x3b, byteArrayOf(2)) + frames(0x10, byteArrayOf(0)))
        assertArrayEquals(byteArrayOf(0), ready(io).exchangeCtap2(byteArrayOf(4)))
    }

    @Test fun badReportLengthCidCommandSequenceOrDeclaredLengthPoisonsAndNeverReplays() {
        val bad = listOf(
            frames(0x10, byteArrayOf(0))[0].copyOf(63),
            frames(0x10, byteArrayOf(0), 2)[0],
            frames(3, byteArrayOf(0))[0],
            frames(0x10, byteArrayOf(0))[0].also { it[4] = 0 },
            frames(0x10, byteArrayOf(0))[0].also { it[5] = 30; it[6] = 0 },
            frames(0x3b, byteArrayOf(3))[0],
            frames(0x3b, byteArrayOf(1, 2))[0],
            frames(0x3f, byteArrayOf(0x7f))[0],
        ).map { listOf(it) } + listOf(frames(0x10, ByteArray(60)).also { it[1][4] = 1 })
        for (reports in bad) {
            val io = FakeIo(init() + reports)
            val channel = ready(io)
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.exchangeCtap2(byteArrayOf(4)) }.dispatch)
            assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.exchangeCtap2(byteArrayOf(4)) }.dispatch)
            assertEquals(2, io.writes.size)
            assertEquals(1, io.closes)
        }
    }

    @Test fun unknownWriteOrReadFailureIsNotReplayed() {
        for (written in listOf(-1, 0, 10)) {
            val io = FakeIo(init())
            val channel = ready(io)
            io.written = written
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.exchangeCtap2(byteArrayOf(4)) }.dispatch)
            assertEquals(2, io.writes.size)
        }
        val io = FakeIo(init())
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { ready(io).exchangeCtap2(byteArrayOf(4)) }.dispatch)
    }

    @Test fun shortCtapResponseCannotSucceed() {
        for ((command, response) in listOf(0x10 to byteArrayOf(), 3 to byteArrayOf(0x90.toByte()))) {
            val io = FakeIo(init() + frames(command, response))
            val channel = ready(io)
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure {
                if (command == 0x10) channel.exchangeCtap2(byteArrayOf(4)) else channel.exchangeCtap1(ByteArray(7))
            }.dispatch)
        }
    }

    @Test fun cancellationSuppressesLateSuccessAndSendsNoAckRequest() {
        val io = FakeIo(init() + frames(0x10, byteArrayOf(0, 42)))
        val channel = ready(io)
        val readEntered = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        io.afterRead = { readEntered.countDown(); check(readReleased.await(5, TimeUnit.SECONDS)) }
        io.onInterrupt = { readReleased.countDown() }
        val outcome = AtomicReference<TransportException>()
        val worker = Thread { outcome.set(failure { channel.exchangeCtap2(byteArrayOf(4)) }) }
        worker.start()
        assertTrue(readEntered.await(5, TimeUnit.SECONDS))
        channel.cancel()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(outcome.get().cancelled)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, outcome.get().dispatch)
        assertEquals(listOf(0x86, 0x90, 0x91), io.writes.map { it[4].toInt() and 255 })
        assertEquals(2, io.reads)
        assertEquals(1, io.closes)
        assertTrue(io.borrowedBuffers.all { report -> report.all { it == 0.toByte() } })
    }

    @Test fun cancellationBeforeCommandDoesNotDispatchAndCloseIsIdempotent() {
        val io = FakeIo(init())
        val channel = ready(io)
        channel.cancel()
        channel.close()
        assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.exchangeCtap2(byteArrayOf(4)) }.dispatch)
        assertEquals(1, io.writes.size)
        assertEquals(1, io.closes)
    }

    private fun ready(io: FakeIo) = CtaphidChannel(io, nonce = { challenge.copyOf() }).also { it.initialize() }
    private fun init(capabilities: Int = 4) = frames(6, initReply(capabilities), 0xffffffffL)
    private fun initReply(capabilities: Int = 4) = challenge + byteArrayOf(0, 0, 0, 1, 2, 1, 0, 0, capabilities.toByte())
    private fun failure(block: () -> Unit): TransportException {
        try { block(); fail("Expected failure") } catch (failure: TransportException) { return failure }
        throw AssertionError()
    }
    private class FakeIo(replies: List<ByteArray>) : CtaphidIo {
        val writes = mutableListOf<ByteArray>()
        val borrowedBuffers = mutableListOf<ByteArray>()
        val replies = ArrayDeque(replies)
        var written: Int? = null
        var closes = 0
        var reads = 0
        var afterRead: () -> Unit = {}
        var onInterrupt: () -> Unit = {}
        override fun write(report: ByteArray, timeoutMillis: Int): Int {
            writes += report.copyOf(); borrowedBuffers += report
            return written ?: 64
        }
        override fun read(report: ByteArray, timeoutMillis: Int): Int {
            reads++
            val reply = replies.removeFirstOrNull() ?: throw IOException("Disconnected")
            reply.copyInto(report)
            borrowedBuffers += report
            afterRead()
            return reply.size
        }
        override fun interrupt() { onInterrupt() }
        override fun close() { closes++ }
    }
    private fun frames(command: Int, payload: ByteArray, cid: Long = 1): List<ByteArray> {
        val reports = mutableListOf<ByteArray>()
        var offset = 0
        var seq = 0
        do {
            val report = ByteArray(64)
            for (index in 0..3) report[index] = (cid ushr (24 - index * 8)).toByte()
            val header = if (offset == 0) 7 else 5
            if (offset == 0) {
                report[4] = (command or 0x80).toByte()
                report[5] = (payload.size ushr 8).toByte()
                report[6] = payload.size.toByte()
            } else report[4] = seq++.toByte()
            val count = minOf(payload.size - offset, 64 - header)
            payload.copyInto(report, header, offset, offset + count)
            reports += report
            offset += count
        } while (offset < payload.size)
        return reports
    }
}
