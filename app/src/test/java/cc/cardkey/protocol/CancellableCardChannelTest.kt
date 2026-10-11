package cc.cardkey.protocol

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CancellableCardChannelTest {
    @Test fun knownCardResponseSurvivesCancellationDuringTransmit() {
        var cancelled = false
        lateinit var channel: CancellableCardChannel
        val delegate = object : CardChannel {
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 261
            override fun transmit(command: ByteArray): ByteArray {
                cancelled = true; channel.cancel()
                return "63c2".hexBytes()
            }
            override fun close() = Unit
        }
        channel = CancellableCardChannel(delegate) { cancelled }
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0x20), effectful = true).requireSuccess() }
        assertEquals(OperationResult.Failed(FailureCode.PIN_INVALID, Effect.NONE, 0x63c2), result)
    }
    @Test fun cancellationBeforeDispatchStopsTheCommand() {
        val delegate = object : CardChannel {
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 261
            override fun transmit(command: ByteArray): ByteArray = error("Cancelled request must not dispatch")
            override fun close() = Unit
        }
        val result = CardSession(CancellableCardChannel(delegate) { true }).run {
            it.exchange(Apdu(ins = 0x20), effectful = true)
        }
        assertEquals(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED), result)
    }
    @Test fun onlyCancelledCloseConvertsIoFailureAndKeepsUncertainEffect() {
        for (closeForCancel in listOf(false, true)) {
            lateinit var channel: CancellableCardChannel
            val delegate = object : CardChannel {
                override val medium = Medium.CONTACT
                override val maxCommandBytes = 261
                override fun transmit(command: ByteArray): ByteArray {
                    if (closeForCancel) channel.cancel()
                    throw IOException("Synthetic unavailable response")
                }
                override fun close() = Unit
            }
            channel = CancellableCardChannel(delegate) { false }
            val result = CardSession(channel).run { it.exchange(Apdu(ins = 0x20), effectful = true) }
            assertEquals(OperationResult.Failed(if (closeForCancel) FailureCode.CANCELLED else FailureCode.TRANSPORT_FAILURE,
                Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE), result)
        }
    }
}
