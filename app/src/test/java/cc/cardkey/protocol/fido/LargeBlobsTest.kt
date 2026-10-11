package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test

class LargeBlobsTest {
    @Test fun initialArrayHasStandardDigestAndRejectsCorruption() {
        assertEquals("8076be8b528d0075f7aae98d6fa57a6d3c", LargeBlobs.encode(emptyList()).hex())
        val corrupt = LargeBlobs.encode(emptyList()).also { it[1] = 0 }
        assertThrows(CardFailure::class.java) { LargeBlobs.decode(corrupt) }
        assertThrows(CardFailure::class.java) { LargeBlobs.encode(listOf(cborMap())) }
    }
    @Test fun aeadAndDeflateRoundTripEmptyAndLargeInputsAndBindSize() {
        val key = ByteArray(32) { 9 }
        for (bytes in listOf(byteArrayOf(), ByteArray(100000) { (it % 255).toByte() })) {
            val entry = LargeBlobs.encrypt(key, bytes)
            assertArrayEquals(bytes, LargeBlobs.decrypt(key, entry))
            assertNull(LargeBlobs.decrypt(ByteArray(32), entry))
            val tampered = Cbor.Map(entry.value + cborMap(3 to (bytes.size + 1).cbor()).value)
            assertNull(LargeBlobs.decrypt(key, tampered))
        }
    }
    @Test fun updatePreservesUnrelatedEntriesUsesPermissionAndReadsBack() {
        val server = FidoClientTest.PinServer(2)
        val otherKey = ByteArray(32) { 4 }; val key = ByteArray(32) { 7 }; val retained = key.copyOf()
        val other = LargeBlobs.encrypt(otherKey, byteArrayOf(1, 2))
        var stored = LargeBlobs.encode(listOf(other))
        var staged = byteArrayOf(); var total = 0; var writes = 0
        val metadata = info(mapOf("pinUvAuthToken" to true, "largeBlobs" to true, "clientPin" to true),
            extra = cborMap(5 to 128.cbor(), 11 to 4096.cbor()))
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(metadata)
            6 -> server.respond(map!!, 16, null)
            0x0c -> {
                val params = map!!; val offset = params[3].count()
                if (params[1] != null) {
                    val count = params[1].count()
                    success(cborMap(1 to stored.copyOfRange(offset, minOf(offset + count, stored.size)).cbor()))
                } else {
                    val fragment = params[2].bytes(); assertTrue(fragment.size <= 64)
                    if (offset == 0) { total = params[4].count(); staged = byteArrayOf() } else assertNull(params[4])
                    assertEquals(staged.size, offset)
                    val message = ByteArray(32) { 0xff.toByte() } + byteArrayOf(12, 0) +
                        ByteArray(4) { (offset ushr (it * 8)).toByte() } + sha256(fragment)
                    assertArrayEquals(PinProtocol.authenticate(2, server.token, message), params[5].bytes())
                    staged += fragment; writes++
                    if (staged.size == total) stored = staged
                    success()
                }
            }
            else -> error("unexpected command")
        } }
        val plaintext = ByteArray(200) { it.toByte() }; val expected = plaintext.copyOf(); val pin = "1234".toCharArray()
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }.writeLargeBlob(pin, key, plaintext) }
        assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect); assertTrue(writes > 1)
        val entries = LargeBlobs.decode(stored); assertEquals(2, entries.size); assertEquals(other, entries[0])
        assertArrayEquals(expected, LargeBlobs.decrypt(retained, entries[1]))
        assertArrayEquals(ByteArray(32), key); assertArrayEquals(ByteArray(200), plaintext); assertArrayEquals(CharArray(4), pin)
    }
    @Test fun interruptedBlobWriteNeverReplaysTheFragment() {
        val initial = LargeBlobs.encode(emptyList()); var writes = 0
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("largeBlobs" to true), extra = cborMap(5 to 128.cbor(), 11 to 4096.cbor())))
            0x0c -> if (map!![1] != null) success(cborMap(1 to initial.cbor())) else {
                writes++; throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            }
            else -> error("unexpected command")
        } }
        val result = CardSession(channel).run { session -> FidoClient(session).apply { select() }
            .writeLargeBlob(null, ByteArray(32), ByteArray(256) { it.toByte() }) }
        assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE), result)
        assertEquals(1, writes); assertTrue(channel.closed)
    }
}
