package cc.cardkey.protocol

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CancellableCardChannelTest {
    @Test fun typedCancellationGuardsBothRoutesBeforeAndAfterDispatch() {
        for (ctap1 in listOf(false, true)) for (before in listOf(false, true)) {
            var cancelled = before
            var dispatches = 0
            var cancellations = 0
            val response = "009000".hexBytes()
            val delegate = object : CardChannel {
                override val wire = CardWire.CTAP
                override val supportsCtap1 = true
                override val supportsCtap2 = true
                override val medium = Medium.CONTACT
                override val maxCommandBytes = 7609
                override fun transmit(command: ByteArray): ByteArray = error("Unexpected APDU")
                override fun exchangeCtap2(command: ByteArray): ByteArray { dispatches++; cancelled = true; return response }
                override fun exchangeCtap1(command: ByteArray): ByteArray { dispatches++; cancelled = true; return response }
                override fun cancel() { cancellations++ }
                override fun close() = Unit
            }
            val channel = CancellableCardChannel(delegate) { cancelled }
            val result = CardSession(channel).run {
                if (ctap1) it.exchangeCtap1(Apdu(ins = 1), effectful = true)
                else it.exchangeCtap2(byteArrayOf(1), effectful = true)
            } as OperationResult.Failed
            assertEquals(FailureCode.CANCELLED, result.code)
            assertEquals(if (before) Effect.NONE else Effect.UNKNOWN, result.effect)
            assertEquals(if (before) Dispatch.NOT_DISPATCHED else Dispatch.RESPONSE_UNAVAILABLE, result.dispatch)
            assertEquals(if (before) 0 else 1, dispatches)
            if (!before) assertArrayEquals(ByteArray(3), response)
            channel.cancel()
            assertEquals(1, cancellations)
        }
    }

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
