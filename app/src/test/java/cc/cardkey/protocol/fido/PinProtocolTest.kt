package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.hex
import org.junit.Assert.*
import org.junit.Test

class PinProtocolTest {
    @Test fun protocolKdfKnownAnswersAndAuthSizes() {
        val z = ByteArray(32) { it.toByte() }
        assertEquals("630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd", PinProtocol.derive(1, z).hex())
        assertEquals("a689b3b92a6ebab91192408da9c4f05c674a2bc5f938d613077716c719a8df390" +
            "f6ff2ef211829c11638ef2893ea02edf195658c0572393e7680d93bc2b58d44", PinProtocol.derive(2, z).hex())
        assertEquals(16, PinProtocol.authenticate(1, ByteArray(32), z).size)
        assertEquals(32, PinProtocol.authenticate(2, ByteArray(32), z).size)
    }
    @Test fun protocol2UsesFreshIvAndRoundTrips() {
        val key = PinProtocol.derive(2, ByteArray(32) { it.toByte() }); val plain = ByteArray(64) { 7 }
        val first = PinProtocol.encrypt(2, key, plain); val second = PinProtocol.encrypt(2, key, plain)
        assertEquals(80, first.size); assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, PinProtocol.decrypt(2, key, first))
        assertThrows(CardFailure::class.java) { PinProtocol.decrypt(2, key, ByteArray(16)) }
    }
    @Test fun protocol1UsesZeroIvAndTokenCannotBeReusedAfterClose() {
        val key = ByteArray(32) { it.toByte() }; val plain = ByteArray(32)
        assertArrayEquals(PinProtocol.encrypt(1, key, plain), PinProtocol.encrypt(1, key, plain))
        assertArrayEquals(plain, PinProtocol.decrypt(1, key, PinProtocol.encrypt(1, key, plain)))
        val tokenBytes = ByteArray(32) { 9 }; val token = PinToken(2, tokenBytes)
        token.close(); assertArrayEquals(ByteArray(32), tokenBytes)
        assertThrows(IllegalStateException::class.java) { token.authenticate(plain) }
    }
    @Test fun pinPolicyCountsCodePointsAndRejectsInvalidUtf8Input() {
        assertThrows(IllegalArgumentException::class.java) { pinBytes(charArrayOf('\ud83d', '\ude00', '\ud83d', '\ude00'), 4) }
        assertThrows(CardFailure::class.java) { pinBytes(charArrayOf('a', 'b', 'c', '\ud800'), 4) }
        assertThrows(CardFailure::class.java) { pinBytes(CharArray(64) { 'a' }, 4) }
        assertThrows(IllegalArgumentException::class.java) { pinBytes(charArrayOf('a', 'b', 'c', '\u0000'), 4) }
    }
}
