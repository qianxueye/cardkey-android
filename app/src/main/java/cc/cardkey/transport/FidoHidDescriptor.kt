package cc.cardkey.transport

internal object FidoHidDescriptor {
    fun reportLength(raw: ByteArray, interfaceId: Int): Int {
        var offset = 0
        var matching = false
        var length: Int? = null
        while (offset < raw.size) {
            require(offset + 2 <= raw.size)
            val size = raw[offset].toInt() and 255
            require(size >= 2 && offset + size <= raw.size)
            when (raw[offset + 1].toInt() and 255) {
                4 -> {
                    require(size >= 9)
                    matching = raw[offset + 2].toInt() and 255 == interfaceId && raw[offset + 3] == 0.toByte() &&
                        raw[offset + 5] == 3.toByte() && raw[offset + 6] == 0.toByte() && raw[offset + 7] == 0.toByte()
                }
                0x21 -> if (matching) {
                    require(length == null && size >= 9)
                    val count = raw[offset + 5].toInt() and 255
                    require(size == 6 + count * 3)
                    for (index in 0 until count) {
                        val start = offset + 6 + index * 3
                        if (raw[start] == 0x22.toByte()) {
                            require(length == null)
                            length = (raw[start + 1].toInt() and 255) or ((raw[start + 2].toInt() and 255) shl 8)
                        }
                    }
                }
            }
            offset += size
        }
        return requireNotNull(length).also { require(it in 1..4096) }
    }

    fun validate(report: ByteArray) {
        require(report.isNotEmpty() && report.size <= 4096)
        var offset = 0
        var usagePage = 0L
        var usage: Long? = null
        var reportSize = 0L
        var reportCount = 0L
        var inCollection = false
        var collections = 0
        var inputBits = 0L
        var outputBits = 0L
        while (offset < report.size) {
            val prefix = report[offset++].toInt() and 255
            require(prefix != 0xfe)
            val size = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            require(offset + size <= report.size)
            var value = 0L
            for (index in 0 until size) value = value or ((report[offset + index].toLong() and 255) shl (index * 8))
            offset += size
            val type = (prefix ushr 2) and 3
            val tag = prefix ushr 4
            when (type) {
                1 -> when (tag) {
                    0 -> usagePage = value
                    1, 2, 3, 4, 5, 6 -> Unit
                    7 -> reportSize = value
                    9 -> reportCount = value
                    else -> throw IllegalArgumentException("Unsupported HID report layout")
                }
                2 -> {
                    require(tag == 0 && usage == null)
                    usage = if (size == 4) value else (usagePage shl 16) or value
                }
                0 -> {
                    when (tag) {
                        10 -> {
                            require(!inCollection && collections++ == 0 && value == 1L && usage == 0xf1d00001L)
                            inCollection = true
                        }
                        12 -> { require(inCollection && size == 0); inCollection = false }
                        8, 9 -> {
                            require(inCollection && value == 2L && reportSize == 8L && reportCount == 64L)
                            require(usage == if (tag == 8) 0xf1d00020L else 0xf1d00021L)
                            if (tag == 8) inputBits += reportSize * reportCount else outputBits += reportSize * reportCount
                        }
                        else -> throw IllegalArgumentException("Unsupported HID main item")
                    }
                    usage = null
                }
                else -> throw IllegalArgumentException("Invalid HID item")
            }
        }
        require(!inCollection && collections == 1 && inputBits == 512L && outputBits == 512L && usage == null)
    }
}
