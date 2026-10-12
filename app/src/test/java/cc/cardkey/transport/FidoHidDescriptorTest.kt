package cc.cardkey.transport

import org.junit.Assert.*
import org.junit.Test

class FidoHidDescriptorTest {
    private val report = bytes(0x06, 0xd0, 0xf1, 0x09, 0x01, 0xa1, 0x01,
        0x09, 0x20, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02,
        0x09, 0x21, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x91, 0x02, 0xc0)

    @Test fun acceptsUnnumberedFido64ByteInputAndOutput() { FidoHidDescriptor.validate(report) }

    @Test fun rejectsWrongUsageNumberedReportsWrongSizeAndTruncation() {
        val invalid = listOf(
            report.copyOf().also { it[1] = 1 },
            report.copyOf().also { it[4] = 2 },
            report.copyOf().also { it[8] = 0x21 },
            report.copyOf().also { it[17] = 63 },
            report.copyOf().also { it[19] = 3 },
            report.copyOf().also { it[32] = 1 },
            report.copyOf(report.size - 1),
            report + report,
            bytes(0x85, 1) + report,
            report + bytes(0x06, 0xd0),
            report + bytes(0xfe),
        )
        for (candidate in invalid) {
            try { FidoHidDescriptor.validate(candidate); fail("Accepted invalid descriptor") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun findsReportDescriptorOnlyForSelectedHidInterface() {
        val raw = intf(1, 0x0b) + hid(200) + intf(2, 3) + hid(report.size)
        assertEquals(report.size, FidoHidDescriptor.reportLength(raw, 2))
        try { FidoHidDescriptor.reportLength(raw, 1); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsDuplicateMissingOrOversizedReportDescriptorsAndBadUsbLengths() {
        val invalid = listOf(
            intf(1, 3) + hid(34) + hid(34),
            intf(1, 3),
            intf(1, 3) + hid(4097),
            intf(1, 3) + hid(0),
            intf(1, 3) + hid(34).copyOf(8),
            bytes(0, 4),
        )
        for (candidate in invalid) {
            try { FidoHidDescriptor.reportLength(candidate, 1); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    private fun intf(id: Int, type: Int) = bytes(9, 4, id, 0, 2, type, 0, 0, 0)
    private fun hid(length: Int) = bytes(9, 0x21, 0x11, 1, 0, 1, 0x22, length and 255, length ushr 8)
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
}
