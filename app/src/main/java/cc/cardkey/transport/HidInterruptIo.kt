package cc.cardkey.transport

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

internal interface HidInterruptRequest : Closeable {
    fun queue(buffer: ByteBuffer): Boolean
    fun cancel()
}

internal interface HidInterruptConnection : Closeable {
    fun request(input: Boolean): HidInterruptRequest
    fun await(timeoutMillis: Int): HidInterruptRequest?
    fun releaseInterface()
}

internal class HidInterruptIo(private val connection: HidInterruptConnection, private val reports: FidoHidReports = FidoHidReports()) : CtaphidIo {
    private val state = Any()
    private val transferLock = Any()
    private var pending: HidInterruptRequest? = null
    private var interrupted = false
    private var closed = false
    private var disconnected = false

    override fun write(report: ByteArray, timeoutMillis: Int): Int = transfer(report, timeoutMillis, false)
    override fun read(report: ByteArray, timeoutMillis: Int): Int = transfer(report, timeoutMillis, true)
    override fun sendCancel(report: ByteArray, timeoutMillis: Int) {
        transfer(report, timeoutMillis, false, allowInterrupted = true)
    }

    private fun transfer(report: ByteArray, timeoutMillis: Int, input: Boolean, allowInterrupted: Boolean = false): Int =
        synchronized(transferLock) {
            require(report.size == (if (input) reports.inputBytes else reports.outputBytes) && timeoutMillis > 0)
            val buffer = ByteBuffer.allocateDirect(report.size)
            if (!input) { buffer.put(report); buffer.flip() }
            var request: HidInterruptRequest? = null
            var queued = false
            var reaped = false
            try {
                synchronized(state) {
                    if (closed || disconnected || (interrupted && !allowInterrupted)) throw IOException("USB interrupted")
                    request = connection.request(input)
                    pending = request
                    queued = true
                    queued = request.queue(buffer)
                    if (!queued) throw IOException("USB request was not queued")
                }
                val completed = connection.await(timeoutMillis)
                reaped = completed === request
                if (!reaped) throw IOException("Unexpected USB completion")
                synchronized(state) {
                    if (interrupted && !allowInterrupted) throw IOException("USB interrupted")
                }
                val transferred = buffer.position()
                if (transferred != report.size) throw IOException()
                if (input) { buffer.flip(); buffer.get(report, 0, transferred) }
                transferred
            } finally {
                if (queued && !reaped) {
                    runCatching { request!!.cancel() }
                    val retired = runCatching { connection.await(1000) === request }.getOrDefault(false)
                    if (!retired) {
                        // Closing the usbfs connection retires kernel requests before their buffers are released.
                        synchronized(state) { disconnected = true }
                        connection.close()
                    }
                }
                synchronized(state) { pending = null }
                try { request?.close() } finally {
                    buffer.clear()
                    while (buffer.hasRemaining()) buffer.put(0)
                }
            }
        }

    override fun interrupt() {
        synchronized(state) {
            interrupted = true
            pending?.let { runCatching { it.cancel() } }
        }
    }

    override fun close() {
        interrupt()
        synchronized(transferLock) {
            synchronized(state) {
                if (closed) return
                closed = true
            }
            if (!disconnected) {
                try { connection.releaseInterface() } finally { connection.close() }
            }
        }
    }
}
