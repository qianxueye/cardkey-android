package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.*
import org.junit.Test

internal class InventoryCard(val rp: String = "login.example.test", val id: ByteArray = byteArrayOf(21)) {
    val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private val ec = pair.public as ECPublicKey
    val publicKey = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(ec.w.affineX).cbor(), -3 to fixed32(ec.w.affineY).cbor())
    val relyingParty get() = RelyingParty(rp, "Synthetic RP", sha256(rp.toByteArray()))
    val credential get() = Credential(id, FidoUser(byteArrayOf(7), "alice", "Alice"), publicKey, 3)
    var counter = 0
    var flags = 5
    var returnedId = id
    var signedRp = rp
    var valid = true
    var count = 1
    var key: ByteArray? = ByteArray(32) { 9 }
    var blobReads = 0
    var blobWrites = 0
    var lostWrite = false
    var failReadback = false
    var blobData = LargeBlobs.encode(listOf(LargeBlobs.encrypt(ByteArray(32) { 9 }, "private".toByteArray())))
    var assertions = 0
    var pinError = false
    var lostAssertion = false
    var afterAssertion: (() -> Unit)? = null
    val hashes = mutableListOf<ByteArray>()
    val server = FidoClientTest.PinServer(2)
    fun channel(wire: CardWire = CardWire.CTAP) = ManagerTestChannel(wire) { code, parameters -> when (code) {
        4 -> success(info(mapOf("pinUvAuthToken" to true, "clientPin" to true, "credMgmt" to true, "alwaysUv" to true, "largeBlobs" to true),
            extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
        6 -> if (pinError && parameters!![2].number() == 9L) byteArrayOf(0x31) else server.respond(parameters!!,
            parameters[9]?.number()?.toInt() ?: 2, if (parameters[9]?.number() in listOf(4L, 16L)) null else rp)
        10 -> when (parameters!![1].number()) {
            2L -> success(cborMap(3 to textMap("id" to rp.cbor(), "name" to "Synthetic RP".cbor()), 4 to relyingParty.idHash.cbor(), 5 to 1.cbor()))
            4L -> { assertArrayEquals(relyingParty.idHash, parameters[2].map()[1].bytes()); success(cborMap(6 to credential.user.encode(), 7 to descriptor(id), 8 to publicKey, 9 to 1.cbor(), 10 to 3.cbor())) }
            else -> error("Unexpected CM")
        }
        2, 8 -> {
            assertions++
            if (lostAssertion) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            val hash = if (code == 2) parameters!![2].bytes().copyOf().also {
                assertEquals(rp, parameters[1].text()); assertArrayEquals(id, parameters[3].array().single().map()["id"].bytes())
                assertTrue(parameters[5].map()["up"].bool()); assertNotNull(parameters[6]); hashes += it
            } else hashes.last()
            val auth = sha256(signedRp.toByteArray()) + byteArrayOf(flags.toByte(), 0, 0, 0, counter.toByte())
            val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(auth + hash + if (valid) byteArrayOf() else byteArrayOf(1)); sign() }
            afterAssertion?.invoke()
            success(cborMap(1 to descriptor(returnedId), 2 to auth.cbor(), 3 to signature.cbor(), 5 to count.cbor(), 7 to key?.cbor()))
        }
        12 -> {
            if (parameters!![2] != null) {
                blobWrites++
                if (lostWrite) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                val offset = parameters[3].number().toInt()
                if (offset == 0) blobData = ByteArray(parameters[4].number().toInt())
                parameters[2].bytes().copyInto(blobData, offset)
                success()
            } else {
                blobReads++
                if (failReadback && blobWrites > 0) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                val offset = parameters[3].number().toInt()
                val length = parameters[1].number().toInt()
                success(cborMap(1 to blobData.copyOfRange(offset, minOf(offset + length, blobData.size)).cbor()))
            }
        }
        else -> error("Unexpected command $code")
    } }
}

class InventoryProofTest {
    private val inventory = FidoInventory()
    private val operations = ManagerOperations()
    private fun seed(card: InventoryCard) {
        OperationInput(ManagerOperation.FIDO_RPS).use { input -> val request = inventory.begin(input)
            inventory.settle(request, ManagerReport(emptyList(), relyingParties = listOf(card.relyingParty))) }
        inventory.selectRp(inventory.state.value.rps.single())
        OperationInput(ManagerOperation.FIDO_CREDENTIALS).use { input -> val request = inventory.begin(input)
            inventory.settle(request, ManagerReport(emptyList(), credentials = listOf(card.credential))) }
        inventory.selectCredential(inventory.state.value.credentials.single())
    }
    private fun run(card: InventoryCard, operation: ManagerOperation = ManagerOperation.FIDO_VERIFY_CREDENTIAL,
        pin: String = "1234"): OperationResult<ManagerReport> = OperationInput(operation, pin = pin.toCharArray(),
        inventoryGeneration = inventory.state.value.generation, assertionExtensions = AssertionExtensions(largeBlobKey = true)).use { input ->
        input.inventoryRequest = inventory.begin(input)
        CardSession(card.channel()).run { operations.execute(it, input) }.also { result ->
            if (result is OperationResult.Completed) inventory.settle(input.inventoryRequest!!, result.value)
        }
    }
    @Test fun typedEnumerationPreservesRealRpPublicKeyAndProtection() {
        val card = InventoryCard()
        assertTrue(run(card, ManagerOperation.FIDO_RPS) is OperationResult.Completed)
        inventory.selectRp(inventory.state.value.rps.single())
        assertTrue(run(card, ManagerOperation.FIDO_CREDENTIALS) is OperationResult.Completed)
        val selected = inventory.state.value.credentials.single()
        assertEquals(card.rp, selected.rp.id); assertEquals(3, selected.protection)
        assertEquals(card.publicKey, selected.value().publicKey)
        inventory.selectCredential(selected)
        assertTrue(run(card) is OperationResult.Completed)
    }
    @Test fun firstZeroAndNonzeroCountersAreAcceptedThenRegressionIsRejectedAcrossInventoryRefresh() {
        val card = InventoryCard()
        seed(card); assertTrue(run(card) is OperationResult.Completed)
        assertTrue(run(card) is OperationResult.Completed)
        card.counter = 8; assertTrue(run(card) is OperationResult.Completed)
        val second = InventoryCard("accounts.other.test", byteArrayOf(33))
        seed(second); second.counter = 19; assertTrue(run(second) is OperationResult.Completed)
        seed(card); card.counter = 7
        assertEquals(FailureCode.AUTHORIZATION_DENIED, (run(card) as OperationResult.Failed).code)
        assertFalse(inventory.state.value.blobReady)
        assertEquals(4, card.hashes.map { it.toList() }.distinct().size)
    }
    @Test fun signedWrongRpIdKeyHashUpUvAndDuplicateFailWithoutBlob() {
        val variants: List<(InventoryCard) -> Unit> = listOf({ it.signedRp = "other.test" }, { it.returnedId = byteArrayOf(99) },
            { it.valid = false }, { it.flags = 1 }, { it.flags = 4 }, { it.count = 2 })
        for (mutate in variants) {
            inventory.retire(); val card = InventoryCard(); seed(card); mutate(card)
            assertTrue(run(card) is OperationResult.Failed)
            assertFalse(inventory.state.value.blobReady); assertEquals(0, card.blobReads)
        }
        val card = InventoryCard(); seed(card)
        val wrong = InventoryCard(card.rp, card.id)
        assertTrue(run(wrong) is OperationResult.Failed)
    }
    @Test fun missingPinAndMalformedPublicKeyFailBeforePinToken() {
        val card = InventoryCard(); seed(card)
        assertThrows(CardFailure::class.java) { run(card, pin = "") }; assertEquals(0, card.server.tokenRequests)
        val request = inventory.begin(OperationInput(ManagerOperation.FIDO_CREDENTIALS))
        inventory.settle(request, ManagerReport(emptyList(), credentials = listOf(card.credential.copy(publicKey = cborMap()))))
        inventory.selectCredential(inventory.state.value.credentials.single())
        assertTrue(run(card) is OperationResult.Failed); assertEquals(0, card.server.tokenRequests)
    }
    @Test fun keyRequiresVerifiedProofAndOneAcceptedUseWithFreshMatchingKey() {
        val card = InventoryCard(); seed(card)
        assertThrows(CardFailure::class.java) { run(card, ManagerOperation.FIDO_BLOB_READ) }
        assertEquals(0, card.assertions)
        assertTrue(run(card) is OperationResult.Completed)
        val report = (run(card, ManagerOperation.FIDO_BLOB_READ) as OperationResult.Completed).value
        assertEquals(card.rp, report.rows.first().value); assertArrayEquals("private".toByteArray(), report.export)
        assertNull(report.secrets); assertTrue(report.protectedExport)
        assertThrows(CardFailure::class.java) { run(card, ManagerOperation.FIDO_BLOB_READ) }
        assertEquals(1, card.blobReads)
        assertTrue(run(card) is OperationResult.Completed); card.key = ByteArray(32) { 3 }
        assertTrue(run(card, ManagerOperation.FIDO_BLOB_READ) is OperationResult.Failed)
        assertEquals(1, card.blobReads); assertFalse(inventory.state.value.blobReady)
    }
    @Test fun absentKeyRetirementAndStaleSelectionCannotReacquire() {
        val card = InventoryCard(); seed(card); card.key = null
        assertTrue(run(card) is OperationResult.Completed); assertFalse(inventory.state.value.blobReady)
        assertThrows(CardFailure::class.java) { run(card, ManagerOperation.FIDO_BLOB_READ) }
        card.key = ByteArray(32) { 9 }; assertTrue(run(card) is OperationResult.Completed)
        val stale = OperationInput(ManagerOperation.FIDO_BLOB_READ, pin = "1234".toCharArray(), inventoryGeneration = inventory.state.value.generation)
        inventory.selectCredential(inventory.state.value.credential!!)
        assertThrows(CardFailure::class.java) { inventory.begin(stale) }; stale.close()
        assertEquals(0, card.blobReads)
    }
    @Test fun cancellationAfterProofCannotPublishKeyOrDoBlobIoAndLostResponseKeepsUnknown() {
        val card = InventoryCard(); seed(card); assertTrue(run(card) is OperationResult.Completed)
        card.afterAssertion = { inventory.retire() }
        assertEquals(FailureCode.CANCELLED, (run(card, ManagerOperation.FIDO_BLOB_READ) as OperationResult.Failed).code)
        assertEquals(0, card.blobReads); assertFalse(inventory.state.value.blobReady)
        card.afterAssertion = null; seed(card); card.lostAssertion = true
        val failure = run(card) as OperationResult.Failed
        assertEquals(Effect.UNKNOWN, failure.effect); assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure.dispatch)
    }
    @Test fun emptyPinDoesNotConsumePendingKeyAndFreshMissingUvCannotReachBlob() {
        val card = InventoryCard(); seed(card); assertTrue(run(card) is OperationResult.Completed)
        val before = card.assertions
        assertThrows(CardFailure::class.java) { run(card, ManagerOperation.FIDO_BLOB_READ, pin = "") }
        assertTrue(inventory.state.value.blobReady); assertEquals(before, card.assertions)
        card.flags = 1
        val failure = run(card, ManagerOperation.FIDO_BLOB_READ) as OperationResult.Failed
        assertEquals(Effect.APPLIED, failure.effect); assertTrue(failure.writeAccepted)
        assertEquals(0, card.blobReads); assertFalse(inventory.state.value.blobReady)
    }
    @Test fun metadataChangesAndArrayCopiesDoNotResetCounterOrMutateSelection() {
        val card = InventoryCard(); seed(card); card.counter = 12; assertTrue(run(card) is OperationResult.Completed)
        val rp = card.relyingParty.copy(name = "new label")
        inventory.begin(OperationInput(ManagerOperation.FIDO_RPS)).use {
            inventory.settle(it, ManagerReport(emptyList(), relyingParties = listOf(rp)))
        }
        inventory.selectRp(inventory.state.value.rps.single())
        val source = card.credential.copy(protection = 2)
        inventory.begin(OperationInput(ManagerOperation.FIDO_CREDENTIALS)).use {
            inventory.settle(it, ManagerReport(emptyList(), credentials = listOf(source)))
        }
        val selected = inventory.state.value.credentials.single()
        val output = selected.value(); output.id.fill(0); output.publicKey[-2].bytes().fill(0)
        rp.idHash.fill(0)
        inventory.selectCredential(selected)
        card.counter = 11
        assertEquals(FailureCode.AUTHORIZATION_DENIED, (run(card) as OperationResult.Failed).code)
        assertArrayEquals(card.id, selected.value().id)
    }
    @Test fun inconsistentEnumeratedRpHashFailsBeforePinAndSignature() {
        val card = InventoryCard()
        inventory.begin(OperationInput(ManagerOperation.FIDO_RPS)).use {
            inventory.settle(it, ManagerReport(emptyList(), relyingParties = listOf(card.relyingParty.copy(idHash = ByteArray(32)))))
        }
        inventory.selectRp(inventory.state.value.rps.single())
        inventory.begin(OperationInput(ManagerOperation.FIDO_CREDENTIALS)).use {
            inventory.settle(it, ManagerReport(emptyList(), credentials = listOf(card.credential)))
        }
        inventory.selectCredential(inventory.state.value.credentials.single())
        val failure = run(card) as OperationResult.Failed
        assertEquals(FailureCode.MALFORMED_RESPONSE, failure.code); assertEquals(Effect.NONE, failure.effect)
        assertEquals(0, card.server.tokenRequests); assertEquals(0, card.assertions)
    }
    @Test fun selectedProofConsentOnlyIncludesTheExtensionItActuallyRequests() {
        for (operation in listOf(ManagerOperation.FIDO_ASSERT, ManagerOperation.FIDO_VERIFY_CREDENTIAL)) {
            OperationInput(operation, pin = "1234".toCharArray(),
                assertionExtensions = AssertionExtensions(credentialBlob = true, hmacSalts = ByteArray(32))).use {
                val messages = ConfirmationPolicy.messages(it)
                assertTrue(messages.contains(cc.cardkey.R.string.confirm_authenticate))
                assertEquals(operation == ManagerOperation.FIDO_ASSERT, messages.contains(cc.cardkey.R.string.confirm_secretRead))
            }
        }
        OperationInput(ManagerOperation.FIDO_VERIFY_CREDENTIAL, pin = "1234".toCharArray(),
            assertionExtensions = AssertionExtensions(largeBlobKey = true)).use {
            assertTrue(ConfirmationPolicy.messages(it).contains(cc.cardkey.R.string.confirm_secretRead))
        }
    }
    @Test fun wrongPinRetainsRetryEffectAndNeverAsserts() {
        val card = InventoryCard(); seed(card); card.pinError = true
        val failure = run(card) as OperationResult.Failed
        assertEquals(FailureCode.PIN_INVALID, failure.code); assertEquals(Effect.APPLIED, failure.effect)
        assertEquals(0, card.assertions); assertFalse(inventory.state.value.blobReady)
    }
}
