package cc.cardkey.protocol.piv

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode

internal object PivTlv {
    fun encode(tag: Int, value: ByteArray): ByteArray {
        require(tag in 1..0xffffff && value.size <= 65535)
        val tags = when {
            tag > 65535 -> byteArrayOf((tag ushr 16).toByte(), (tag ushr 8).toByte(), tag.toByte())
            tag > 255 -> byteArrayOf((tag ushr 8).toByte(), tag.toByte())
            else -> byteArrayOf(tag.toByte())
        }
        val length = when {
            value.size < 128 -> byteArrayOf(value.size.toByte())
            value.size < 256 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size ushr 8).toByte(), value.size.toByte())
        }
        return tags + length + value
    }

    fun parse(data: ByteArray): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        var offset = 0
        fun octet(): Int {
            if (offset >= data.size) malformed()
            return data[offset++].toInt() and 255
        }
        while (offset < data.size) {
            var tag = octet()
            if (tag and 0x1f == 0x1f) {
                var count = 0
                do {
                    val next = octet()
                    if (++count > 2 || (count == 1 && next and 0x7f == 0)) malformed()
                    tag = (tag shl 8) or next
                } while (next and 0x80 != 0)
            }
            var length = octet()
            if (length and 0x80 != 0) {
                val count = length and 0x7f
                if (count !in 1..2) malformed()
                length = 0
                repeat(count) { length = (length shl 8) or octet() }
                if (length < 128 || (count == 2 && length < 256)) malformed()
            }
            if (length > data.size - offset || result.containsKey(tag)) malformed()
            result[tag] = data.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }

    fun one(data: ByteArray, tag: Int): ByteArray = parse(data).let {
        if (it.size != 1) malformed()
        it[tag] ?: malformed()
    }
}

internal fun malformed(): Nothing = throw CardFailure(FailureCode.MALFORMED_RESPONSE)
