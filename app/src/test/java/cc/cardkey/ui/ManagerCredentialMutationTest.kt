package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Test

class ManagerCredentialMutationTest {
    private val id = byteArrayOf(7)
    private val userId = byteArrayOf(8, 9)
    private val rpHash = sha256("example.test".toByteArray())

    private class Card(val persist: Boolean = true, val readbackFails: Boolean = false, val known: Boolean = true) {
        val server = FidoClientTest.PinServer(2)
        var deleted = false
        var changed = false
        var user: Cbor.Map? = null
        var writes = 0
        lateinit var channel: FidoChannel
    }

    private fun run(operation: ManagerOperation, card: Card): OperationResult<ManagerReport> {
        card.channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true)))
            6 -> card.server.respond(map!!, 4, null)
            10 -> when (map!![1].number().toInt()) {
                2 -> success(cborMap(3 to textMap("id" to "example.test".cbor()), 4 to rpHash.cbor(), 5 to 1.cbor()))
                4 -> {
                    if (card.readbackFails && card.writes > 0) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    if (card.deleted || !card.known) byteArrayOf(0x2e) else success(cborMap(
                        6 to (if (card.changed) card.user!! else FidoUser(userId, "old", "old").encode()),
                        7 to descriptor(id), 8 to cborMap(), 9 to 1.cbor()))
                }
                6 -> { card.writes++; if (card.persist) card.deleted = true; success() }
                7 -> { card.writes++; card.user = map[2].map()[3].map(); if (card.persist) card.changed = true; success() }
                else -> error("Unexpected CM command")
            }
            else -> error("Unexpected CTAP command")
        } }
        return OperationInput(operation, pin = "1234".toCharArray(), text = id.hex(),
            secondary = "updated", bytes = byteArrayOf(99)).use { input ->
            CardSession(card.channel).run { ManagerOperations().execute(it, input) }
        }
    }

    @Test fun acknowledgedButUnchangedDeletionFailsReadback() {
        val card = Card(persist = false)
        assertEquals(OperationResult.Failed(FailureCode.RECOVERY_REQUIRED, Effect.APPLIED, writeAccepted = true),
            run(ManagerOperation.FIDO_DELETE, card))
        assertEquals(1, card.writes)
    }
    @Test fun acknowledgedButUnchangedUserUpdateFailsReadback() {
        val card = Card(persist = false)
        assertEquals(OperationResult.Failed(FailureCode.RECOVERY_REQUIRED, Effect.APPLIED, writeAccepted = true),
            run(ManagerOperation.FIDO_UPDATE, card))
        assertEquals(1, card.writes)
    }
    @Test fun updateKeepsEnumeratedUserIdAndReadsNamesBack() {
        val card = Card()
        assertTrue(run(ManagerOperation.FIDO_UPDATE, card) is OperationResult.Completed)
        assertArrayEquals(userId, card.user!!["id"].bytes())
        assertEquals("updated", card.user!!["name"].text())
        assertEquals("updated", card.user!!["displayName"].text())
        assertEquals(1, card.server.tokenRequests)
        assertEquals(listOf(2L, 4L, 7L, 4L), card.channel.commands.filter { it.first == 10 }.map { it.second!![1].number() })
    }
    @Test fun deletionReadsBackWithSameToken() {
        val card = Card()
        assertTrue(run(ManagerOperation.FIDO_DELETE, card) is OperationResult.Completed)
        assertEquals(1, card.server.tokenRequests)
        assertEquals(listOf(2L, 4L, 6L, 4L), card.channel.commands.filter { it.first == 10 }.map { it.second!![1].number() })
    }
    @Test fun lostMutationReadbackDoesNotRetryAndKeepsAcceptedWrite() {
        for (operation in listOf(ManagerOperation.FIDO_DELETE, ManagerOperation.FIDO_UPDATE)) {
            val card = Card(readbackFails = true)
            assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.APPLIED,
                dispatch = Dispatch.RESPONSE_UNAVAILABLE, writeAccepted = true), run(operation, card))
            assertEquals(1, card.writes)
        }
    }
    @Test fun missingCredentialIsRejectedBeforeMutation() {
        for (operation in listOf(ManagerOperation.FIDO_DELETE, ManagerOperation.FIDO_UPDATE)) {
            val card = Card(known = false)
            assertEquals(FailureCode.INVALID_INPUT, (run(operation, card) as OperationResult.Failed).code)
            assertEquals(0, card.writes)
        }
    }
}
