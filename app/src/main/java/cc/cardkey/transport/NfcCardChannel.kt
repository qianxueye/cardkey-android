package cc.cardkey.transport

import android.nfc.Tag
import android.nfc.tech.IsoDep
import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.Medium
import cc.cardkey.protocol.TransportException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal interface IsoDepIo : Closeable {
    val maxCommandBytes: Int
    val supportsExtendedApdus: Boolean
    fun transmit(command: ByteArray): ByteArray
}

class NfcCardChannel internal constructor(private val io: IsoDepIo) : CardChannel {
    override val medium = Medium.NFC
    override val maxCommandBytes = io.maxCommandBytes
    private val closed = AtomicBoolean(false)

    @Synchronized
    override fun transmit(command: ByteArray): ByteArray {
        if (closed.get() || Thread.currentThread().isInterrupted || command.size !in 4..maxCommandBytes ||
            (!io.supportsExtendedApdus && command.size > 5 && command[4] == 0.toByte())) {
            throw TransportException(Dispatch.NOT_DISPATCHED)
        }
        try {
            return io.transmit(command).also { require(it.size in 2..65538) }
        } catch (error: Exception) {
            close()
            throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, error)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { io.close() }
    }

    companion object {
        fun connect(tag: Tag, timeoutMillis: Int = 15000): NfcCardChannel {
            require(timeoutMillis in 1..60000)
            val isoDep = IsoDep.get(tag) ?: throw TransportException(Dispatch.NOT_DISPATCHED)
            try {
                if (Thread.currentThread().isInterrupted) throw TransportException(Dispatch.NOT_DISPATCHED)
                isoDep.connect()
                isoDep.timeout = timeoutMillis
                return NfcCardChannel(object : IsoDepIo {
                    override val supportsExtendedApdus = isoDep.isExtendedLengthApduSupported
                    override val maxCommandBytes = minOf(
                        isoDep.maxTransceiveLength,
                        if (isoDep.isExtendedLengthApduSupported) 65544 else 261,
                    )
                    override fun transmit(command: ByteArray): ByteArray = isoDep.transceive(command)
                    override fun close() = isoDep.close()
                })
            } catch (error: Exception) {
                runCatching { isoDep.close() }
                throw TransportException(Dispatch.NOT_DISPATCHED, error)
            }
        }
    }
}
