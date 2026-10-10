package cc.cardkey.transport

import org.junit.Assert.*
import org.junit.Test

class CcidCapabilitiesTest {
    @Test fun descriptorSelectsItsOwnInterfaceAndCapabilities() {
        val caps = CcidCapabilities.parse(descriptor(), 2)
        assertTrue(caps.extended)
        assertEquals(65544, caps.maxCommandBytes)
        assertEquals(271, caps.maxMessageBytes)
        assertEquals(3, caps.protocols)
    }

    @Test fun shortApduCapabilityIsBounded() {
        val caps = CcidCapabilities.parse(descriptor(features = 0x2004a), 2)
        assertFalse(caps.extended)
        assertEquals(261, caps.maxCommandBytes)
    }

    @Test fun unsupportedOrMalformedDescriptorsAreRejected() {
        val invalid = listOf(
            descriptor(features = 0x1004a),
            descriptor(features = 0x6004a),
            descriptor(features = 0x40048),
            descriptor(features = 0x4000a),
            descriptor(features = 0x400ca),
            descriptor(protocols = 4),
            descriptor(protocols = 0),
            descriptor(messageBytes = 65555),
            descriptor(messageBytes = 20),
            descriptor().also { it[9 + 4] = 1 },
            descriptor().also { it[0] = 0 },
            descriptor().also { it[9] = 100 },
            descriptor().copyOf(40),
            descriptor().also { it[3] = 1 },
            descriptor().also { it[2] = 3 },
        )
        invalid.forEach { raw ->
            try { CcidCapabilities.parse(raw, 2); fail("Invalid descriptor accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun atrSupportsT0T1AndBothWithoutAssumingT1() {
        assertEquals(setOf(0), AtrProtocols.parse(byteArrayOf(0x3b, 0)))
        assertEquals(setOf(1), AtrProtocols.parse(byteArrayOf(0x3b, 0x80.toByte(), 1, 0x81.toByte())))
        assertEquals(setOf(0, 1), AtrProtocols.parse(byteArrayOf(0x3b, 0x80.toByte(), 0x80.toByte(), 1, 1)))
    }

    @Test fun atrRequiresValidLengthsChecksumAndSupportedProtocols() {
        val invalid = listOf(
            byteArrayOf(), byteArrayOf(0x3b), byteArrayOf(0, 0), byteArrayOf(0x3b, 1),
            byteArrayOf(0x3b, 0x80.toByte()), byteArrayOf(0x3b, 0x80.toByte(), 1, 0),
            byteArrayOf(0x3b, 0x80.toByte(), 2, 0x82.toByte()),
            byteArrayOf(0x3b, 0, 0),
        )
        invalid.forEach {
            try { AtrProtocols.parse(it); fail("Invalid ATR accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun descriptor(features: Int = 0x4004a, protocols: Int = 3, messageBytes: Int = 271): ByteArray {
        val header = byteArrayOf(9, 4, 2, 0, 2, 0x0b, 0, 0, 0)
        val ccid = ByteArray(54)
        ccid[0] = 54
        ccid[1] = 0x21
        ccid[2] = 0x10
        ccid[3] = 1
        for ((offset, value) in listOf(6 to protocols, 40 to features, 44 to messageBytes)) {
            for (i in 0..3) ccid[offset + i] = (value ushr (i * 8)).toByte()
        }
        return header + ccid
    }
}
