package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Test

class ManagerBlobInputTest {
    @Test fun importedBytesSurviveSuccessfulAndFailedAttempts() {
        val imported = "reusable imported contents".toByteArray()
        val expected = imported.copyOf()
        val key = ByteArray(32) { 7 }
        for (failWrite in listOf(false, true, false)) {
            var stored = LargeBlobs.encode(emptyList())
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("largeBlobs" to true), extra = cborMap(11 to 4096.cbor())))
                12 -> if (map!![1] != null) success(cborMap(1 to stored.cbor())) else {
                    if (failWrite) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    stored = map[2].bytes(); success()
                }
                else -> error("Unexpected command")
            } }
            val result = CardSession(channel).run { ManagerOperations().execute(it,
                OperationInput(ManagerOperation.FIDO_BLOB_WRITE, adminKey = key.copyOf(), bytes = imported)) }
            assertArrayEquals(expected, imported)
            if (failWrite) assertTrue(result is OperationResult.Failed) else {
                assertTrue(result is OperationResult.Completed)
                assertArrayEquals(expected, LargeBlobs.decrypt(key, LargeBlobs.decode(stored).single()))
            }
        }
    }
}
