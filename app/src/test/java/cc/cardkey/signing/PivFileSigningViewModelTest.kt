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
    private fun model() = ManagerViewModel(app).apply {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "file-signing-device")
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        selectReader(UsbReaderDescriptor(device, 1, CardWire.APDU))
        fileSigningAction(ManagerOperation.PIV_SIGN)
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

    @Test fun publicSubmitTransfersOneIndependentSnapshotAndCancelsBeforeWorkerDispatch() {
        val model = model()
        val original = byteArrayOf(1, 2, 3, 4)
        select(model, original)
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(),
            signingFileGeneration = model.signingFiles.state.value.selection!!.generation)
        model.submit(input)
        val holder = ReflectionHelpers.getField<AtomicReference<OperationInput?>>(model, "signingInput")
        val owned = requireNotNull(holder.get())
        assertArrayEquals(original, owned.bytes)
        assertTrue(input.pin.all { it == '\u0000' })
        assertNull(model.signingFiles.state.value.selection)
        model.cancel()
        assertTrue(owned.bytes.all { it == 0.toByte() })
        assertTrue(owned.pin.all { it == '\u0000' })
        await { scheduler.runCurrent(); !model.state.value.busy }
        assertEquals(FailureCode.CANCELLED, (model.state.value.result as OperationResult.Failed).code)
    }

    @Test fun lastRetryDeclineAndChangedFactsPreventVerificationAndWipeInput() {
        for (approve in listOf(false, true)) {
            val model = model()
            val card = EnrollmentCard().apply { pinRemaining = 1; pukRemaining = 0 }
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1, 2, 3))
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

    @Test fun cancellingActiveVerifyOrCompletedGaNeverRacesFileWipingOrExports() {
        for (instruction in listOf(0x20, 0x87)) {
            val model = model()
            val card = EnrollmentCard()
            val original = byteArrayOf(1, 2, 3, 4)
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = original.copyOf())
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
        val original = ManagerReport(emptyList(), byteArrayOf(1, 2, 3), "signature.bin")
        val replacement = ManagerReport(emptyList(), byteArrayOf(7, 8, 9), "signature.bin")
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
            val source = ManagerReport(emptyList(), byteArrayOf(1, 2, 3), "signature.bin")
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

}
