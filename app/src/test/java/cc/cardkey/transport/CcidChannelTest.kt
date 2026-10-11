package cc.cardkey.transport

import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CcidChannelTest {
    private val caps = CcidCapabilities(3, 0x4004a, 271)
    private val apdu = byteArrayOf(0, 0x20, 0, 0)

    @Test fun absenceProbeUsesOnlyGetSlotStatusAndClosesWithoutPowerCommands() {
        for (status in 0..2) {
            val io = FakeIo(listOf(frame(0x81, 0, status = status)))
            val absent = CcidChannel(io, caps, 64, 1500).use { it.isCardAbsent() }
            assertEquals(status == 2, absent)
            assertEquals(listOf(0x65), io.types())
            assertEquals(1, io.closes)
        }
    }

    @Test fun absenceProbeFailuresNeverProveRemoval() {
        val invalid = listOf(
            emptyList(),
            listOf(frame(0x81, 0, status = 0x42)),
            listOf(frame(0x81, 1, status = 2)),
            listOf(frame(0x80, 0, status = 2)),
            listOf(frame(0x81, 0, status = 2, data = byteArrayOf(0))),
            listOf(frame(0x81, 0, status = 2, parameter = 4)),
            listOf(frame(0x81, 0, status = 3)),
            List(65) { frame(0x81, 0, status = 0x82) },
        )
        for (responses in invalid) {
            val io = FakeIo(responses)
            assertFalse(CcidChannel(io, caps, 64, 1500).use { it.isCardAbsent() })
            assertEquals(listOf(0x65), io.types())
            assertEquals(1, io.closes)
        }
        for (written in listOf(-1, 0, 5)) {
            val io = FakeIo(listOf(frame(0x81, 0, status = 2))).also { it.writeResult = written }
            assertFalse(CcidChannel(io, caps, 64, 1500).use { it.isCardAbsent() })
            assertEquals(listOf(0x65), io.types())
            assertEquals(0, io.reads)
            assertEquals(1, io.closes)
        }
    }

    @Test fun absenceProbeKeepsItsDeadlineAcrossExtensions() {
        var clock = 0L
        val io = FakeIo(listOf(frame(0x81, 0, status = 0x82)))
        io.afterRead = { clock += 1_501_000_000L }
        assertFalse(CcidChannel(io, caps, 64, 1500, { clock }).use { it.isCardAbsent() })
        assertEquals(listOf(0x65), io.types())
        assertEquals(1, io.reads)
        assertEquals(1, io.closes)
    }

    @Test fun activeCardNeverPowersOnOrOff() {
        val io = FakeIo(active())
        val channel = CcidChannel(io, caps, 64)
        channel.initialize(true)
        channel.close()
        channel.close()
        assertEquals(listOf(0x65, 0x6c), io.types())
        assertEquals(1, io.closes)
    }

    @Test fun initialPowerOnNeedsExplicitPermission() {
        val io = FakeIo(listOf(frame(0x81, 0, status = 1)))
        val channel = CcidChannel(io, caps, 64)
        expectFailure { channel.initialize(false) }
        assertEquals(listOf(0x65), io.types())
    }

    @Test fun inactiveCardIsPoweredOnceAndAtrProtocolChecked() {
        val io = FakeIo(listOf(
            frame(0x81, 0, status = 1),
            frame(0x80, 1, data = byteArrayOf(0x3b, 0x80.toByte(), 1, 0x81.toByte())),
            frame(0x82, 2, parameter = 1, data = ByteArray(7)),
        ))
        CcidChannel(io, caps, 64).initialize(true)
        assertEquals(listOf(0x65, 0x62, 0x6c), io.types())
        assertEquals(0, io.writes[1][7].toInt())
    }

    @Test fun atrAndSelectedProtocolMismatchRejectsConnection() {
        val io = FakeIo(listOf(
            frame(0x81, 0, status = 1),
            frame(0x80, 1, data = byteArrayOf(0x3b, 0)),
            frame(0x82, 2, parameter = 1, data = ByteArray(7)),
        ))
        expectFailure { CcidChannel(io, caps, 64).initialize(true) }
        assertFalse(0x6f in io.types())
    }

    @Test fun unsupportedSelectedProtocolNeverSendsApdu() {
        val io = FakeIo(active(2))
        expectFailure { CcidChannel(io, caps, 64).initialize(false) }
        assertFalse(0x6f in io.types())
    }

    @Test fun t0AndT1UseReaderApduExchange() {
        for (protocol in 0..1) {
            val io = FakeIo(active(protocol) + frame(0x80, 2, data = byteArrayOf(0x90.toByte(), 0)))
            val channel = ready(io)
            assertArrayEquals(byteArrayOf(0x90.toByte(), 0), channel.transmit(apdu))
            assertEquals(listOf(0x65, 0x6c, 0x6f), io.types())
        }
    }

    @Test fun timeExtensionReadsAgainWithoutResending() {
        val io = FakeIo(active() + listOf(
            frame(0x80, 2, status = 0x80),
            frame(0x80, 2, status = 0x80),
            frame(0x80, 2, data = byteArrayOf(0x90.toByte(), 0)),
        ))
        assertEquals(2, ready(io).transmit(apdu).size)
        assertEquals(1, io.types().count { it == 0x6f })
    }

    @Test fun timeExtensionsAreBounded() {
        val io = FakeIo(active() + List(65) { frame(0x80, 2, status = 0x80) })
        val channel = ready(io)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, expectFailure { channel.transmit(apdu) }.dispatch)
        assertEquals(1, io.types().count { it == 0x6f })
        assertEquals(1, io.closes)
    }

    @Test fun commandAndResponseChainsAreFramedWithoutReplay() {
        val io = FakeIo(active() + listOf(
            frame(0x80, 2, parameter = 0x10),
            frame(0x80, 3, parameter = 1, data = byteArrayOf(1, 2)),
            frame(0x80, 4, parameter = 3, data = byteArrayOf(3)),
            frame(0x80, 5, parameter = 2, data = byteArrayOf(0x90.toByte(), 0)),
        ))
        assertArrayEquals(byteArrayOf(1, 2, 3, 0x90.toByte(), 0), ready(io).transmit(ByteArray(300)))
        val frames = io.writes.drop(2)
        assertEquals(listOf(1, 2, 0x10, 0x10), frames.map { it[8].toInt() })
        assertEquals(listOf(271, 49, 10, 10), frames.map { it.size })
        assertArrayEquals(ByteArray(300), frames.take(2).flatMap { it.drop(10) }.toByteArray())
    }

    @Test fun responseChainCannotStartWithContinuation() {
        val io = FakeIo(active() + frame(0x80, 2, parameter = 2, data = byteArrayOf(0x90.toByte(), 0)))
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, expectFailure { ready(io).transmit(apdu) }.dispatch)
        assertEquals(3, io.writes.size)
    }

    @Test fun incorrectCommandChainAcknowledgementStopsBeforeNextFragment() {
        val io = FakeIo(active() + frame(0x80, 2, data = byteArrayOf(0x90.toByte(), 0)))
        expectFailure { ready(io).transmit(ByteArray(300)) }
        assertEquals(3, io.writes.size)
    }

    @Test fun malformedResponsesPoisonSessionAndNeverReplay() {
        val malformed = listOf(
            frame(0x80, 99, data = byteArrayOf(0x90.toByte(), 0)),
            frame(0x81, 2),
            frame(0x80, 2, status = 0x40),
            frame(0x80, 2, status = 2),
            frame(0x80, 2, data = byteArrayOf(1)),
            frame(0x80, 2, data = byteArrayOf(0x90.toByte(), 0)).also { it[1] = 3 },
            frame(0x80, 2, data = byteArrayOf(0x90.toByte(), 0)).also { it[5] = 1 },
            frame(0x80, 2).also { it[4] = 0x7f },
            frame(0x80, 2, status = 0xc0),
        )
        for (bad in malformed) {
            val io = FakeIo(active() + bad)
            val channel = ready(io)
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, expectFailure { channel.transmit(apdu) }.dispatch)
            assertEquals(Dispatch.NOT_DISPATCHED, expectFailure { channel.transmit(apdu) }.dispatch)
            assertEquals(3, io.writes.size)
            assertEquals(1, io.closes)
        }
    }

    @Test fun negativePartialAndThrownWritesAreUncertainAndNeverRetried() {
        for (written in listOf(-1, 1, 13)) {
            val io = FakeIo(active())
            val channel = ready(io)
            io.writeResult = written
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, expectFailure { channel.transmit(apdu) }.dispatch)
            assertEquals(3, io.writes.size)
        }
        val io = FakeIo(active())
        val channel = ready(io)
        io.throwWrite = true
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, expectFailure { channel.transmit(apdu) }.dispatch)
        assertEquals(3, io.writes.size)
    }

    @Test fun zeroWriteIsNotDispatched() {
        val io = FakeIo(active())
        val channel = ready(io)
        io.writeResult = 0
        assertEquals(Dispatch.NOT_DISPATCHED, expectFailure { channel.transmit(apdu) }.dispatch)
        assertEquals(3, io.writes.size)
    }

    @Test fun readTimeoutIsUncertain() {
        val io = FakeIo(active())
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, expectFailure { ready(io).transmit(apdu) }.dispatch)
        assertEquals(3, io.writes.size)
    }

    @Test fun deadlineAppliesAcrossTimeExtensions() {
        var clock = 0L
        val io = FakeIo(active() + frame(0x80, 2, status = 0x80))
        val channel = CcidChannel(io, caps, 64, 100, { clock })
        channel.initialize(false)
        io.afterRead = { clock += 101_000_000 }
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, expectFailure { channel.transmit(apdu) }.dispatch)
        assertEquals(3, io.reads)
    }

    @Test fun interruptBeforeTransmitDoesNotDispatch() {
        val io = FakeIo(active())
        val channel = ready(io)
        Thread.currentThread().interrupt()
        try {
            assertEquals(Dispatch.NOT_DISPATCHED, expectFailure { channel.transmit(apdu) }.dispatch)
            assertEquals(2, io.writes.size)
        } finally { Thread.interrupted() }
    }

    @Test fun shortOnlyReaderRejectsExtendedApduBeforeWrite() {
        val io = FakeIo(active())
        val channel = CcidChannel(io, caps.copy(features = 0x2004a), 64)
        channel.initialize(false)
        assertEquals(Dispatch.NOT_DISPATCHED, expectFailure { channel.transmit(ByteArray(7)) }.dispatch)
        assertEquals(2, io.writes.size)
    }

    @Test fun sequenceWrapsAtByteBoundary() {
        val replies = (0 until 260).map { frame(0x80, (it + 2) and 255, data = byteArrayOf(0x90.toByte(), 0)) }
        val io = FakeIo(active() + replies)
        val channel = ready(io)
        repeat(260) { channel.transmit(apdu) }
        assertEquals(0, io.writes[256][6].toInt())
    }

    private fun ready(io: FakeIo) = CcidChannel(io, caps, 64).also { it.initialize(false) }
    private fun active(protocol: Int = 1) = listOf(
        frame(0x81, 0), frame(0x82, 1, parameter = protocol, data = ByteArray(if (protocol == 0) 5 else 7)),
    )

    private fun expectFailure(block: () -> Unit): TransportException {
        try { block(); fail("Expected transport failure") } catch (error: TransportException) { return error }
        throw AssertionError()
    }

    private class FakeIo(responses: List<ByteArray>) : CcidIo {
        val writes = mutableListOf<ByteArray>()
        private val responses = ArrayDeque(responses)
        var closes = 0
        var reads = 0
        var writeResult: Int? = null
        var throwWrite = false
        var afterRead: () -> Unit = {}
        fun types() = writes.map { it[0].toInt() and 255 }
        override fun write(message: ByteArray, timeoutMillis: Int): Int {
            assertTrue(timeoutMillis > 0)
            writes.add(message.copyOf())
            if (throwWrite) throw IOException()
            return writeResult ?: message.size
        }
        override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
            assertTrue(timeoutMillis > 0)
            reads++
            val response = responses.removeFirstOrNull() ?: return -1
            response.copyInto(buffer)
            afterRead()
            return response.size
        }
        override fun close() { closes++ }
    }

    private fun frame(type: Int, sequence: Int, status: Int = 0, parameter: Int = 0, data: ByteArray = byteArrayOf()): ByteArray =
        ByteArray(10 + data.size).also {
            it[0] = type.toByte()
            for (i in 0..3) it[i + 1] = (data.size ushr (i * 8)).toByte()
            it[6] = sequence.toByte()
            it[7] = status.toByte()
            it[9] = parameter.toByte()
            data.copyInto(it, 10)
        }
}
