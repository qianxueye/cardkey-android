package cc.cardkey.protocol

import java.io.IOException

internal class CancellableCardChannel(
    private val delegate: CardChannel,
    private val cancelled: () -> Boolean,
) : CardChannel by delegate {
    @Volatile private var cancelledClose = false

    override fun transmit(command: ByteArray): ByteArray {
        if (cancelled()) throw TransportException(Dispatch.NOT_DISPATCHED, cancelled = true)
        return try { delegate.transmit(command) }
        catch (failure: TransportException) {
            if (cancelledClose) throw TransportException(failure.dispatch, failure, cancelled = true)
            throw failure
        } catch (failure: IOException) {
            throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, failure, cancelled = cancelledClose)
        }
    }

    override fun exchangeCtap2(command: ByteArray): ByteArray = exchangeTyped(command, delegate::exchangeCtap2)

    override fun exchangeCtap1(command: ByteArray): ByteArray = exchangeTyped(command, delegate::exchangeCtap1)

    private fun exchangeTyped(command: ByteArray, exchange: (ByteArray) -> ByteArray): ByteArray {
        if (cancelledClose || cancelled()) throw TransportException(Dispatch.NOT_DISPATCHED, cancelled = true)
        val response = try { exchange(command) }
        catch (failure: TransportException) {
            if (cancelledClose || cancelled()) throw TransportException(failure.dispatch, failure, cancelled = true)
            throw failure
        } catch (failure: IOException) {
            throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, failure, cancelled = cancelledClose || cancelled())
        }
        if (cancelledClose || cancelled()) {
            response.fill(0)
            throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, cancelled = true)
        }
        return response
    }

    override fun cancel() {
        cancelledClose = true
        delegate.cancel()
    }
}
