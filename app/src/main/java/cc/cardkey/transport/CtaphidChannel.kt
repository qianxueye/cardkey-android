package cc.cardkey.transport

import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.Medium
import cc.cardkey.protocol.TransportException
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

internal interface CtaphidIo : Closeable {
    fun write(report: ByteArray, timeoutMillis: Int): Int
    fun read(report: ByteArray, timeoutMillis: Int): Int
    fun interrupt()
    fun sendCancel(report: ByteArray, timeoutMillis: Int) { write(report, timeoutMillis) }
}

internal class CtaphidChannel(
    private val io: CtaphidIo,
    private val timeoutMillis: Int = 15000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val nonce: () -> ByteArray = { ByteArray(8).also { SecureRandom().nextBytes(it) } },
) : CardChannel {
    override val medium = Medium.CONTACT
    override val wire = CardWire.CTAP
    override val maxCommandBytes = MAX_MESSAGE_BYTES
    override var supportsCtap2 = false
        private set
    override var supportsCtap1 = false
        private set
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var retired = false
    private val presenceLock = Any()
    private var presenceListener: ((Boolean) -> Unit)? = null
    private var userPresenceRequired = false
    private var initialized = false
    private var cid = BROADCAST_CID
    private var deadline = 0L
    private var dispatched = false
    private var processingObserved = false

    init { require(timeoutMillis in 1..60000) }

    @Synchronized
    fun initialize() {
        if (initialized) throw TransportException(Dispatch.NOT_DISPATCHED)
        val challenge = nonce()
        require(challenge.size == 8)
        try {
            transaction(INIT) {
                send(INIT, challenge)
                val reply = receive(INIT)
                try {
                    require(reply.size >= 17 && reply.copyOfRange(0, 8).contentEquals(challenge))
                    val assigned = readCid(reply, 8)
                    require(assigned != 0L && assigned != BROADCAST_CID && u8(reply[12]) == 2)
                    val capabilities = u8(reply[16])
                    supportsCtap2 = capabilities and 4 != 0
                    supportsCtap1 = capabilities and 8 == 0
                    require(supportsCtap2 || supportsCtap1)
                    cid = assigned
                    initialized = true
                } finally { reply.fill(0) }
            }
        } finally { challenge.fill(0) }
    }

    override fun setUserPresenceListener(listener: ((Boolean) -> Unit)?) {
        synchronized(presenceLock) {
            if (closed.get() || cancelled.get()) return
            presenceListener = listener
            listener?.invoke(userPresenceRequired)
        }
    }

    private fun reportUserPresence(required: Boolean) {
        synchronized(presenceLock) {
            userPresenceRequired = required && !closed.get() && !cancelled.get()
            presenceListener?.invoke(userPresenceRequired)
        }
    }

    override fun transmit(command: ByteArray): ByteArray = throw CardFailure(FailureCode.UNSUPPORTED)

    @Synchronized
    override fun exchangeCtap2(command: ByteArray): ByteArray = exchange(CBOR, command, supportsCtap2, 1)

    @Synchronized
    override fun exchangeCtap1(command: ByteArray): ByteArray = exchange(MSG, command, supportsCtap1, 2)

    private fun exchange(command: Int, payload: ByteArray, supported: Boolean, minimumResponse: Int): ByteArray {
        if (!initialized || closed.get() || cancelled.get()) {
            throw TransportException(Dispatch.NOT_DISPATCHED, cancelled = cancelled.get())
        }
        if (!supported) throw CardFailure(FailureCode.UNSUPPORTED)
        if (payload.isEmpty() || payload.size > MAX_MESSAGE_BYTES) throw TransportException(Dispatch.NOT_DISPATCHED)
        return transaction(command) {
            send(command, payload)
            val result = receive(command)
            try {
                require(result.size >= minimumResponse)
                ensureAvailable()
                result
            } catch (failure: Exception) {
                result.fill(0)
                throw failure
            }
        }
    }

    private fun <T> transaction(command: Int, block: () -> T): T {
        deadline = nanoTime() + timeoutMillis * 1_000_000L
        dispatched = false
        processingObserved = false
        try {
            ensureAvailable()
            return block()
        } catch (failure: Exception) {
            if (cancelled.get() && dispatched && command == CBOR) {
                val cancel = initialReport(cid, CANCEL, 0)
                try { runCatching { io.sendCancel(cancel, 1000) } } finally { cancel.fill(0) }
            }
            close()
            if (failure is CardFailure && failure.code == FailureCode.BUSY && !cancelled.get()) throw failure
            throw TransportException(if (dispatched) Dispatch.RESPONSE_UNAVAILABLE else Dispatch.NOT_DISPATCHED,
                failure, cancelled.get())
        } finally { reportUserPresence(false) }
    }

    private fun send(command: Int, payload: ByteArray) {
        var offset = 0
        var sequence = 0
        do {
            val report = if (offset == 0) initialReport(cid, command, payload.size)
                else ByteArray(REPORT_BYTES).also { putCid(it, cid); it[4] = sequence++.toByte() }
            try {
                val header = if (offset == 0) 7 else 5
                val count = minOf(payload.size - offset, REPORT_BYTES - header)
                payload.copyInto(report, header, offset, offset + count)
                val timeout = remainingMillis()
                // Once the native queue is attempted, loss of completion cannot prove no dispatch.
                dispatched = true
                require(io.write(report, timeout) == REPORT_BYTES)
                offset += count
            } finally { report.fill(0) }
        } while (offset < payload.size)
    }

    private fun receive(command: Int): ByteArray {
        val report = ByteArray(REPORT_BYTES)
        var output: ByteArray? = null
        var success = false
        try {
            while (true) {
                require(io.read(report, remainingMillis()) == REPORT_BYTES)
                remainingMillis()
                require(readCid(report) == cid && u8(report[4]) and 0x80 != 0)
                val receivedCommand = u8(report[4]) and 0x7f
                val length = (u8(report[5]) shl 8) or u8(report[6])
                require(length <= MAX_MESSAGE_BYTES)
                if (receivedCommand == KEEPALIVE) {
                    require(command != INIT && length == 1 && u8(report[7]) in 1..2)
                    processingObserved = true
                    reportUserPresence(u8(report[7]) == 2)
                    continue
                }
                if (receivedCommand == ERROR) {
                    require(length == 1)
                    if (u8(report[7]) == 6 && !processingObserved) throw CardFailure(FailureCode.BUSY, 6)
                    throw IOException("CTAPHID error ${u8(report[7])}")
                }
                require(receivedCommand == command)
                output = ByteArray(length)
                var offset = minOf(length, 57)
                report.copyInto(output, 0, 7, 7 + offset)
                var sequence = 0
                while (offset < length) {
                    require(io.read(report, remainingMillis()) == REPORT_BYTES)
                    remainingMillis()
                    require(readCid(report) == cid && u8(report[4]) == sequence++)
                    val count = minOf(length - offset, 59)
                    report.copyInto(output, offset, 5, 5 + count)
                    offset += count
                }
                ensureAvailable()
                success = true
                return output
            }
        } finally {
            report.fill(0)
            if (!success) output?.fill(0)
        }
    }

    private fun ensureAvailable() {
        if (closed.get() || cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Closed or cancelled")
    }

    private fun remainingMillis(): Int {
        ensureAvailable()
        val remaining = deadline - nanoTime()
        if (remaining <= 0) throw IOException("CTAPHID timeout")
        return ((remaining + 999_999L) / 1_000_000L).coerceAtMost(timeoutMillis.toLong()).toInt()
    }

    override fun cancel() {
        synchronized(presenceLock) { cancelled.set(true) }
        io.interrupt()
        close()
    }

    override fun close() {
        synchronized(presenceLock) {
            closed.set(true)
            userPresenceRequired = false
            val listener = presenceListener
            presenceListener = null
            listener?.invoke(false)
        }
        io.interrupt()
        synchronized(this) {
            if (!retired) { retired = true; io.close() }
        }
    }

    companion object {
        const val REPORT_BYTES = 64
        const val MAX_MESSAGE_BYTES = 7609
        private const val BROADCAST_CID = 0xffffffffL
        private const val MSG = 0x03
        private const val INIT = 0x06
        private const val CBOR = 0x10
        private const val CANCEL = 0x11
        private const val KEEPALIVE = 0x3b
        private const val ERROR = 0x3f
        private fun u8(value: Byte) = value.toInt() and 255
        private fun readCid(bytes: ByteArray, offset: Int = 0): Long =
            (0..3).fold(0L) { value, index -> (value shl 8) or u8(bytes[offset + index]).toLong() }
        private fun putCid(bytes: ByteArray, cid: Long) {
            for (index in 0..3) bytes[index] = (cid ushr (24 - index * 8)).toByte()
        }
        private fun initialReport(cid: Long, command: Int, length: Int) = ByteArray(REPORT_BYTES).also {
            putCid(it, cid)
            it[4] = (command or 0x80).toByte()
            it[5] = (length ushr 8).toByte()
            it[6] = length.toByte()
        }
    }
}
