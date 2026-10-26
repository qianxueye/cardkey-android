package cc.cardkey.ui

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.net.Uri
import androidx.lifecycle.viewModelScope
import android.nfc.Tag
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.protocol.piv.PivTlv
import cc.cardkey.signing.SignatureFormat
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.transport.UsbReaderDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ManagerRecognitionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var readerNumber = 0

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
    }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun reader(name: String = "recognition-reader-${++readerNumber}", wire: CardWire = CardWire.CTAP): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", name)
        ReflectionHelpers.setField(device, "mConfigurations", emptyArray<UsbConfiguration>())
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        return UsbReaderDescriptor(device, 1, wire)
    }

    private fun start(model: ManagerViewModel, operation: ManagerOperation, connect: () -> CardChannel) {
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        state.value = state.value.copy(busy = true, operation = operation, result = null)
        ReflectionHelpers.setField(model, "cancelled", false)
        val run = ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java)
        run.isAccessible = true
        run.invoke(model, OperationInput(operation), connect)
    }

    private fun awaitResult(model: ManagerViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.state.value.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(model.state.value.busy)
        assertNotNull(model.state.value.result)
    }

    private fun fido(wire: CardWire = CardWire.CTAP) = ManagerTestChannel(wire) { command, _ ->
        assertEquals(4, command)
        success(info())
    }

    private fun recognizeFido(model: ManagerViewModel, wire: CardWire = CardWire.CTAP) {
        val channel = fido(wire)
        start(model, ManagerOperation.FIDO_INFO) { channel }
        awaitResult(model)
        assertTrue(model.state.value.result is OperationResult.Completed)
        assertEquals(CardFamily.FIDO2, model.state.value.recognition?.family)
        assertTrue(channel.closed)
        assertEquals(listOf(4), channel.commands.map { it.first })
    }

    private class PivInfoChannel(override val medium: Medium = Medium.CONTACT) : CardChannel {
        override val maxCommandBytes = 261
        val commands = mutableListOf<Int>()
        var closed = false
        override fun transmit(command: ByteArray): ByteArray {
            val instruction = command[1].toInt() and 255
            commands += instruction
            val response = when (instruction) {
                0xa4 -> byteArrayOf()
                0xfd -> byteArrayOf(1, 2, 3)
                0xf8 -> byteArrayOf(0, 0, 0, 7)
                0xf7 -> when (command[3].toInt() and 255) {
                    0x9b -> PivTlv.encode(1, byteArrayOf(3)) + PivTlv.encode(5, byteArrayOf(0))
                    0x80, 0x81 -> PivTlv.encode(5, byteArrayOf(0)) + PivTlv.encode(6, byteArrayOf(3, 3))
                    else -> error("Unexpected metadata read")
                }
                else -> error("Recognition must not authenticate or mutate")
            }
            return response + byteArrayOf(0x90.toByte(), 0)
        }
        override fun close() { closed = true }
    }

    @Test fun readerCapabilityAndOldReportCannotCreateRecognition() {
        val selected = reader()
        val model = ManagerViewModel(app)
        model.selectReader(selected)
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        state.value = state.value.copy(ctap2Reader = selected.routeKey, operation = ManagerOperation.FIDO_INFO)
        val finish = ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java)
        finish.isAccessible = true
        finish.invoke(model, OperationResult.Completed(ManagerReport(listOf(
            ResultRow("支持的协议", "FIDO_2_1"), ResultRow("AAGUID", "00000000000000000000000000000000"))), Effect.NONE))
        assertTrue(model.state.value.supportsFidoReset)
        assertNull(model.state.value.recognition)
        assertFalse(model.state.value.canEnterFidoSecrets)
        assertFalse(model.state.value.canEnterPivSecrets)
        model.selectReader(null)
        assertFalse(model.state.value.canEnterFidoSecrets)
        assertFalse(model.state.value.canEnterPivSecrets)
    }

    @Test fun successfulExplicitFidoReadEnablesOnlyFidoSecrets() {
        val model = ManagerViewModel(app)
        val selected = reader()
        model.selectReader(selected)
        recognizeFido(model)
        assertEquals(selected.routeKey, model.state.value.recognition?.route)
        assertTrue(model.state.value.canEnterFidoSecrets)
        assertFalse(model.state.value.canEnterPivSecrets)
    }

    @Test fun successfulPivReadUsesOnlyPublicCommandsAndReplacesPriorFamily() {
        val model = ManagerViewModel(app)
        model.selectReader(reader(wire = CardWire.APDU))
        recognizeFido(model, CardWire.APDU)
        val channel = PivInfoChannel()
        start(model, ManagerOperation.PIV_INFO) { channel }
        awaitResult(model)
        assertTrue(channel.closed)
        assertEquals(listOf(0xa4, 0xfd, 0xf8, 0xf7, 0xf7, 0xf7), channel.commands)
        assertEquals(CardFamily.PIV, model.state.value.recognition?.family)
        assertFalse(model.state.value.canEnterFidoSecrets)
        assertTrue(model.state.value.canEnterPivSecrets)
        recognizeFido(model, CardWire.APDU)
        assertFalse(model.state.value.canEnterPivSecrets)
    }

    @Test fun ctap1OnlyU2fReadNeverEnablesFido2OrPivSecrets() {
        val model = ManagerViewModel(app)
        model.selectReader(reader())
        var commands = 0
        val channel = object : CardChannel {
            override val wire = CardWire.CTAP
            override val supportsCtap1 = true
            override val supportsCtap2 = false
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 7609
            override fun transmit(command: ByteArray): ByteArray = error("No APDU")
            override fun exchangeCtap1(command: ByteArray): ByteArray {
                assertEquals(3, command[1].toInt())
                commands++
                return "U2F_V2".toByteArray() + byteArrayOf(0x90.toByte(), 0)
            }
            override fun close() = Unit
        }
        start(model, ManagerOperation.U2F_INFO) { channel }
        awaitResult(model)
        assertEquals(1, commands)
        assertEquals(CardFamily.U2F, model.state.value.recognition?.family)
        assertFalse(model.state.value.canEnterFidoSecrets)
        assertFalse(model.state.value.canEnterPivSecrets)
        assertFalse(model.state.value.supportsFidoReset)
    }

    @Test fun successfulUnrelatedReadCannotCreateOrChangeRecognition() {
        val model = ManagerViewModel(app)
        model.selectReader(reader())
        fun readRetries() {
            start(model, ManagerOperation.FIDO_RETRIES) {
                ManagerTestChannel(CardWire.CTAP) { command, _ ->
                    when (command) {
                        4 -> success(info())
                        6 -> success(cborMap(3 to 8.cbor()))
                        else -> error("Unexpected command")
                    }
                }
            }
            awaitResult(model)
            assertTrue(model.state.value.result is OperationResult.Completed)
        }
        readRetries()
        assertNull(model.state.value.recognition)
        recognizeFido(model)
        val observed = model.state.value.recognition
        readRetries()
        assertEquals(observed, model.state.value.recognition)
    }

    @Test fun malformedProbeAndEmptyReaderNeverLeaveRecognition() {
        for (transportFailure in listOf(false, true)) {
            val model = ManagerViewModel(app)
            model.selectReader(reader())
            recognizeFido(model)
            start(model, ManagerOperation.FIDO_INFO) {
                if (transportFailure) throw TransportException(Dispatch.NOT_DISPATCHED)
                ManagerTestChannel(CardWire.CTAP) { _, _ -> success(cborMap()) }
            }
            awaitResult(model)
            assertEquals(if (transportFailure) FailureCode.TRANSPORT_FAILURE else FailureCode.MALFORMED_RESPONSE,
                (model.state.value.result as OperationResult.Failed).code)
            assertNull(model.state.value.recognition)
            assertFalse(model.state.value.canEnterFidoSecrets)
        }
    }

    @Test fun detachedUsbStaysUnselectedAfterReplugUntilExplicitSelection() {
        val manager = app.getSystemService(UsbManager::class.java)
        val model = ManagerViewModel(app)
        val original = reader()
        val intf = Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", 1)
            ReflectionHelpers.setField(it, "mClass", 3)
            ReflectionHelpers.setField(it, "mSubclass", 0)
            ReflectionHelpers.setField(it, "mProtocol", 0)
            ReflectionHelpers.setField(it, "mEndpoints", arrayOf(0x81, 1).map { address ->
                Shadow.newInstanceOf(UsbEndpoint::class.java).also { endpoint ->
                    ReflectionHelpers.setField(endpoint, "mAddress", address)
                    ReflectionHelpers.setField(endpoint, "mAttributes", 3)
                    ReflectionHelpers.setField(endpoint, "mMaxPacketSize", 64)
                    ReflectionHelpers.setField(endpoint, "mInterval", 5)
                }
            }.toTypedArray())
        }
        ReflectionHelpers.setField(original.device, "mInterfaces", arrayOf(intf))
        model.selectReader(original)
        model.refreshReaders()
        assertEquals(original.routeKey, model.state.value.selectedReader?.routeKey)
        assertTrue(model.state.value.hasSelectedReader)
        shadowOf(manager).removeUsbDevice(original.device)
        UsbAuthenticatorReader.invalidate(original)
        model.refreshReaders()
        assertNull(model.state.value.selectedReader)
        assertFalse(model.state.value.nfc)
        assertFalse(model.state.value.hasSelectedReader)
        val input = OperationInput(ManagerOperation.FIDO_INFO, pin = "123456".toCharArray())
        model.submit(input)
        assertTrue(input.pin.all { it == '\u0000' })
        assertNull(input.inventoryRequest)
        assertNull(model.state.value.result)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.awaitingNfc)
        assertNull(model.pendingNfcOwner)

        val replacement = reader(name = original.device.deviceName)
        ReflectionHelpers.setField(replacement.device, "mInterfaces", arrayOf(intf))
        model.refreshReaders()
        val discovered = model.state.value.readers.single { it.device.deviceName == original.device.deviceName }
        assertNotEquals(original.routeKey, discovered.routeKey)
        assertFalse(UsbAuthenticatorReader.isCurrent(manager, original))
        assertTrue(UsbAuthenticatorReader.isCurrent(manager, discovered))
        assertNull(model.state.value.selectedReader)
        assertFalse(model.state.value.nfc)
        assertFalse(model.state.value.hasSelectedReader)
        model.selectReader(discovered)
        assertEquals(discovered.routeKey, model.state.value.selectedReader?.routeKey)
        assertTrue(model.state.value.hasSelectedReader)
        assertFalse(model.state.value.nfc)
    }

    @Test fun noReaderSubmissionPreservesUnknownEffectGuardAndClosesInput() {
        val model = ManagerViewModel(app)
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        val failure = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
            dispatch = Dispatch.RESPONSE_UNAVAILABLE)
        state.value = ManagerState(nfc = false, result = failure, requiresInspection = true,
            operation = ManagerOperation.FIDO_DELETE)
        val before = state.value
        for (operation in listOf(ManagerOperation.FIDO_INFO, ManagerOperation.PIV_SIGN)) {
            val input = OperationInput(operation, pin = "123456".toCharArray(), bytes = byteArrayOf(1, 2))
            model.submit(input)
            assertSame(before, model.state.value)
            assertSame(failure, model.state.value.result)
            assertTrue(model.state.value.requiresInspection)
            assertTrue(input.pin.all { it == '\u0000' })
            assertNull(input.inventoryRequest)
            if (operation.isFileSigning) assertTrue(input.bytes.all { it == 0.toByte() })
        }
    }

    @Test fun selectionPermissionPauseCancelAndDetachRetireObservation() {
        for (retirement in listOf("selection", "permission", "pause", "cancel", "detach")) {
            val model = ManagerViewModel(app)
            val selected = reader()
            model.selectReader(selected)
            recognizeFido(model)
            when (retirement) {
                "selection" -> model.selectReader(selected)
                "permission" -> { model.permissionPending(true); model.permissionPending(false) }
                "pause" -> model.pauseForActivity(changingConfigurations = true)
                "cancel" -> model.cancel()
                "detach" -> {
                    shadowOf(app.getSystemService(UsbManager::class.java)).removeUsbDevice(selected.device)
                    UsbAuthenticatorReader.invalidate(selected)
                    model.refreshReaders()
                }
            }
            assertNull("Recognition survives $retirement", model.state.value.recognition)
            assertFalse(model.state.value.canEnterFidoSecrets)
            assertFalse(model.state.value.canEnterPivSecrets)
        }
    }

    @Test fun lateSuccessfulProbeCannotRestoreRetiredObservation() {
        for (retirement in listOf("permission", "cancel", "pause", "detach", "permissionLost")) {
            val model = ManagerViewModel(app)
            val selected = reader()
            model.selectReader(selected)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val channel = ManagerTestChannel(CardWire.CTAP) { command, _ ->
                assertEquals(4, command)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                success(info())
            }
            start(model, ManagerOperation.FIDO_INFO) { channel }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                when (retirement) {
                    "permission" -> { model.permissionPending(true); model.permissionPending(false) }
                    "cancel" -> model.cancel()
                    "pause" -> model.pauseForActivity(changingConfigurations = false)
                    "detach" -> UsbAuthenticatorReader.invalidate(selected)
                    "permissionLost" -> {
                        val usb = app.getSystemService(UsbManager::class.java)
                        shadowOf(usb).addOrUpdateUsbDevice(selected.device, false)
                        assertFalse(usb.hasPermission(selected.device))
                    }
                }
            } finally { release.countDown() }
            awaitResult(model)
            assertNull("Late probe restored recognition after $retirement", model.state.value.recognition)
            assertFalse(model.state.value.canEnterFidoSecrets)
        }
    }

    @Test fun unrelatedTransportFailureRetiresRecognitionWithoutClearingUncertainty() {
        val model = ManagerViewModel(app)
        model.selectReader(reader())
        recognizeFido(model)
        val finish = ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java)
        finish.isAccessible = true
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        state.value = state.value.copy(operation = ManagerOperation.FIDO_DELETE)
        val failure = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
            dispatch = Dispatch.RESPONSE_UNAVAILABLE)
        finish.invoke(model, failure)
        assertSame(failure, model.state.value.result)
        assertTrue(model.state.value.requiresInspection)
        assertNull(model.state.value.recognition)
    }

    @Test fun knownPinFailurePreservesLastReadAndItsExistingResultSemantics() {
        val model = ManagerViewModel(app)
        model.selectReader(reader())
        recognizeFido(model)
        val observation = model.state.value.recognition
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        state.value = state.value.copy(operation = ManagerOperation.FIDO_CHANGE_PIN)
        val failure = OperationResult.Failed(FailureCode.PIN_INVALID, Effect.APPLIED, 0x31)
        val finish = ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java)
        finish.isAccessible = true
        finish.invoke(model, failure)
        assertSame(failure, model.state.value.result)
        assertFalse(model.state.value.requiresInspection)
        assertEquals(observation, model.state.value.recognition)
    }

    @Test fun nfcReadEnablesSecretEntryBeforeExistingExplicitNextTap() {
        val model = ManagerViewModel(app)
        val publicChannel = PivInfoChannel(Medium.NFC)
        start(model, ManagerOperation.PIV_INFO) { publicChannel }
        awaitResult(model)
        assertEquals(CardRecognition("nfc", 0, CardFamily.PIV), model.state.value.recognition)
        assertTrue(model.state.value.canEnterPivSecrets)
        val input = OperationInput(ManagerOperation.PIV_CHANGE_PIN,
            pin = "123456".toCharArray(), newPin = "654321".toCharArray())
        model.submit(input)
        assertTrue(model.state.value.awaitingNfc)
        assertNotNull(model.pendingNfcOwner)
        assertTrue(model.state.value.canEnterPivSecrets)
        assertEquals(6, publicChannel.commands.size)
        model.cancel()
        assertNull(model.state.value.recognition)
        assertNull(model.pendingNfcOwner)
        assertArrayEquals(CharArray(6), input.pin)
        assertArrayEquals(CharArray(6), input.newPin)
    }

    @Test fun startingPublicNfcReadRetiresEarlierFamilyBeforeTheTap() {
        val model = ManagerViewModel(app)
        start(model, ManagerOperation.PIV_INFO) { PivInfoChannel(Medium.NFC) }
        awaitResult(model)
        assertTrue(model.state.value.canEnterPivSecrets)
        model.submit(OperationInput(ManagerOperation.FIDO_INFO))
        assertTrue(model.state.value.awaitingNfc)
        assertNull(model.state.value.recognition)
        model.cancel()
    }

    @Test fun permissionTransitionRetiresQueuedNfcAttemptBeforeAnyTagConnection() {
        val model = ManagerViewModel(app)
        model.submit(OperationInput(ManagerOperation.FIDO_INFO))
        val owner = requireNotNull(model.pendingNfcOwner)
        model.permissionPending(true)
        model.permissionPending(false)
        assertTrue(model.onTag(Shadow.newInstanceOf(Tag::class.java), owner))
        awaitResult(model)
        assertEquals(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED),
            model.state.value.result)
        assertNull(model.state.value.recognition)
    }

    @Test fun observationIsLastReadAndDoesNotAssertSameCardAtSameReader() {
        val model = ManagerViewModel(app)
        val selected = reader(wire = CardWire.APDU)
        model.selectReader(selected)
        recognizeFido(model, CardWire.APDU)
        val lastRead = requireNotNull(model.state.value.recognition)
        val replacementCard = PivInfoChannel()
        assertEquals(lastRead, model.state.value.recognition)
        assertTrue(replacementCard.commands.isEmpty())
        start(model, ManagerOperation.PIV_INFO) { replacementCard }
        awaitResult(model)
        assertEquals(lastRead.route, model.state.value.recognition?.route)
        assertEquals(CardFamily.PIV, model.state.value.recognition?.family)
        assertFalse(model.state.value.canEnterFidoSecrets)
    }

    private fun preparedSigningFile(model: ManagerViewModel, operation: ManagerOperation, slot: Int): ByteArray {
        model.fileSigningAction(operation, slot)
        assertTrue(model.requestSigningFile())
        val request = requireNotNull(model.signingFileRequestId)
        model.pauseForActivity(changingConfigurations = false)
        assertNull(model.state.value.recognition)
        val bytes = byteArrayOf(1, 3, 5, 7)
        val uri = Uri.parse("content://recognition/signing-input.raw")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        model.receiveSigningFile(request, uri)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.signingFiles.state.value.selection == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull(model.signingFiles.state.value.selection)
        return bytes
    }

    @Test fun explicitPivProbePreservesPickerFileForSubsequentRawAndCmsSigning() {
        for (operation in listOf(ManagerOperation.PIV_SIGN, ManagerOperation.PIV_CMS_SIGN)) {
            val model = ManagerViewModel(app)
            model.selectReader(reader(wire = CardWire.APDU))
            start(model, ManagerOperation.PIV_INFO) { PivInfoChannel() }
            awaitResult(model)
            val bytes = preparedSigningFile(model, operation, 0x9c)
            val selection = requireNotNull(model.signingFiles.state.value.selection)
            val scheduler = TestCoroutineScheduler()
            Dispatchers.setMain(StandardTestDispatcher(scheduler))
            val probe = OperationInput(ManagerOperation.PIV_INFO, slot = 0x9c,
                inventoryGeneration = model.inventory.state.value.generation)
            val existingJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            model.submit(probe)
            assertSame(selection, model.signingFiles.state.value.selection)
            assertEquals(operation, model.signingFiles.state.value.operation)
            assertEquals(0x9c, model.signingFiles.state.value.slot)
            assertNull(model.state.value.recognition)
            assertNull(model.state.value.result)

            // Substitute the transport only after exercising the public submission and its ownership rules.
            model.viewModelScope.coroutineContext[Job]!!.children.single { it !in existingJobs }.cancel()
            scheduler.runCurrent()
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val publicChannel = PivInfoChannel()
            val run = ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java)
            run.isAccessible = true
            run.invoke(model, probe, { publicChannel })
            awaitResult(model)
            assertTrue(model.state.value.canEnterPivSecrets)
            assertSame(selection, model.signingFiles.state.value.selection)
            assertEquals(ManagerOperation.PIV_INFO, model.state.value.operation)
            assertEquals(listOf(0xa4, 0xfd, 0xf8, 0xf7, 0xf7, 0xf7), publicChannel.commands)
            assertNull((model.state.value.result as OperationResult.Completed).value.signatureFormat)

            Dispatchers.setMain(StandardTestDispatcher(scheduler))
            val format = if (operation == ManagerOperation.PIV_CMS_SIGN) SignatureFormat.CMS else SignatureFormat.RAW
            val signing = OperationInput(operation, pin = "123456".toCharArray(), slot = 0x9c,
                signingFileGeneration = selection.generation, signatureFormat = format)
            model.submit(signing)
            val holder = ReflectionHelpers.getField<AtomicReference<OperationInput?>>(model, "signingInput")
            val owned = requireNotNull(holder.get())
            assertArrayEquals(bytes, owned.bytes)
            assertEquals(0x9c, owned.slot)
            assertEquals(operation, owned.operation)
            assertEquals(format, owned.signatureFormat)
            assertArrayEquals(CharArray(6), signing.pin)
            assertNull(model.signingFiles.state.value.selection)
            model.cancel()
            assertArrayEquals(ByteArray(bytes.size), owned.bytes)
            assertArrayEquals(CharArray(6), owned.pin)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model.state.value.busy && System.nanoTime() < deadline) {
                scheduler.runCurrent()
                Thread.sleep(5)
            }
            assertFalse(model.state.value.busy)
            assertEquals(FailureCode.CANCELLED, (model.state.value.result as OperationResult.Failed).code)
            Dispatchers.setMain(UnconfinedTestDispatcher())
        }
    }

    @Test fun filePreservationDoesNotApplyToOtherCommandsSlotsOrRetiredPermission() {
        for (case in listOf("command", "slot", "permission", "uncertainty", "save", "secret")) {
            val model = ManagerViewModel(app)
            val selected = reader(wire = CardWire.APDU)
            model.selectReader(selected)
            preparedSigningFile(model, ManagerOperation.PIV_SIGN, 0x9c)
            when (case) {
                "permission" -> {
                    val usb = app.getSystemService(UsbManager::class.java)
                    shadowOf(usb).addOrUpdateUsbDevice(selected.device, false)
                    assertFalse(usb.hasPermission(selected.device))
                }
                "uncertainty" -> {
                    val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
                    state.value = state.value.copy(requiresInspection = true)
                }
                "save" -> assertTrue(model.signingFiles.requestSave(ManagerReport(emptyList(), export = byteArrayOf(9),
                    fileName = SignatureFormat.RAW.fileName, signatureFormat = SignatureFormat.RAW), ManagerOperation.PIV_SIGN))
            }
            val scheduler = TestCoroutineScheduler()
            Dispatchers.setMain(StandardTestDispatcher(scheduler))
            model.submit(OperationInput(if (case == "command") ManagerOperation.FIDO_INFO else ManagerOperation.PIV_INFO,
                slot = if (case == "slot") 0x9a else 0x9c,
                pin = if (case == "secret") "123456".toCharArray() else charArrayOf()))
            assertNull("File survived $case", model.signingFiles.state.value.selection)
            model.cancel()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model.state.value.busy && System.nanoTime() < deadline) {
                scheduler.runCurrent()
                Thread.sleep(5)
            }
            assertFalse(model.state.value.busy)
            Dispatchers.setMain(UnconfinedTestDispatcher())
        }
    }

    @Test fun observationForAnotherRouteOrPendingPermissionCannotEnableSecretEntry() {
        val selected = reader()
        val state = ManagerState(selectedReader = selected, nfc = false,
            recognition = CardRecognition("another-route", 1, CardFamily.FIDO2))
        assertFalse(state.canEnterFidoSecrets)
        assertFalse(state.canEnterPivSecrets)
        for (family in CardFamily.entries) {
            val pending = state.copy(permissionPending = true,
                recognition = CardRecognition(selected.routeKey, 1, family))
            assertFalse(pending.canEnterFidoSecrets)
            assertFalse(pending.canEnterPivSecrets)
        }
    }
}
