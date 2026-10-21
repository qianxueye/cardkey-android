package cc.cardkey.ui

import android.nfc.NfcAdapter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.net.Uri
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.signing.SignatureFormat
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.transport.UsbReaderDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowIsoDep
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Implements(IsoDep::class)
class InventoryIsoDepShadow : ShadowIsoDep() {
    companion object {
        var channel: CardChannel? = null
        @JvmStatic @Implementation fun get(@Suppress("UNUSED_PARAMETER") tag: Tag): IsoDep = newInstance()
    }
    @Implementation override fun transceive(data: ByteArray): ByteArray = requireNotNull(channel).transmit(data)
    @Implementation override fun getMaxTransceiveLength() = 261
}

internal fun seedInventory(inventory: FidoInventory, card: InventoryCard) {
    inventory.begin(OperationInput(ManagerOperation.FIDO_RPS)).use { inventory.settle(it,
        ManagerReport(emptyList(), relyingParties = listOf(card.relyingParty))) }
    inventory.selectRp(inventory.state.value.rps.single())
    inventory.begin(OperationInput(ManagerOperation.FIDO_CREDENTIALS)).use { inventory.settle(it,
        ManagerReport(emptyList(), credentials = listOf(card.credential))) }
    inventory.selectCredential(inventory.state.value.credentials.single())
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [InventoryIsoDepShadow::class])
class InventoryViewModelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var model: ManagerViewModel
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        model = ManagerViewModel(app)
    }
    @After fun teardown() { model.cancel(); InventoryIsoDepShadow.channel = null; Dispatchers.resetMain() }
    private fun await(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!test() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(test())
    }
    private fun input(operation: ManagerOperation) = OperationInput(operation, pin = "1234".toCharArray(),
        inventoryGeneration = model.inventory.state.value.generation, assertionExtensions = AssertionExtensions(largeBlobKey = true))
    private fun dispatch(card: InventoryCard, operation: ManagerOperation = ManagerOperation.FIDO_VERIFY_CREDENTIAL): OperationInput {
        val input = input(operation)
        model.submit(input)
        assertTrue(model.state.value.awaitingNfc)
        InventoryIsoDepShadow.channel = card.channel(CardWire.APDU)
        model.onTag(Shadow.newInstanceOf(Tag::class.java), requireNotNull(model.pendingNfcOwner))
        await { !model.state.value.busy }
        return input
    }
    @Test fun completeInventoryOwnsOneLeaseAndRetiresPinAfterFreshConnection() {
        val card = InventoryCard()
        val input = dispatch(card, ManagerOperation.FIDO_INVENTORY)
        assertTrue(model.state.value.result is OperationResult.Completed)
        assertEquals(InventoryLoad.LOADED, model.inventory.state.value.load)
        assertEquals(card.rp, model.inventory.state.value.credentials.single().rp.id)
        assertEquals(1, card.server.tokenRequests)
        assertArrayEquals(CharArray(4), input.pin)
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }
    @Test fun finalAttemptHoldsLeaseAndDeclineOrBackgroundNeverAuthenticates() {
        for (retirement in listOf("decline", "background", "permission")) {
            val card = InventoryCard().apply { remainingAttempts = 1 }
            val input = input(ManagerOperation.FIDO_INVENTORY)
            model.submit(input)
            InventoryIsoDepShadow.channel = card.channel(CardWire.APDU)
            model.onTag(Shadow.newInstanceOf(Tag::class.java), requireNotNull(model.pendingNfcOwner))
            await { model.state.value.fidoFinalAttempt }
            assertNull(CardOperationGate.tryAcquire())
            assertEquals(0, card.server.tokenRequests)
            when (retirement) {
                "background" -> model.pauseForActivity(false)
                "permission" -> { model.permissionPending(true); model.approveLastRetry(true) }
                else -> model.approveLastRetry(false)
            }
            await { !model.state.value.busy }
            assertEquals(0, card.server.tokenRequests)
            assertArrayEquals(CharArray(4), input.pin)
            assertFalse(model.inventory.state.value.load == InventoryLoad.LOADED)
            requireNotNull(CardOperationGate.tryAcquire()).close()
        }
    }
    @Test fun finalAttemptApprovalPermitsOneAuthAndLateDocumentResultCannotPublish() {
        val card = InventoryCard().apply { remainingAttempts = 1 }
        val input = input(ManagerOperation.FIDO_INVENTORY)
        model.submit(input)
        InventoryIsoDepShadow.channel = card.channel(CardWire.APDU)
        model.onTag(Shadow.newInstanceOf(Tag::class.java), requireNotNull(model.pendingNfcOwner))
        await { model.state.value.fidoFinalAttempt }
        model.approveLastRetry(true)
        await { !model.state.value.busy }
        assertEquals(1, card.server.tokenRequests)
        assertEquals(InventoryLoad.LOADED, model.inventory.state.value.load)
        assertArrayEquals(CharArray(4), input.pin)
        val document = model.beginDocument()
        model.cancel()
        model.documentResult(document, cc.cardkey.R.string.document_saved)
        assertNull(model.state.value.documentMessage)
    }
    @Test fun actualPendingNfcUsesEnumeratedTupleAndFreshProofRejectsSubstitutedCard() {
        val card = InventoryCard()
        dispatch(card, ManagerOperation.FIDO_RPS)
        model.selectInventoryRp(model.inventory.state.value.generation, model.inventory.state.value.rps.single())
        dispatch(card, ManagerOperation.FIDO_CREDENTIALS)
        model.selectInventoryCredential(model.inventory.state.value.generation, model.inventory.state.value.credentials.single())
        val proof = dispatch(card)
        assertTrue(model.state.value.result is OperationResult.Completed)
        assertTrue(proof.pin.all { it == '\u0000' }); assertTrue(model.inventory.state.value.blobReady)
        val replacement = InventoryCard(card.rp, card.id.copyOf())
        dispatch(replacement, ManagerOperation.FIDO_BLOB_READ)
        assertEquals(FailureCode.MALFORMED_RESPONSE, (model.state.value.result as OperationResult.Failed).code)
        assertEquals(0, replacement.blobReads); assertFalse(model.inventory.state.value.blobReady)
        assertTrue(model.state.value.requiresInspection)
    }
    @Test fun staleConfirmationAndPendingNfcCancellationWipeWithoutIoOrReacquisition() {
        val card = InventoryCard(); seedInventory(model.inventory, card)
        val stale = input(ManagerOperation.FIDO_VERIFY_CREDENTIAL)
        val confirmation = PendingOperation(stale)
        val a = model.inventory.state.value.credential!!
        val oldGeneration = model.inventory.state.value.generation
        model.selectInventoryCredential(model.inventory.state.value.generation, a); model.selectInventoryCredential(model.inventory.state.value.generation, a)
        val currentGeneration = model.inventory.state.value.generation
        model.selectInventoryCredential(oldGeneration, a)
        assertEquals(currentGeneration, model.inventory.state.value.generation)
        assertNull(model.requestInventoryFile(oldGeneration))
        confirmation.dispatch(model::submit)
        assertFalse(model.state.value.busy); assertTrue(stale.pin.all { it == '\u0000' })
        val current = input(ManagerOperation.FIDO_VERIFY_CREDENTIAL)
        model.submit(current); assertTrue(model.state.value.awaitingNfc)
        val owner = requireNotNull(model.pendingNfcOwner)
        model.cancel(); assertTrue(current.pin.all { it == '\u0000' })
        InventoryIsoDepShadow.channel = card.channel(CardWire.APDU)
        assertFalse(model.onTag(Shadow.newInstanceOf(Tag::class.java), owner))
        assertEquals(0, card.assertions); assertFalse(model.state.value.busy)
    }
    @Test fun pickerPauseRetiresKeyThenExplicitProofEnablesExactlyOneWriteOfBoundBytes() {
        val card = InventoryCard(); seedInventory(model.inventory, card); dispatch(card)
        val retained = ReflectionHelpers.getField<ByteArray>(model.inventory, "key")
        val id = model.requestInventoryFile(model.inventory.state.value.generation)!!
        assertTrue(retained.all { it == 0.toByte() }); assertFalse(model.inventory.state.value.blobReady)
        model.pauseForActivity(false)
        val raw = "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----".toByteArray()
        val uri = Uri.parse("content://inventory-file/raw")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(raw))
        model.receiveInventoryFile(id, uri)
        await { model.inventory.state.value.fileSize != null }
        val file = ReflectionHelpers.getField<ByteArray>(model.inventory, "file")
        assertArrayEquals(raw, file)
        val early = input(ManagerOperation.FIDO_BLOB_WRITE)
        model.submit(early); assertFalse(model.state.value.busy); assertEquals(0, card.blobWrites)
        dispatch(card)
        assertTrue(model.inventory.state.value.blobReady); assertArrayEquals(raw, file)
        val written = dispatch(card, ManagerOperation.FIDO_BLOB_WRITE)
        assertTrue(model.state.value.result is OperationResult.Completed)
        assertEquals(1, card.blobWrites)
        assertArrayEquals(raw, LargeBlobs.decrypt(ByteArray(32) { 9 }, LargeBlobs.decode(card.blobData).single()))
        assertTrue(file.all { it == 0.toByte() }); assertTrue(written.pin.all { it == '\u0000' })
        assertNull(model.inventory.state.value.fileSize); assertFalse(model.inventory.state.value.blobReady)
        model.submit(input(ManagerOperation.FIDO_BLOB_WRITE)); assertFalse(model.state.value.busy)
        assertEquals(1, card.blobWrites)
    }
    @Test fun explicitRecognitionAfterPickerPreservesFileButRequiresFreshProofBeforeOneWrite() {
        val card = InventoryCard()
        seedInventory(model.inventory, card)
        model.inventoryAction(ManagerOperation.FIDO_BLOB_WRITE)
        dispatch(card)
        val initialKey = ReflectionHelpers.getField<ByteArray>(model.inventory, "key")
        val id = requireNotNull(model.requestInventoryFile(model.inventory.state.value.generation))
        model.pauseForActivity(false)
        assertNull(model.state.value.recognition)
        assertArrayEquals(ByteArray(initialKey.size), initialKey)
        val raw = byteArrayOf(7, 8, 9)
        val uri = Uri.parse("content://inventory-file/recognition.raw")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(raw))
        model.receiveInventoryFile(id, uri)
        await { model.inventory.state.value.fileSize != null }
        val file = ReflectionHelpers.getField<ByteArray>(model.inventory, "file")
        val credential = requireNotNull(model.inventory.state.value.credential)

        repeat(2) {
            val retainedKey = ReflectionHelpers.getField<ByteArray?>(model.inventory, "key")
            val generation = model.inventory.state.value.generation
            val assertions = card.assertions
            val tokens = card.server.tokenRequests
            val channel = card.channel(CardWire.APDU)
            model.submit(OperationInput(ManagerOperation.FIDO_INFO,
                inventoryGeneration = generation, inventorySelection = credential))
            assertEquals(generation + 1, model.inventory.state.value.generation)
            assertSame(credential, model.inventory.state.value.credential)
            assertEquals(ManagerOperation.FIDO_BLOB_WRITE, model.inventory.state.value.action)
            assertSame(file, ReflectionHelpers.getField<ByteArray>(model.inventory, "file"))
            retainedKey?.let { assertArrayEquals(ByteArray(it.size), it) }
            assertFalse(model.inventory.state.value.blobReady)
            InventoryIsoDepShadow.channel = channel
            model.onTag(Shadow.newInstanceOf(Tag::class.java), requireNotNull(model.pendingNfcOwner))
            await { !model.state.value.busy }
            assertTrue(model.state.value.canEnterFidoSecrets)
            assertEquals(listOf(4), channel.commands.map { it.first })
            assertEquals(assertions, card.assertions)
            assertEquals(tokens, card.server.tokenRequests)
            assertEquals(0, card.blobWrites)
            assertArrayEquals(raw, file)

            val withoutProof = input(ManagerOperation.FIDO_BLOB_WRITE)
            model.submit(withoutProof)
            assertFalse(model.state.value.busy)
            assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
            assertEquals(0, card.blobWrites)
            assertArrayEquals(raw, file)
            dispatch(card)
            assertTrue(model.inventory.state.value.blobReady)
            assertArrayEquals(raw, file)
        }
        val written = dispatch(card, ManagerOperation.FIDO_BLOB_WRITE)
        assertTrue(model.state.value.result is OperationResult.Completed)
        assertEquals(1, card.blobWrites)
        assertArrayEquals(raw, LargeBlobs.decrypt(ByteArray(32) { 9 }, LargeBlobs.decode(card.blobData).single()))
        assertArrayEquals(ByteArray(file.size), file)
        assertArrayEquals(CharArray(4), written.pin)
        assertNull(model.inventory.state.value.fileSize)
        assertFalse(model.inventory.state.value.blobReady)
        model.submit(input(ManagerOperation.FIDO_BLOB_WRITE))
        assertFalse(model.state.value.busy)
        assertEquals(1, card.blobWrites)
    }

    @Test fun recognitionFileExceptionRejectsOtherOwnersSecretsAndRuntimeBlockers() {
        for (case in listOf("command", "action", "missingCredential", "credential", "secret", "extension",
                "permission", "permissionLost", "reader", "uncertainty", "save", "inventoryExport")) {
            model = ManagerViewModel(app)
            val card = InventoryCard()
            val usbReader = if (case in listOf("permissionLost", "reader")) {
                val device = Shadow.newInstanceOf(UsbDevice::class.java)
                ReflectionHelpers.setField(device, "mName", "blob-recognition-$case")
                shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
                UsbReaderDescriptor(device, 1, CardWire.APDU).also(model::selectReader)
            } else null
            seedInventory(model.inventory, card)
            model.inventoryAction(ManagerOperation.FIDO_BLOB_WRITE)
            val id = requireNotNull(model.requestInventoryFile(model.inventory.state.value.generation))
            val generation = requireNotNull(model.inventory.receiveFile(id))
            val file = byteArrayOf(4, 5, 6)
            assertTrue(model.inventory.finishFile(id, generation, file))
            when (case) {
                "action" -> model.inventoryAction(ManagerOperation.FIDO_BLOB_READ)
                "permission" -> model.permissionPending(true)
                "permissionLost" -> shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(requireNotNull(usbReader).device, false)
                "reader" -> UsbAuthenticatorReader.invalidate(requireNotNull(usbReader))
                "uncertainty" -> {
                    val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
                    state.value = state.value.copy(requiresInspection = true)
                }
                "save" -> assertTrue(model.signingFiles.requestSave(ManagerReport(emptyList(), export = byteArrayOf(9),
                    fileName = SignatureFormat.RAW.fileName, signatureFormat = SignatureFormat.RAW), ManagerOperation.PIV_SIGN))
                "inventoryExport" -> {
                    val report = ManagerReport(emptyList(), export = byteArrayOf(9), protectedExport = true,
                        inventoryGeneration = model.inventory.state.value.generation)
                    val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
                    state.value = state.value.copy(result = OperationResult.Completed(report, Effect.NONE))
                    assertNotNull(model.requestInventoryExport(report))
                }
            }
            val current = requireNotNull(model.inventory.state.value.credential)
            model.submit(OperationInput(if (case == "command") ManagerOperation.PIV_INFO else ManagerOperation.FIDO_INFO,
                inventoryGeneration = model.inventory.state.value.generation,
                inventorySelection = when (case) {
                    "missingCredential" -> null
                    "credential" -> InventoryCredential(current.rp, InventoryCard(id = byteArrayOf(88)).credential)
                    else -> current
                },
                pin = if (case == "secret") "1234".toCharArray() else charArrayOf(),
                assertionExtensions = AssertionExtensions(hmacSalts = if (case == "extension") ByteArray(32) { 1 } else null)))
            assertNull("File survived $case", model.inventory.state.value.fileSize)
            assertArrayEquals(ByteArray(file.size), file)
            assertFalse(model.inventory.state.value.blobReady)
            model.cancel()
            await { !model.state.value.busy }
        }
    }

    @Test fun staleRecognitionGenerationCannotConsumeNewerPreparedFileOrRestoreOldCallback() {
        val card = InventoryCard()
        seedInventory(model.inventory, card)
        model.inventoryAction(ManagerOperation.FIDO_BLOB_WRITE)
        val oldId = requireNotNull(model.requestInventoryFile(model.inventory.state.value.generation))
        val stale = OperationInput(ManagerOperation.FIDO_INFO, inventoryGeneration = model.inventory.state.value.generation,
            inventorySelection = model.inventory.state.value.credential)
        val newId = requireNotNull(model.requestInventoryFile(model.inventory.state.value.generation))
        val generation = requireNotNull(model.inventory.receiveFile(newId))
        val file = byteArrayOf(1, 2)
        assertTrue(model.inventory.finishFile(newId, generation, file))
        model.submit(stale)
        assertEquals(FailureCode.CANCELLED, (model.state.value.result as OperationResult.Failed).code)
        assertFalse(model.state.value.busy)
        assertSame(file, ReflectionHelpers.getField<ByteArray>(model.inventory, "file"))
        assertArrayEquals(byteArrayOf(1, 2), file)
        var reads = 0
        val uri = Uri.parse("content://inventory-file/old-recognition")
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(9)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(bytes, offset, length)
            }
        })
        model.receiveInventoryFile(oldId, uri)
        assertEquals(0, reads)
        assertSame(file, ReflectionHelpers.getField<ByteArray>(model.inventory, "file"))
    }

    @Test fun recognitionDoesNotPreservePendingFilePickerCallback() {
        val card = InventoryCard()
        seedInventory(model.inventory, card)
        model.inventoryAction(ManagerOperation.FIDO_BLOB_WRITE)
        val id = requireNotNull(model.requestInventoryFile(model.inventory.state.value.generation))
        model.submit(OperationInput(ManagerOperation.FIDO_INFO,
            inventoryGeneration = model.inventory.state.value.generation,
            inventorySelection = model.inventory.state.value.credential))
        assertNull(model.inventory.fileRequestId)
        var reads = 0
        val uri = Uri.parse("content://inventory-file/pending-recognition")
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(9)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(bytes, offset, length)
            }
        })
        model.receiveInventoryFile(id, uri)
        assertEquals(0, reads)
        assertNull(model.inventory.state.value.fileSize)
        assertFalse(model.inventory.state.value.blobReady)
        assertEquals(0, card.assertions)
        assertEquals(0, card.blobWrites)
    }

    @Test fun preconnectionFailureWipesTransferredKeyAndFile() {
        val card = InventoryCard(); seedInventory(model.inventory, card); dispatch(card)
        val expected = ReflectionHelpers.getField<ByteArray>(model.inventory, "key")
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(false)
        model.submit(input(ManagerOperation.FIDO_BLOB_READ))
        assertTrue(expected.all { it == 0.toByte() }); assertFalse(model.inventory.state.value.blobReady)
        assertEquals(FailureCode.NFC_DISABLED, (model.state.value.result as OperationResult.Failed).code)
    }
    @Test fun cancelDuringAssertionKeepsAppliedEffectAndNoLateKeyOrOutput() {
        val card = InventoryCard(); seedInventory(model.inventory, card)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        card.afterAssertion = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        model.submit(input(ManagerOperation.FIDO_VERIFY_CREDENTIAL))
        InventoryIsoDepShadow.channel = card.channel(CardWire.APDU)
        model.onTag(Shadow.newInstanceOf(Tag::class.java), requireNotNull(model.pendingNfcOwner))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        model.cancel(); assertTrue(model.state.value.busy)
        release.countDown(); await { !model.state.value.busy }
        val failure = model.state.value.result as OperationResult.Failed
        assertEquals(Effect.APPLIED, failure.effect); assertTrue(failure.writeAccepted)
        assertTrue(model.state.value.requiresInspection); assertFalse(model.inventory.state.value.blobReady)
        assertEquals(1, card.assertions)
    }
    @Test fun lostBlobWriteAndAcceptedWriteReadbackFailureKeepEffectsAndNeverReplay() {
        for (lost in listOf(true, false)) {
            model = ManagerViewModel(app)
            val card = InventoryCard(); seedInventory(model.inventory, card)
            val id = model.requestInventoryFile(model.inventory.state.value.generation)!!
            val generation = model.inventory.receiveFile(id)!!
            model.inventory.finishFile(id, generation, byteArrayOf(11, 22))
            dispatch(card)
            card.lostWrite = lost; card.failReadback = !lost
            dispatch(card, ManagerOperation.FIDO_BLOB_WRITE)
            val failure = model.state.value.result as OperationResult.Failed
            assertEquals(if (lost) Effect.UNKNOWN else Effect.APPLIED, failure.effect)
            assertTrue(failure.writeAccepted); assertTrue(model.state.value.requiresInspection)
            assertEquals(1, card.blobWrites); assertFalse(model.inventory.state.value.blobReady)
            model.cancel()
        }
    }
    @Test fun boundedFileReaderRejectsOversizeReadAndCloseFailuresAndWipesTemporaryBuffers() {
        for (mode in listOf("empty", "limit", "oversize", "read", "close")) {
            seedInventory(model.inventory, InventoryCard())
            val id = model.requestInventoryFile(model.inventory.state.value.generation)!!
            val size = when (mode) { "empty" -> 0; "limit" -> 65536; "oversize" -> 65537; else -> 8 }
            var temporary: ByteArray? = null
            val uri = Uri.parse("content://inventory-file/$mode")
            shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(ByteArray(size) { 19 }) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    temporary = bytes
                    if (mode == "read") { bytes[0] = 19; throw java.io.IOException() }
                    return super.read(bytes, offset, length)
                }
                override fun close() { if (mode == "close") throw java.io.IOException() }
            })
            model.receiveInventoryFile(id, uri)
            await { ReflectionHelpers.getField<Any?>(model.inventory, "fileRequest") == null }
            assertTrue(requireNotNull(temporary).all { it == 0.toByte() })
            if (mode in listOf("empty", "limit")) assertEquals(size, model.inventory.state.value.fileSize)
            else assertNull(model.inventory.state.value.fileSize)
            model.cancel()
        }
    }
    @Test fun lateReadAfterCancellationCannotPublishAndWipesReadBuffer() {
        seedInventory(model.inventory, InventoryCard())
        val id = model.requestInventoryFile(model.inventory.state.value.generation)!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var temporary: ByteArray? = null
        val closed = CountDownLatch(1)
        val uri = Uri.parse("content://inventory-file/cancelled-read")
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(7, 8)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                temporary = bytes; entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
            override fun close() { closed.countDown() }
        })
        model.receiveInventoryFile(id, uri)
        assertTrue(entered.await(5, TimeUnit.SECONDS)); model.cancel(); release.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        await { temporary?.all { it == 0.toByte() } == true }
        assertNull(model.inventory.state.value.fileSize); assertNull(model.inventory.state.value.credential)
    }
    @Test fun queuedOldTagCannotConsumeReplacementPendingRequest() {
        val card = InventoryCard(); seedInventory(model.inventory, card)
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        try {
            val old = input(ManagerOperation.FIDO_VERIFY_CREDENTIAL)
            model.submit(old)
            val oldOwner = requireNotNull(model.pendingNfcOwner)
            model.onTag(Shadow.newInstanceOf(Tag::class.java), requireNotNull(model.pendingNfcOwner))
            model.cancel()
            assertTrue(old.pin.all { it == '\u0000' })
            seedInventory(model.inventory, card)
            model.submit(input(ManagerOperation.FIDO_VERIFY_CREDENTIAL))
            InventoryIsoDepShadow.channel = card.channel(CardWire.APDU)
            assertFalse(model.onTag(Shadow.newInstanceOf(Tag::class.java), oldOwner))
            scheduler.runCurrent()
            assertTrue("The old tag callback must leave the replacement waiting for its own tag", model.state.value.awaitingNfc)
            assertEquals(0, card.assertions)
            model.onTag(Shadow.newInstanceOf(Tag::class.java), requireNotNull(model.pendingNfcOwner))
            await { scheduler.runCurrent(); !model.state.value.busy }
            assertTrue(model.state.value.result is OperationResult.Completed)
            assertEquals(1, card.assertions)
        } finally { model.cancel(); await { scheduler.runCurrent(); !model.state.value.busy } }
    }
    @Test fun unrelatedActionRetiresPendingReadAndLoadedPlaintext() {
        val card = InventoryCard(); seedInventory(model.inventory, card)
        val id = model.requestInventoryFile(model.inventory.state.value.generation)!!
        model.inventoryAction(ManagerOperation.FIDO_INFO)
        model.inventoryAction(ManagerOperation.FIDO_BLOB_WRITE)
        var reads = 0
        val uri = Uri.parse("content://inventory-file/stale")
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return super.read(bytes, offset, length) }
        })
        model.receiveInventoryFile(id, uri)
        assertEquals(0, reads); assertNull(model.inventory.state.value.fileSize)
        val next = model.requestInventoryFile(model.inventory.state.value.generation)!!
        val generation = model.inventory.receiveFile(next)!!
        val plain = byteArrayOf(3, 4)
        model.inventory.finishFile(next, generation, plain)
        model.pauseForActivity(false)
        assertTrue(plain.all { it == 0.toByte() }); assertNull(model.inventory.state.value.credential)
    }
}
