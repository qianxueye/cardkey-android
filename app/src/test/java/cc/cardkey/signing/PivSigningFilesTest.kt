package cc.cardkey.signing

import cc.cardkey.ui.ManagerReport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class PivSigningFilesTest {
    @Test fun rawSnapshotKeepsCertificateLookingBytesAndTransfersIndependentOwnership() {
        val original = "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----".toByteArray()
        val read = PivSigningFiles.readRaw(ByteArrayInputStream(original))
        assertArrayEquals(original, read)
        val files = PivSigningFiles()
        val request = files.request("reader-a")
        assertSame(request, files.takeRequest())
        assertTrue(files.accept(request, "reader-a", "raw.pem", read))
        assertTrue(read.all { it == 0.toByte() })
        val selected = files.state.value.selection!!
        val input = files.take(selected.generation, "reader-a")
        assertArrayEquals(original, input)
        assertNull(files.state.value.selection)
        files.retire()
        assertArrayEquals(original, input)
        input.fill(0)
        assertThrows(IllegalArgumentException::class.java) { files.take(selected.generation, "reader-a") }
    }

    @Test fun emptyOversizeReadAndCloseFailureCannotLeaveInputBytes() {
        for (size in listOf(0, 1_048_577)) assertThrows(IllegalArgumentException::class.java) {
            PivSigningFiles.readRaw(ByteArrayInputStream(ByteArray(size)))
        }
        assertEquals(1_048_576, PivSigningFiles.readRaw(ByteArrayInputStream(ByteArray(1_048_576))).size)
        for (failOnClose in listOf(false, true)) {
            var buffer: ByteArray? = null
            val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    buffer = bytes
                    val count = super.read(bytes, offset, length)
                    if (!failOnClose) throw IOException()
                    return count
                }
                override fun close() { if (failOnClose) throw IOException() }
            }
            assertThrows(IOException::class.java) { PivSigningFiles.readRaw(stream) }
            assertTrue(buffer!!.all { it == 0.toByte() })
        }
    }

    @Test fun readerAndActionAbaCannotReviveLateSelectionAndDuplicateCallbackIsEmpty() {
        val files = PivSigningFiles()
        val old = files.request("reader-a")
        files.takeRequest()
        files.retireSelection()
        files.request("reader-b")
        files.request("reader-a")
        val bytes = byteArrayOf(1, 2, 3)
        assertFalse(files.accept(old, "reader-a", "late", bytes))
        assertTrue(bytes.all { it == 0.toByte() })
        assertNull(files.state.value.selection)
        assertNotNull(files.takeRequest())
        assertNull(files.takeRequest())
        assertNull(PivSigningFiles().takeRequest())
    }

    @Test fun saveCapturesOneVerifiedOutputAndWritesOnceWithCloseAcknowledgement() {
        val files = PivSigningFiles()
        val source = ManagerReport(emptyList(), byteArrayOf(1, 2, 3), "signature.bin")
        val newer = ManagerReport(emptyList(), byteArrayOf(7, 8, 9), "signature.bin")
        assertTrue(files.requestSave(source))
        assertFalse(files.requestSave(newer))
        val captured = files.takeSave()!!
        assertNull(files.takeSave())
        source.export!!.fill(4)
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val status = PivSigningFiles.writeSignature(captured) { output }
        assertEquals(SignatureExportStatus.SAVED, status)
        assertTrue(closed)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertTrue(captured.bytes.all { it == 0.toByte() })
        files.finishSave(captured, status)
        assertSame(source, files.state.value.exportOwner)
    }

    @Test fun cancelNullWriteAndCloseFailuresNeverRetryOrDiscardVerifiedSource() {
        for (mode in listOf("cancel", "null", "write", "close")) {
            val files = PivSigningFiles()
            val source = ManagerReport(emptyList(), byteArrayOf(1, 2, 3), "signature.bin")
            files.requestSave(source)
            val snapshot = files.takeSave()!!
            var opens = 0; var writes = 0
            val status = if (mode == "cancel") SignatureExportStatus.CANCELLED
            else PivSigningFiles.writeSignature(snapshot) {
                opens++
                if (mode == "null") null else object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, off: Int, len: Int) {
                        writes++
                        if (mode == "write") { super.write(bytes, off, 1); throw IOException() }
                        super.write(bytes, off, len)
                    }
                    override fun close() { if (mode == "close") throw IOException() }
                }
            }
            files.finishSave(snapshot, status)
            assertEquals(if (mode == "cancel") SignatureExportStatus.CANCELLED else SignatureExportStatus.UNKNOWN, files.state.value.exportStatus)
            assertEquals(if (mode == "cancel") 0 else 1, opens)
            assertEquals(if (mode in listOf("cancel", "null")) 0 else 1, writes)
            assertArrayEquals(byteArrayOf(1, 2, 3), source.export)
            assertTrue(snapshot.bytes.all { it == 0.toByte() })
            assertNull(files.takeSave())
        }
    }
}
