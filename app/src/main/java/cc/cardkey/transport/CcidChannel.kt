package cc.cardkey.transport

import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.Medium
import cc.cardkey.protocol.TransportException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal interface CcidIo : Closeable {
    fun write(message: ByteArray, timeoutMillis: Int): Int
    fun read(buffer: ByteArray, timeoutMillis: Int): Int
}

internal data class CcidCapabilities(
    val protocols: Int,
    val features: Int,
    val maxMessageBytes: Int,
    val maxIfsd: Int = 32,
) {
    val tpdu: Boolean get() = features and 0x70000 == 0x10000
    val extended: Boolean get() = features and 0x70000 == 0x40000
    val maxCommandBytes: Int get() = if (extended || tpdu) 65544 else minOf(261, maxMessageBytes - 10)

    companion object {
        fun parse(raw: ByteArray, interfaceId: Int): CcidCapabilities {
            var offset = 0
            var matchingInterface = false
            var result: CcidCapabilities? = null
            while (offset < raw.size) {
                require(offset + 2 <= raw.size)
                val size = raw.u8(offset)
                require(size >= 2 && offset + size <= raw.size)
                when (raw.u8(offset + 1)) {
                    4 -> {
                        require(size >= 9)
                        matchingInterface = raw.u8(offset + 2) == interfaceId &&
                            raw.u8(offset + 3) == 0 && raw.u8(offset + 5) == 0x0b &&
                            raw.u8(offset + 6) == 0 && raw.u8(offset + 7) == 0
                    }
                    0x21 -> if (matchingInterface) {
                        require(result == null && size == 54)
                        val descriptor = raw.copyOfRange(offset, offset + size)
                        val version = descriptor.u8(2) or (descriptor.u8(3) shl 8)
                        require(version in setOf(0x0100, 0x0110))
                        require(descriptor.u8(4) == 0) { "Multi-slot CCID readers are unsupported" }
                        val protocols = descriptor.u32(6)
                        val features = descriptor.u32(40)
                        val maxMessage = descriptor.u32(44)
                        val maxIfsd = descriptor.u32(28)
                        require(protocols in 1L..3L) { "Unsupported CCID protocols" }
                        require(features and 0x70000 in setOf(0x10000L, 0x20000L, 0x40000L)) { "Unsupported CCID exchange level" }
                        if (features and 0x70000 == 0x10000L) {
                            require(protocols and 2 != 0L) { "CCID TPDU exchange requires T=1" }
                            require(maxIfsd in 32L..254L)
                            require(features and 0x400 == 0L)
                        }
                        require(features and 2 != 0L) { "CCID automatic parameter configuration is required" }
                        require(features and 0xc0 in setOf(0x40L, 0x80L)) { "Unsupported CCID automatic negotiation" }
                        require(maxMessage in 271L..65554L) { "Unsupported CCID message capacity" }
                        result = CcidCapabilities(protocols.toInt(), features.toInt(), maxMessage.toInt(), maxIfsd.toInt())
                    }
                }
                offset += size
            }
            return requireNotNull(result)
        }
    }
}

internal class CcidChannel(
    private val io: CcidIo,
    private val capabilities: CcidCapabilities,
    private val inputPacketBytes: Int,
    private val timeoutMillis: Int = 15000,
    private val nanoTime: () -> Long = System::nanoTime,
    override val medium: Medium = Medium.UNKNOWN,
) : CardChannel {
    override val maxCommandBytes = capabilities.maxCommandBytes
    private var sequence = 0
    private val closed = AtomicBoolean(false)
    private var initialized = false
    private var t1: T1Protocol? = null
    private var deadline = 0L
    private var dispatched = false

    init {
        require(inputPacketBytes in 1..1024)
        require(timeoutMillis in 1..60000)
    }

    @Synchronized
    fun isCardAbsent(): Boolean = try {
        transaction {
            val status = exchange(0x65, byteArrayOf(), 0x81)
            require(status.data.isEmpty() && status.parameter in 0..3)
            status.cardStatus == 2
        }
    } catch (_: TransportException) {
        false
    }

    @Synchronized
    fun initialize(allowInitialPowerOn: Boolean) {
        check(!initialized)
        transaction {
            val status = exchange(0x65, byteArrayOf(), 0x81)
            require(status.data.isEmpty() && status.parameter in 0..3)
            val atr = when (status.cardStatus) {
                0 -> null
                1 -> {
                    require(allowInitialPowerOn)
                    // Voltage auto-selection is needed to avoid guessing or cycling voltages.
                    require(capabilities.features and 8 != 0)
                    val powered = exchange(0x62, byteArrayOf(), 0x80)
                    require(powered.cardStatus == 0 && powered.parameter == 0)
                    AtrProtocols.parse(powered.data)
                }
                else -> throw IOException("No active card")
            }
            val parameters = exchange(0x6c, byteArrayOf(), 0x82)
            require(parameters.cardStatus == 0 && parameters.parameter in 0..1)
            require(parameters.data.size == if (parameters.parameter == 0) 5 else 7)
            require(capabilities.protocols and (1 shl parameters.parameter) != 0)
            require(atr == null || parameters.parameter in atr)
            if (capabilities.tpdu) {
                require(parameters.parameter == 1)
                t1 = T1Protocol(T1Parameters.parse(parameters.data)) { block, multiplier ->
                    require(block.size <= capabilities.maxMessageBytes - 10)
                    val reply = exchange(0x6f, block, 0x80, bwi = multiplier)
                    require(reply.cardStatus == 0 && reply.parameter == 0)
                    reply.data
                }.also { it.synchronize() }
            }
            initialized = true
        }
    }

    @Synchronized
    override fun transmit(command: ByteArray): ByteArray {
        if (!initialized || closed.get() || command.size !in 4..maxCommandBytes ||
            (!capabilities.extended && !capabilities.tpdu && command.size > 5 && command[4] == 0.toByte())) {
            throw TransportException(Dispatch.NOT_DISPATCHED)
        }
        return transaction {
            t1?.let { return@transaction it.transmit(command) }
            val chunkBytes = capabilities.maxMessageBytes - 10
            var offset = 0
            var response: Response
            do {
                val end = minOf(command.size, offset + chunkBytes)
                val level = when {
                    offset == 0 && end == command.size -> 0
                    offset == 0 -> 1
                    end == command.size -> 2
                    else -> 3
                }
                response = exchange(0x6f, command.copyOfRange(offset, end), 0x80, level)
                require(response.cardStatus == 0)
                if (end != command.size) require(response.parameter == 0x10 && response.data.isEmpty())
                offset = end
            } while (offset < command.size)

            val output = ByteArrayOutputStream()
            var fragments = 0
            var continuing = false
            while (true) {
                require(++fragments <= 512)
                require(output.size() + response.data.size <= 65538)
                output.write(response.data)
                when (response.parameter) {
                    0 -> { require(!continuing); break }
                    1 -> { require(capabilities.extended && !continuing); continuing = true }
                    2 -> { require(continuing); break }
                    3 -> require(continuing)
                    else -> throw IOException("Invalid CCID response chain")
                }
                response = exchange(0x6f, byteArrayOf(), 0x80, 0x10)
                require(response.cardStatus == 0)
            }
            require(output.size() >= 2)
            output.toByteArray()
        }
    }

    private fun <T> transaction(block: () -> T): T {
        dispatched = false
        deadline = nanoTime() + timeoutMillis * 1_000_000L
        try {
            ensureAvailable()
            return block()
        } catch (error: Exception) {
            close()
            throw TransportException(if (dispatched) Dispatch.RESPONSE_UNAVAILABLE else Dispatch.NOT_DISPATCHED, error)
        }
    }

    private fun ensureAvailable(): Int {
        if (closed.get() || Thread.currentThread().isInterrupted) throw IOException("Transport closed or cancelled")
        val remaining = (deadline - nanoTime() + 999_999) / 1_000_000
        if (remaining <= 0) throw IOException("CCID deadline exceeded")
        return minOf(remaining, timeoutMillis.toLong()).toInt()
    }

    private fun exchange(type: Int, data: ByteArray, expected: Int, level: Int = 0, bwi: Int = 0): Response {
        val seq = sequence
        sequence = (sequence + 1) and 0xff
        val message = ByteArray(10 + data.size)
        message[0] = type.toByte()
        for (i in 0..3) message[1 + i] = (data.size ushr (8 * i)).toByte()
        message[6] = seq.toByte()
        message[7] = bwi.toByte()
        message[8] = level.toByte()
        message[9] = (level ushr 8).toByte()
        data.copyInto(message, 10)
        val timeout = ensureAvailable()
        val previouslyDispatched = dispatched
        // A negative Android bulk result can conceal a partial write.
        dispatched = true
        val written = io.write(message, timeout)
        if (written == 0) dispatched = previouslyDispatched
        if (written != message.size) throw IOException("Incomplete CCID write")

        // An extra packet gives the transfer room to consume a terminating ZLP.
        val buffer = ByteArray(((capabilities.maxMessageBytes / inputPacketBytes) + 1) * inputPacketBytes)
        repeat(65) {
            val received = io.read(buffer, ensureAvailable())
            require(received in 10..capabilities.maxMessageBytes)
            require(buffer.u8(0) == expected && buffer.u8(5) == 0 && buffer.u8(6) == seq)
            val length = buffer.u32(1)
            require(length == (received - 10).toLong())
            val status = buffer.u8(7)
            require(status and 0x3c == 0 && status and 3 != 3)
            when (status ushr 6) {
                0 -> return Response(status and 3, buffer.u8(9), buffer.copyOfRange(10, received))
                2 -> require(length == 0L)
                else -> throw IOException("CCID command failed")
            }
        }
        throw IOException("Too many CCID time extensions")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { io.close() }
    }

    private data class Response(val cardStatus: Int, val parameter: Int, val data: ByteArray)
}

internal object AtrProtocols {
    fun parse(atr: ByteArray): Set<Int> {
        require(atr.size in 2..33 && atr.u8(0) in setOf(0x3b, 0x3f))
        val historicalBytes = atr.u8(1) and 0x0f
        var mask = atr.u8(1) ushr 4
        var position = 2
        val protocols = mutableSetOf<Int>()
        var needsCheckByte = false
        while (true) {
            for (bit in 0..2) if (mask and (1 shl bit) != 0) {
                require(position < atr.size)
                position++
            }
            if (mask and 8 == 0) break
            require(position < atr.size)
            val td = atr.u8(position++)
            val protocol = td and 15
            if (protocol != 15) protocols.add(protocol)
            if (protocol != 0) needsCheckByte = true
            mask = td ushr 4
        }
        if (protocols.isEmpty()) protocols.add(0)
        require(position + historicalBytes + (if (needsCheckByte) 1 else 0) == atr.size)
        if (needsCheckByte) require(atr.drop(1).fold(0) { sum, byte -> sum xor (byte.toInt() and 0xff) } == 0)
        require(protocols.all { it in 0..1 })
        return protocols
    }
}

private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 0xff
private fun ByteArray.u32(offset: Int): Long = (0..3).fold(0L) { value, index ->
    value or (u8(offset + index).toLong() shl (8 * index))
}
