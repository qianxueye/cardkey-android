package cc.cardkey.transport

import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class T1Parameters(val ifsc: Int, val crc: Boolean) {
    companion object {
        fun parse(bytes: ByteArray): T1Parameters {
            require(bytes.size == 7)
            val values = bytes.map { it.toInt() and 255 }
            require(values[0] ushr 4 in setOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13))
            require(values[0] and 15 in setOf(1, 2, 3, 4, 5, 6, 8, 9))
            require(values[1] and 0xfc == 0x10)
            require(values[3] ushr 4 <= 9)
            require(values[4] in 0..3 && values[5] in 1..254 && values[6] == 0)
            return T1Parameters(values[5], values[1] and 1 != 0)
        }
    }
}

internal class T1Protocol(
    parameters: T1Parameters,
    private val exchange: (ByteArray, Int) -> ByteArray,
) {
    private var ifsc = parameters.ifsc
    private val crc = parameters.crc
    private var sendSequence = 0
    private var receiveSequence = 0

    fun synchronize() {
        // An already active card may retain sequence numbers from another host session.
        val reply = decode(exchange(encode(0xc0), 0))
        require(reply.pcb == 0xe0 && reply.data.isEmpty())
        sendSequence = 0
        receiveSequence = 0
        // RESYNCH retains IFSD; an active card may have negotiated a larger size earlier.
        val ifs = decode(exchange(encode(0xc1, byteArrayOf(32)), 0))
        require(ifs.pcb == 0xe1 && ifs.data.contentEquals(byteArrayOf(32)))
    }

    fun transmit(command: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        var end = minOf(ifsc, command.size)
        var pending = encode((sendSequence shl 6) or if (end < command.size) 0x20 else 0,
            command.copyOfRange(offset, end))
        var outgoing = pending
        var waitingForCommandAck = true
        var receiving = false
        var errors = 0
        var extensions = 0
        var multiplier = 0
        var blocks = 0
        var lastResponseAck: ByteArray? = null
        while (true) {
            require(++blocks <= 131100)
            if (receiving && outgoing[1].toInt() and 0xc0 == 0x80) lastResponseAck = outgoing
            val raw = exchange(outgoing, multiplier)
            multiplier = 0
            val block = try { decode(raw) } catch (error: InvalidBlock) {
                require(++errors <= 3)
                outgoing = encode(0x80 or (receiveSequence shl 4) or error.code)
                continue
            }
            when {
                block.pcb and 0x80 == 0 -> {
                    require(end == command.size)
                    val sequence = block.pcb ushr 6
                    if (sequence != receiveSequence) {
                        require(receiving && ++errors <= 3)
                        outgoing = encode(0x80 or (receiveSequence shl 4))
                        continue
                    }
                    if (waitingForCommandAck) {
                        sendSequence = sendSequence xor 1
                        waitingForCommandAck = false
                    }
                    require(output.size() + block.data.size <= 65538)
                    output.write(block.data)
                    receiving = true
                    receiveSequence = receiveSequence xor 1
                    if (block.pcb and 0x20 == 0) {
                        require(output.size() >= 2)
                        return output.toByteArray()
                    }
                    outgoing = encode(0x80 or (receiveSequence shl 4))
                }
                block.pcb and 0xc0 == 0x80 -> {
                    if (receiving) {
                        require(++errors <= 3)
                        outgoing = requireNotNull(lastResponseAck)
                        continue
                    }
                    require(waitingForCommandAck)
                    val nextSequence = (block.pcb ushr 4) and 1
                    if (nextSequence == sendSequence) {
                        require(++errors <= 3)
                        require(pending[2].toInt() and 255 <= ifsc)
                        outgoing = pending
                    } else {
                        require(block.pcb and 15 == 0 && end < command.size)
                        sendSequence = sendSequence xor 1
                        offset = end
                        end = minOf(offset + ifsc, command.size)
                        pending = encode((sendSequence shl 6) or if (end < command.size) 0x20 else 0,
                            command.copyOfRange(offset, end))
                        outgoing = pending
                    }
                }
                block.pcb == 0xc1 -> {
                    require(++errors <= 3)
                    ifsc = block.data[0].toInt() and 255
                    outgoing = encode(0xe1, block.data)
                }
                block.pcb == 0xc2 -> {
                    exchange(encode(0xe2), 0)
                    throw IOException("T=1 command aborted")
                }
                block.pcb == 0xc3 -> {
                    require(++extensions <= 8)
                    multiplier = block.data[0].toInt() and 255
                    outgoing = encode(0xe3, block.data)
                }
                else -> throw IOException("Unexpected T=1 supervisory block")
            }
        }
    }

    private fun encode(pcb: Int, data: ByteArray = byteArrayOf()): ByteArray {
        val body = byteArrayOf(0, pcb.toByte(), data.size.toByte()) + data
        return body + checksum(body)
    }

    private fun decode(raw: ByteArray): Block {
        val edcBytes = if (crc) 2 else 1
        if (raw.size < 3 + edcBytes) throw InvalidBlock(2)
        val length = raw[2].toInt() and 255
        if (raw.size != 3 + length + edcBytes || length == 255) throw InvalidBlock(2)
        val body = raw.copyOf(raw.size - edcBytes)
        if (!checksum(body).contentEquals(raw.copyOfRange(body.size, raw.size))) throw InvalidBlock(1)
        if (raw[0] != 0.toByte()) throw InvalidBlock(2)
        val pcb = raw[1].toInt() and 255
        val data = raw.copyOfRange(3, 3 + length)
        val valid = when {
            pcb and 0x80 == 0 -> pcb and 0x1f == 0 && length in 0..32
            pcb and 0xc0 == 0x80 -> pcb and 0x2c == 0 && pcb and 3 <= 2 && length == 0
            pcb in setOf(0xc0, 0xe0, 0xc2, 0xe2) -> length == 0
            pcb in setOf(0xc1, 0xe1) -> length == 1 && data[0].toInt() and 255 in 1..254
            pcb in setOf(0xc3, 0xe3) -> length == 1 && data[0].toInt() and 255 in 1..59
            else -> false
        }
        if (!valid) throw InvalidBlock(2)
        return Block(pcb, data)
    }

    private fun checksum(body: ByteArray): ByteArray {
        if (!crc) return byteArrayOf(body.fold(0) { acc, byte -> acc xor (byte.toInt() and 255) }.toByte())
        var value = 0xffff
        for (byte in body) {
            value = value xor (byte.toInt() and 255)
            repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0x8408 else value ushr 1 }
        }
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private class InvalidBlock(val code: Int) : IOException()
    private data class Block(val pcb: Int, val data: ByteArray)
}
