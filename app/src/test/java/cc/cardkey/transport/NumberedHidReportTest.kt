package cc.cardkey.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NumberedHidReportTest {
    @Test fun numberedDescriptorKeepsDirectionalPayloadLengths() {
        val reports = FidoHidDescriptor.validate(numberedDescriptor(16, 32, 3, 9))
        assertEquals(16, reports.inputBytes)
        assertEquals(32, reports.outputBytes)
    }

    @Test fun reportIdsAreDirectionalUnsignedGlobalState() {
        val inherited = numberedDescriptor(64, 64, 1, 1).toList().let { it.take(18) + it.drop(20) }.toByteArray()
        assertEquals(FidoHidReports(64, 64, 1, 1), FidoHidDescriptor.validate(inherited))
        assertEquals(FidoHidReports(16, 32, 3, 9), FidoHidDescriptor.validate(numberedDescriptor(16, 32, 3, 9)))
        val normal = numberedDescriptor(16, 32, 255, 9)
        val reversed = normal.take(7) + listOf(0x75.toByte(), 8.toByte()) + normal.slice(18..26) + normal.slice(7..17) + normal.last()
        assertEquals(FidoHidReports(16, 32, 255, 9), FidoHidDescriptor.validate(reversed.toByteArray()))
    }

    @Test fun invalidIdsMixedStreamsAndRichLayoutsAreRejected() {
        val normal = numberedDescriptor(16, 32, 3, 9)
        val invalid = listOf(
            numberedDescriptor(16, 32, 0, 9), numberedDescriptor(16, 32, 3, 0),
            normal.take(7).toByteArray() + byteArrayOf(0x86.toByte(), 0, 1) + normal.drop(9).toByteArray(),
            byteArrayOf(0x84.toByte()) + normal, normal + byteArrayOf(0x85.toByte()),
            (normal.take(7) + normal.drop(9)).toByteArray(),
            normal.dropLast(1).toByteArray() + normal.slice(7..17).toByteArray() + byteArrayOf(0xc0.toByte()),
            byteArrayOf(0xa4.toByte()) + normal, byteArrayOf(0xb4.toByte()) + normal,
            normal.copyOf().also { it[16] = 0xb1.toByte() },
            normal.copyOf().also { it[17] = 3 },
            normal + normal,
            numberedDescriptor(1025, 32, 3, 9), numberedDescriptor(32, 1025, 3, 9),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { FidoHidDescriptor.validate(it) } }
        for (id in listOf(-1, 256)) assertThrows(IllegalArgumentException::class.java) { FidoHidReports(64, 64, id, 1) }
        assertThrows(IllegalArgumentException::class.java) { FidoHidReports(64, 64, 0, 1) }
        for (id in listOf(0, 1)) assertThrows(IllegalArgumentException::class.java) { FidoHidReports(1025, 64, id, id) }
    }

    @Test fun nativeEnvelopesPreservePayloadAndExactAllocationAtBothBounds() {
        for (reports in listOf(FidoHidReports(), FidoHidReports(1024, 1024),
            FidoHidReports(7, 7, 255, 255), FidoHidReports(64, 64, 1, 1),
            FidoHidReports(1024, 1024, 3, 9), FidoHidReports(16, 32, 3, 9))) {
            val input = ByteArray(reports.inputBytes) { (it + 3).toByte() }
            val output = ByteArray(reports.outputBytes) { (it + 9).toByte() }
            val native = SyntheticNativeHidConnection(object : CtaphidIo {
                override fun write(report: ByteArray, timeoutMillis: Int): Int { assertArrayEquals(output, report); return report.size }
                override fun read(report: ByteArray, timeoutMillis: Int): Int { input.copyInto(report); return report.size }
                override fun interrupt() = Unit
                override fun close() = Unit
            }, reports)
            val io = HidInterruptIo(native, reports)
            assertEquals(output.size, io.write(output, 100))
            val read = ByteArray(input.size)
            assertEquals(input.size, io.read(read, 100))
            assertArrayEquals(input, read)
            assertArrayEquals((if (reports.outputReportID == 0) byteArrayOf() else byteArrayOf(reports.outputReportID.toByte())) + output,
                native.nativeWrites.single())
            assertEquals(listOf(reports.outputNativeBytes, reports.inputNativeBytes), native.buffers.map { it.capacity() })
            assertTrue(native.buffers.all { buffer -> (0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() } })
            assertEquals(FidoHidReports.capacity(reports.inputBytes), reports.inputCapacity)
            assertEquals(FidoHidReports.capacity(reports.outputBytes), reports.outputCapacity)
            io.close()
        }
    }

    @Test fun invalidNativeInputNeverPublishesPayload() {
        val reports = FidoHidReports(16, 32, 3, 9)
        for ((id, length) in listOf(4 to 17, 0 to 17, 3 to 0, 3 to 1, 3 to 16, 3 to 18)) {
            val native = SyntheticNativeHidConnection(object : CtaphidIo {
                override fun write(report: ByteArray, timeoutMillis: Int) = report.size
                override fun read(report: ByteArray, timeoutMillis: Int): Int { report.fill(42); return report.size }
                override fun interrupt() = Unit
                override fun close() = Unit
            }, reports).also { it.inputID = id; it.inputLength = length }
            val target = ByteArray(16) { 77 }
            try { HidInterruptIo(native, reports).read(target, 100); fail() }
            catch (_: IOException) { } catch (_: IllegalArgumentException) { }
            assertArrayEquals(ByteArray(16) { 77 }, target)
            assertTrue(native.buffers.all { buffer -> (0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() } })
        }
    }

    private fun numberedDescriptor(input: Int, output: Int, inputId: Int, outputId: Int) = listOf(
        0x06, 0xd0, 0xf1, 0x09, 1, 0xa1, 1,
        0x85, inputId, 0x09, 0x20, 0x75, 8, 0x96, input and 255, input ushr 8, 0x81, 2,
        0x85, outputId, 0x09, 0x21, 0x96, output and 255, output ushr 8, 0x91, 2, 0xc0,
    ).map { it.toByte() }.toByteArray()
}
