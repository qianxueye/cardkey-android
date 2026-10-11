package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.hexBytes
import cc.cardkey.protocol.hex
import org.junit.Assert.*
import org.junit.Test

class CborTest {
    @Test fun canonicalOrderUsesMajorTypeBeforeLength() {
        val map = Cbor.Map(linkedMapOf((-1).cbor() to true.cbor(), 1000.cbor() to false.cbor(), "a".cbor() to 0.cbor()))
        val encoded = CborCodec.encode(map)
        assertEquals("a31903e8f420f5616100", encoded.hex())
        assertEquals(map, CborCodec.decode(encoded))
    }
    @Test fun rejectsAmbiguousOrUnboundedMessages() {
        val bad = listOf("1817", "9fff", "a201000101", "a220001903e801", "61ff", "c000", "0000", "5a7fffffff", "1bffffffffffffffff", "f93c00")
        bad.forEach { bytes -> assertThrows(CardFailure::class.java) { CborCodec.decode(bytes.hexBytes()) } }
        assertThrows(CardFailure::class.java) { CborCodec.decode(ByteArray(18) { 0x81.toByte() } + byteArrayOf(0)) }
    }
    @Test fun supportsAuthDataPrefixButRejectsTrailingTopLevelBytes() {
        val bytes = "a1010241ab".hexBytes()
        val (map, consumed) = CborCodec.decodePrefix(bytes)
        assertEquals(3, consumed); assertEquals(2L, map.map()[1].number())
        assertThrows(CardFailure::class.java) { CborCodec.decode(bytes) }
    }
    @Test fun integerBoundariesRoundTrip() {
        listOf(0L, 23, 24, 255, 256, 65535, 65536, 0xffffffffL, Long.MAX_VALUE, -1, -24, -25, Long.MIN_VALUE).forEach {
            assertEquals(Cbor.Integer(it), CborCodec.decode(CborCodec.encode(Cbor.Integer(it))))
        }
    }
}
