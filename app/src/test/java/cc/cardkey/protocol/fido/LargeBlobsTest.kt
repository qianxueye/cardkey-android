package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test

class LargeBlobsTest {
    @Test fun initialArrayHasStandardDigestAndDiscardsDigestCorruption() {
        assertEquals("8076be8b528d0075f7aae98d6fa57a6d3c", LargeBlobs.encode(emptyList()).hex())
        val corrupt = LargeBlobs.encode(emptyList()).also { it[1] = 0 }
        assertTrue(LargeBlobs.decode(corrupt).isEmpty())
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
    @Test fun corruptInitialReadIsLogicalAbsenceWithoutWrites() {
        val corrupt = LargeBlobs.encode(listOf(LargeBlobs.encrypt(ByteArray(32), byteArrayOf(7)))).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (received in listOf(corrupt, byteArrayOf(), ByteArray(15))) {
            val channel = FidoChannel { code, map -> when(code) {
                4 -> success(info(mapOf("largeBlobs" to true), extra = cborMap(11 to 4096.cbor())))
                12 -> { assertNotNull(map!![1]); success(cborMap(1 to received.cbor())) }
                else -> error("Unexpected command")
            } }
            val key = ByteArray(32)
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.readLargeBlob(key) }
            assertEquals(OperationResult.Completed<ByteArray?>(null, Effect.NONE), result)
            assertTrue(channel.commands.filter { it.first == 12 }.all { it.second!![2] == null })
            assertArrayEquals(ByteArray(32), key)
        }
    }
    @Test fun authenticatedWriteReplacesCorruptArrayAndReadsActualTarget() {
        val server = FidoClientTest.PinServer(2)
        var stored = LargeBlobs.encode(emptyList()).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        var staged = byteArrayOf(); var writes = 0
        val metadata = info(mapOf("largeBlobs" to true, "clientPin" to true, "pinUvAuthToken" to true),
            extra = cborMap(5 to 128.cbor(), 11 to 4096.cbor()))
        val channel = FidoChannel { code, map -> when(code) {
            4 -> success(metadata)
            6 -> server.respond(map!!, 16, null)
            12 -> {
                val params = map!!; val offset = params[3].count()
                if (params[1] != null) {
                    val end = minOf(offset + params[1].count(), stored.size)
                    success(cborMap(1 to stored.copyOfRange(offset, end).cbor()))
                } else {
                    val part = params[2].bytes()
                    assertEquals(staged.size, offset)
                    val message = ByteArray(32) { 0xff.toByte() } + byteArrayOf(12, 0) +
                        ByteArray(4) { (offset ushr (it * 8)).toByte() } + sha256(part)
                    assertArrayEquals(PinProtocol.authenticate(2, server.token, message), params[5].bytes())
                    staged += part; stored = staged; writes++; success()
                }
            }
            else -> error("Unexpected command")
        } }
        val key = ByteArray(32) { 7 }; val plaintext = byteArrayOf(1, 2, 3)
        val result = CardSession(channel).run { FidoClient(it).apply { select() }.writeLargeBlob("1234".toCharArray(), key, plaintext) }
        assertTrue(result is OperationResult.Completed)
        assertTrue(writes > 0)
        val entries = LargeBlobs.decode(stored)
        assertEquals(1, entries.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), LargeBlobs.decrypt(ByteArray(32) { 7 }, entries.single()))
    }
    @Test fun corruptPostwriteCannotPassIncludingDeletionToEmptyTarget() {
        for (plaintext in listOf<ByteArray?>(null, byteArrayOf(7))) {
            var stored = LargeBlobs.encode(emptyList()); var writes = 0
            val channel = FidoChannel { code, map -> when(code) {
                4 -> success(info(mapOf("largeBlobs" to true), extra = cborMap(11 to 4096.cbor())))
                12 -> if (map!![1] != null) success(cborMap(1 to stored.cbor())) else {
                    writes++
                    stored = map[2].bytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
                    success()
                }
                else -> error("Unexpected command")
            } }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.writeLargeBlob(null, ByteArray(32), plaintext) }
            assertEquals(OperationResult.Failed(FailureCode.RECOVERY_REQUIRED, Effect.APPLIED, writeAccepted = true), result)
            assertEquals(1, writes)
        }
    }
    @Test fun validDigestMalformedPayloadAndOversizedInputAreNotRecovered() {
        for (payload in listOf(byteArrayOf(), byteArrayOf(0xa0.toByte()), byteArrayOf(0x81.toByte(), 0xa0.toByte()))) {
            val received = payload + sha256(payload).copyOf(16)
            assertThrows(CardFailure::class.java) { LargeBlobs.decode(received) }
        }
        assertThrows(CardFailure::class.java) { LargeBlobs.decode(ByteArray(CborCodec.MAX_BYTES + 1)) }
    }

    @Test fun corruptionRecoveryDoesNotBypassReceivedOrTargetCapacity() {
        for (received in listOf(ByteArray(18), ByteArray(17))) {
            var writes = 0
            val channel = FidoChannel { code, params -> when(code) {
                4 -> success(info(mapOf("largeBlobs" to true), extra = cborMap(11 to 17.cbor())))
                12 -> { if (params!![2] != null) writes++; success(cborMap(1 to received.cbor())) }
                else -> error("Unexpected command")
            } }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.writeLargeBlob(null, ByteArray(32), byteArrayOf(9)) }
            assertEquals(OperationResult.Failed(if (received.size > 17) FailureCode.MALFORMED_RESPONSE else FailureCode.CAPACITY_EXCEEDED,
                Effect.NONE), result)
            assertEquals(0, writes)
        }
    }
    @Test fun corruptArrayDoesNotBypassWriteAuthentication() {
        val channel = FidoChannel { code, params -> when(code) {
            4 -> success(info(mapOf("largeBlobs" to true, "clientPin" to true), extra = cborMap(11 to 4096.cbor())))
            12 -> { assertNull(params!![2]); success(cborMap(1 to ByteArray(17).cbor())) }
            else -> error("Unexpected command")
        } }
        val result = CardSession(channel).run { FidoClient(it).apply { select() }.writeLargeBlob(null, ByteArray(32), byteArrayOf(9)) }
        assertEquals(OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE), result)
        assertTrue(channel.commands.filter { it.first == 12 }.all { it.second!![2] == null })
    }
    @Test fun validDifferentPostwriteArrayStillFailsExactTargetComparison() {
        var stored = LargeBlobs.encode(emptyList()); var writes = 0
        val foreign = LargeBlobs.encode(listOf(LargeBlobs.encrypt(ByteArray(32) { 8 }, byteArrayOf(1))))
        val channel = FidoChannel { code, params -> when(code) {
            4 -> success(info(mapOf("largeBlobs" to true), extra = cborMap(11 to 4096.cbor())))
            12 -> if (params!![1] != null) success(cborMap(1 to stored.cbor())) else {
                writes++; stored = foreign; success()
            }
            else -> error("Unexpected command")
        } }
        val result = CardSession(channel).run { FidoClient(it).apply { select() }.writeLargeBlob(null, ByteArray(32), null) }
        assertEquals(OperationResult.Failed(FailureCode.RECOVERY_REQUIRED, Effect.APPLIED, writeAccepted = true), result)
        assertEquals(1, writes)
    }

}
