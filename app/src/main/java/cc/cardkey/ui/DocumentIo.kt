package cc.cardkey.ui

import java.io.InputStream
import java.io.OutputStream

internal object DocumentIo {
    fun read(open: () -> InputStream?): ByteArray {
        val buffer = ByteArray(65_537)
        try {
            val length = requireNotNull(open()).use { input ->
                var length = 0
                while (length < buffer.size) {
                    val count = input.read(buffer, length, buffer.size - length)
                    if (count < 0) break
                    check(count > 0)
                    length += count
                }
                length
            }
            require(length <= 65_536)
            return buffer.copyOf(length)
        } finally { buffer.fill(0) }
    }

    fun write(bytes: ByteArray, open: () -> OutputStream?): Boolean = try {
        requireNotNull(open()).use { output -> output.write(bytes); output.flush() }
        true
    } catch (_: Exception) { false }
}
