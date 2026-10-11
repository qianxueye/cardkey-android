package cc.cardkey.transport

import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NfcCardChannelTest {
    @Test fun transmitUsesSingleExplicitConnectionAndCloseIsIdempotent() {
        val io = FakeIsoDep()
        val channel = NfcCardChannel(io)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0), channel.transmit(ByteArray(4)))
        channel.close()
        channel.close()
        assertEquals(1, io.calls)
        assertEquals(1, io.closes)
    }

    @Test fun failedTransceiveIsUncertainAndCannotBeRetriedOnChannel() {
        val io = FakeIsoDep().also { it.fail = true }
        val channel = NfcCardChannel(io)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.transmit(ByteArray(4)) })
        assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.transmit(ByteArray(4)) })
        assertEquals(1, io.calls)
        assertEquals(1, io.closes)
    }

    @Test fun oversizedCommandsAreRejectedBeforeDispatch() {
        val io = FakeIsoDep()
        assertEquals(Dispatch.NOT_DISPATCHED, failure { NfcCardChannel(io).transmit(ByteArray(262)) })
        assertEquals(0, io.calls)
    }

    @Test fun unsupportedExtendedApduIsRejectedEvenWhenShortEnough() {
        val io = FakeIsoDep()
        assertEquals(Dispatch.NOT_DISPATCHED, failure { NfcCardChannel(io).transmit(ByteArray(7)) })
        assertEquals(0, io.calls)
    }

    @Test fun interruptionBeforeTransceiveDoesNotDispatch() {
        val io = FakeIsoDep()
        Thread.currentThread().interrupt()
        try {
            assertEquals(Dispatch.NOT_DISPATCHED, failure { NfcCardChannel(io).transmit(ByteArray(4)) })
            assertEquals(0, io.calls)
        } finally { Thread.interrupted() }
    }

    private fun failure(block: () -> Unit): Dispatch {
        try { block(); fail("Expected failure") } catch (error: TransportException) { return error.dispatch }
        throw AssertionError()
    }

    private class FakeIsoDep : IsoDepIo {
        override val maxCommandBytes = 261
        override val supportsExtendedApdus = false
        var calls = 0
        var closes = 0
        var fail = false
        override fun transmit(command: ByteArray): ByteArray {
            calls++
            if (fail) throw IOException()
            return byteArrayOf(0x90.toByte(), 0)
        }
        override fun close() { closes++ }
    }
}
