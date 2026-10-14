package cc.cardkey.signing

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.*
import cc.cardkey.transport.UsbReaderDescriptor
import cc.cardkey.ui.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PivFileSigningViewModelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(format: SignatureFormat = SignatureFormat.RAW) = ManagerViewModel(app).apply {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "file-signing-device")
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        selectReader(UsbReaderDescriptor(device, 1, CardWire.APDU))
        fileSigningAction(if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN)
    }
    private fun await(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!test() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(test())
    }
    private fun select(model: ManagerViewModel, bytes: ByteArray) {
        assertTrue(model.requestSigningFile())
        val uri = Uri.parse("content://file-signing/selected.raw")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        model.receiveSigningFile(model.signingFileRequestId ?: "retired", uri)
        await { model.signingFiles.state.value.selection != null }
    }
    private fun state(model: ManagerViewModel) = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
    private fun run(model: ManagerViewModel, input: OperationInput, channel: CardChannel) {
        state(model).value = model.state.value.copy(busy = true, operation = input.operation, result = null)
        val method = ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java).apply { isAccessible = true }
        method.invoke(model, input, { channel })
    }

    @Test fun selectedInputSurvivesOnlyIntentionalPickerPauseAndRejectsActionAba() {
        val model = model()
        assertTrue(model.requestSigningFile())
        model.pauseForActivity(false)
        val uri = Uri.parse("content://file-signing/pause.raw")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        model.receiveSigningFile(model.signingFileRequestId ?: "retired", uri)
        await { model.signingFiles.state.value.selection != null }
        model.pauseForActivity(false)
        assertNull(model.signingFiles.state.value.selection)
        assertTrue(model.requestSigningFile())
        model.fileSigningAction(ManagerOperation.PIV_INFO)
        model.fileSigningAction(ManagerOperation.PIV_SIGN)
        model.receiveSigningFile(model.signingFileRequestId ?: "retired", uri)
        assertNull(model.signingFiles.state.value.selection)
        model.cancel()
    }

    @Test fun publicSubmitTransfersOneIndependentSnapshotAndCancelsBeforeWorkerDispatch() = publicSubmit(SignatureFormat.RAW)
    @Test fun cmsSubmitTransfersFormatAndSelectedBytesThenCancelsBeforeDispatch() = publicSubmit(SignatureFormat.CMS)
    private fun publicSubmit(format: SignatureFormat) {
        val model = model(format)
        val original = byteArrayOf(1, 2, 3, 4)
        select(model, original)
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val input = OperationInput(if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(),
            signingFileGeneration = model.signingFiles.state.value.selection!!.generation, signatureFormat = format)
        model.submit(input)
        val holder = ReflectionHelpers.getField<AtomicReference<OperationInput?>>(model, "signingInput")
        val owned = requireNotNull(holder.get())
        assertArrayEquals(original, owned.bytes)
        assertEquals(format, owned.signatureFormat)
        assertTrue(input.pin.all { it == '\u0000' })
        assertNull(model.signingFiles.state.value.selection)
        model.cancel()
        assertTrue(owned.bytes.all { it == 0.toByte() })
        assertTrue(owned.pin.all { it == '\u0000' })
        await { scheduler.runCurrent(); !model.state.value.busy }
        assertEquals(FailureCode.CANCELLED, (model.state.value.result as OperationResult.Failed).code)
    }

    @Test fun lastRetryDeclineAndChangedFactsPreventVerificationAndWipeInput() = lastRetry(SignatureFormat.RAW)
    @Test fun cmsLastRetryDeclineAndChangedFactsNeverReachPinOrSignature() = lastRetry(SignatureFormat.CMS)
    private fun lastRetry(format: SignatureFormat) {
        for (approve in listOf(false, true)) {
            val model = model(format)
            val card = EnrollmentCard().apply {
                pinRemaining = 1; pukRemaining = 0
                if (format == SignatureFormat.CMS) certificate = documentCertificate(this).encoded
            }
            val input = OperationInput(if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1, 2, 3), signatureFormat = format)
            run(model, input, card)
            await { model.state.value.lastRetry != null }
            if (approve) card.pinRemaining = 2
            model.approveLastRetry(approve)
            await { !model.state.value.busy }
            assertEquals(if (approve) FailureCode.RECOVERY_REQUIRED else FailureCode.CANCELLED,
                (model.state.value.result as OperationResult.Failed).code)
            assertEquals(0, card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
            assertTrue(input.pin.all { it == '\u0000' })
            assertTrue(input.bytes.all { it == 0.toByte() })
        }
    }

    @Test fun cancellingActiveVerifyOrCompletedGaNeverRacesFileWipingOrExports() = cancellingActive(SignatureFormat.RAW)
    @Test fun cmsCancelDuringVerifyOrCompletedSignaturePreservesUnknownAndOwnership() = cancellingActive(SignatureFormat.CMS)
    private fun cancellingActive(format: SignatureFormat) {
        for (instruction in listOf(0x20, 0x87)) {
            val model = model(format)
            val card = EnrollmentCard().apply { if (format == SignatureFormat.CMS) certificate = documentCertificate(this).encoded }
            val original = byteArrayOf(1, 2, 3, 4)
            val input = OperationInput(if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = original.copyOf(), signatureFormat = format)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val channel = object : CardChannel by card {
                override fun transmit(command: ByteArray): ByteArray {
                    val result = card.transmit(command)
                    if (command[1].toInt() and 255 == instruction) {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                        if (instruction == 0x20) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    }
                    return result
                }
                override fun cancel() { }
            }
            run(model, input, channel)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                model.cancel()
                assertArrayEquals(original, input.bytes)
                assertTrue(model.state.value.busy)
            } finally { release.countDown() }
            await { !model.state.value.busy }
            val failure = model.state.value.result as OperationResult.Failed
            assertEquals(if (instruction == 0x20) Effect.UNKNOWN else Effect.APPLIED, failure.effect)
            assertEquals(instruction == 0x20, model.state.value.requiresInspection)
            assertTrue(input.bytes.all { it == 0.toByte() })
            assertEquals(if (instruction == 0x20) 0 else 1, card.commands.count { it.ins == 0x87 })
        }
    }

    @Test fun saveUsesCapturedResultAndDuplicateCallbackCannotWriteAgain() {
        val model = model()
        val original = ManagerReport(emptyList(), byteArrayOf(1, 2, 3), "signature.bin", signatureFormat = SignatureFormat.RAW)
        val replacement = ManagerReport(emptyList(), byteArrayOf(7, 8, 9), "signature.bin", signatureFormat = SignatureFormat.RAW)
        state(model).value = model.state.value.copy(operation = ManagerOperation.PIV_SIGN,
            result = OperationResult.Completed(original, Effect.APPLIED))
        assertTrue(model.requestSignatureSave(original))
        model.pauseForActivity(false)
        assertSame(original, (model.state.value.result as OperationResult.Completed).value)
        state(model).value = model.state.value.copy(result = OperationResult.Completed(replacement, Effect.APPLIED))
        val uri = Uri.parse("content://file-signing/signature.bin")
        val output = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(uri, output)
        model.receiveSignatureSave(model.signatureSaveRequestId ?: "retired", uri)
        await { model.signingFiles.state.value.exportStatus == SignatureExportStatus.SAVED }
        model.receiveSignatureSave(model.signatureSaveRequestId ?: "retired", uri)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertSame(replacement, (model.state.value.result as OperationResult.Completed).value)
        assertSame(original, model.signingFiles.state.value.exportOwner)
        assertTrue(model.requestSignatureSave(replacement))
        model.receiveSignatureSave(requireNotNull(model.signatureSaveRequestId), null)
        assertEquals(SignatureExportStatus.CANCELLED, model.signingFiles.state.value.exportStatus)
        assertSame(replacement, (model.state.value.result as OperationResult.Completed).value)
    }
    @Test fun delayedReadFailureAfterActionAbaCannotReplaceNewerState() {
        val model = model()
        val marker = ManagerReport(emptyList())
        state(model).value = model.state.value.copy(result = OperationResult.Completed(marker, Effect.NONE))
        assertTrue(model.requestSigningFile())
        val uri = Uri.parse("content://file-signing/delayed.raw")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var buffer: ByteArray? = null
        val finished = CountDownLatch(1)
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                buffer = bytes
                super.read(bytes, offset, length)
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                throw java.io.IOException()
            }
            override fun close() { super.close(); finished.countDown() }
        })
        model.receiveSigningFile(model.signingFileRequestId ?: "retired", uri)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            model.fileSigningAction(ManagerOperation.PIV_INFO)
            model.fileSigningAction(ManagerOperation.PIV_SIGN)
        } finally { release.countDown() }
        await { finished.count == 0L && buffer!!.all { it == 0.toByte() } &&
            model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
        assertNull(model.signingFiles.state.value.selection)
        assertSame(marker, (model.state.value.result as OperationResult.Completed).value)
    }

    @Test fun missingSelectionCannotReachCardAndSaveWriteOrCloseFailureStaysUnknown() {
        val model = model()
        val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray())
        model.submit(input)
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
        assertTrue(input.pin.all { it == '\u0000' })
        assertFalse(model.state.value.busy)
        for (closeFailure in listOf(false, true)) {
            val source = ManagerReport(emptyList(), byteArrayOf(1, 2, 3), "signature.bin", signatureFormat = SignatureFormat.RAW)
            state(model).value = model.state.value.copy(operation = ManagerOperation.PIV_SIGN, result = OperationResult.Completed(source, Effect.APPLIED))
            assertTrue(model.requestSignatureSave(source))
            val uri = Uri.parse("content://file-signing/failure-$closeFailure.bin")
            var writes = 0
            shadowOf(app.contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writes++
                    super.write(bytes, offset, if (closeFailure) length else 1)
                    if (!closeFailure) throw java.io.IOException()
                }
                override fun close() { if (closeFailure) throw java.io.IOException() }
            })
            model.receiveSignatureSave(model.signatureSaveRequestId ?: "retired", uri)
            await { model.signingFiles.state.value.exportStatus == SignatureExportStatus.UNKNOWN }
            assertEquals(1, writes)
            assertSame(source, (model.state.value.result as OperationResult.Completed).value)
            assertArrayEquals(byteArrayOf(1, 2, 3), source.export)
        }
    }

    @Test fun acceptedOtherOperationRetiresPendingFileReadBeforeItsLateError() {
        val model = model()
        assertTrue(model.requestSigningFile())
        val request = requireNotNull(model.signingFileRequestId)
        val uri = Uri.parse("content://file-signing/other-action.raw")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                throw java.io.IOException()
            }
        })
        model.receiveSigningFile(request, uri)
        var current: OperationResult<ManagerReport>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            model.submit(OperationInput(ManagerOperation.PIV_INFO))
            await { !model.state.value.busy }
            current = model.state.value.result
            assertNotNull(current)
        } finally { release.countDown() }
        await { model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
        assertSame(current, model.state.value.result)
        assertNull(model.signingFiles.state.value.selection)
    }

    @Test fun cmsProfilePreflightRejectsCurrentCertificateBeforePinAndOffersNoExport() {
        val model = model(SignatureFormat.CMS)
        val card = EnrollmentCard().apply { certificate = documentCertificate(this, eku = "1.3.6.1.5.5.7.3.2").encoded }
        val input = OperationInput(ManagerOperation.PIV_CMS_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1), signatureFormat = SignatureFormat.CMS)
        run(model, input, card)
        await { !model.state.value.busy }
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
        assertEquals(0, card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
        assertNull(model.signatureSaveRequestId)
        assertTrue(input.bytes.all { it == 0.toByte() })
    }

    @Test fun cmsSelectedInputCannotBeInjectedOrRevivedByLatePickerAfterBackground() {
        val model = model(SignatureFormat.CMS)
        assertTrue(model.requestSigningFile())
        val oldRequest = requireNotNull(model.signingFileRequestId)
        model.cancel()
        assertTrue(model.requestSigningFile())
        val uri = Uri.parse("content://file-signing/cms-late.bin")
        var reads = 0
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int { reads++; return super.read(bytes, off, len) }
        })
        model.receiveSigningFile(oldRequest, uri)
        assertEquals(0, reads)
        assertNull(model.signingFiles.state.value.selection)
        model.receiveSigningFile(requireNotNull(model.signingFileRequestId), uri)
        await { model.signingFiles.state.value.selection != null }
        model.pauseForActivity(false)
        val input = OperationInput(ManagerOperation.PIV_CMS_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(9), signatureFormat = SignatureFormat.CMS)
        model.submit(input)
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
        assertTrue(input.bytes.all { it == 0.toByte() })
        assertTrue(input.pin.all { it == '\u0000' })
        assertFalse(model.state.value.busy)
    }

    @Test fun cmsVerifiedReceiptSurvivesSaveCloseFailureWithoutSigningAgain() {
        val model = model(SignatureFormat.CMS)
        val card = EnrollmentCard().apply { certificate = documentCertificate(this).encoded }
        val input = OperationInput(ManagerOperation.PIV_CMS_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1, 2, 3), signatureFormat = SignatureFormat.CMS)
        run(model, input, card)
        await { !model.state.value.busy }
        val receipt = model.state.value.result as OperationResult.Completed
        val report = receipt.value
        assertEquals(SignatureFormat.CMS, report.signatureFormat)
        assertEquals("signature.p7s", report.fileName)
        assertEquals(listOf(cc.cardkey.R.string.signing_cmsVerified, cc.cardkey.R.string.signing_cmsScope,
            cc.cardkey.R.string.signing_cmsTrust), report.messages)
        assertTrue(model.requestSignatureSave(report))
        assertEquals(SignatureFormat.CMS, model.signatureSaveFormat)
        val uri = Uri.parse("content://file-signing/cms-close.p7s")
        var writes = 0
        shadowOf(app.contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; super.write(bytes, offset, length) }
            override fun close() { throw java.io.IOException() }
        })
        val save = requireNotNull(model.signatureSaveRequestId)
        model.receiveSignatureSave(save, uri)
        await { model.signingFiles.state.value.exportStatus == SignatureExportStatus.UNKNOWN }
        model.receiveSignatureSave(save, uri)
        assertEquals(1, writes)
        assertEquals(1, card.commands.count { it.ins == 0x87 })
        assertSame(receipt, model.state.value.result)
        assertSame(report, model.signingFiles.state.value.exportOwner)
    }

    @Test fun cmsLostOrRemovedPrivateResponseBlocksReplayAndNeverOffersSave() {
        for (removed in listOf(false, true)) {
            val model = model(SignatureFormat.CMS)
            val card = EnrollmentCard().apply { certificate = documentCertificate(this).encoded }
            val channel = object : CardChannel by card {
                override fun transmit(command: ByteArray): ByteArray {
                    val response = card.transmit(command)
                    if (command[1].toInt() and 255 == 0x87) {
                        if (removed) throw java.io.IOException("removed")
                        throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    }
                    return response
                }
            }
            val input = OperationInput(ManagerOperation.PIV_CMS_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1), signatureFormat = SignatureFormat.CMS)
            run(model, input, channel)
            await { !model.state.value.busy }
            val failure = model.state.value.result as OperationResult.Failed
            assertEquals(FailureCode.TRANSPORT_FAILURE, failure.code)
            assertEquals(Effect.UNKNOWN, failure.effect)
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure.dispatch)
            assertTrue(model.state.value.requiresInspection)
            assertNull(model.signatureSaveRequestId)
            val next = OperationInput(ManagerOperation.PIV_CMS_SIGN, pin = "123456".toCharArray(), signatureFormat = SignatureFormat.CMS)
            model.submit(next)
            assertSame(failure, model.state.value.result)
            assertEquals(1, card.commands.count { it.ins == 0x87 })
            assertTrue(input.bytes.all { it == 0.toByte() })
            assertTrue(next.pin.all { it == '\u0000' })
        }
    }

    @Test fun switchingRawAndCmsRetiresPickerAndRejectsMismatchedSubmitOwner() {
        val model = model(SignatureFormat.CMS)
        assertTrue(model.requestSigningFile())
        val oldRequest = requireNotNull(model.signingFileRequestId)
        model.fileSigningAction(ManagerOperation.PIV_SIGN)
        model.fileSigningAction(ManagerOperation.PIV_CMS_SIGN)
        val uri = Uri.parse("content://file-signing/retired-cms.raw")
        var reads = 0
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int { reads++; return super.read(bytes, off, len) }
        })
        model.receiveSigningFile(oldRequest, uri)
        assertEquals(0, reads)
        select(model, byteArrayOf(1, 2, 3))
        val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(),
            signingFileGeneration = model.signingFiles.state.value.selection!!.generation)
        model.submit(input)
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
        assertFalse(model.state.value.busy)
        assertTrue(input.pin.all { it == '\u0000' })
        model.cancel()
    }

}
