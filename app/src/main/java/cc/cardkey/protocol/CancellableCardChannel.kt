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

    fun cancel() {
        cancelledClose = true
        delegate.close()
    }
}
