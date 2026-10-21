package cc.cardkey.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class DocumentIoTest {
    @Test fun nullOrPartialWriteNeverReportsSaved() {
        assertFalse(DocumentIo.write(byteArrayOf(1)) { null })
        assertFalse(DocumentIo.write(byteArrayOf(1, 2)) { object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic document failure") }
        } })
        assertFalse(DocumentIo.write(byteArrayOf(1)) { object : ByteArrayOutputStream() {
            override fun close() { throw IOException("Synthetic close failure") }
        } })
        val output = ByteArrayOutputStream()
        assertTrue(DocumentIo.write(byteArrayOf(1, 2)) { output })
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
    }
    @Test fun inputMustOpenAndRemainWithinBound() {
        assertThrows(IllegalArgumentException::class.java) { DocumentIo.read { null } }
        assertThrows(IllegalArgumentException::class.java) { DocumentIo.read { ByteArrayInputStream(ByteArray(65_537)) } }
        assertArrayEquals(byteArrayOf(3), DocumentIo.read { ByteArrayInputStream(byteArrayOf(3)) })
    }
}
