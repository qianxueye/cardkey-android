package cc.cardkey.ui

import android.view.HapticFeedbackConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cc.cardkey.protocol.*
import cc.cardkey.transport.UsbReaderDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OperationFeedbackTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private lateinit var model: ManagerViewModel
    private val events = mutableListOf<Int>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        model = ManagerViewModel(RuntimeEnvironment.getApplication())
    }

    @After fun teardown() { scope.cancel(); Dispatchers.resetMain() }

    private fun observe() = scope.launch { model.operationFeedback.collect { events += it } }
    private fun state() = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
    private fun begin(operation: ManagerOperation = ManagerOperation.FIDO_INFO) {
        state().value = model.state.value.copy(busy = true, result = null, operation = operation)
    }
    private fun finish(result: OperationResult<ManagerReport>) {
        ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java).apply {
            isAccessible = true
        }.invoke(model, result)
    }

    @Test fun eachNewFidoAndPivCompletionEmitsOnceEvenWithIdenticalReports() {
        observe()
        val result = OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE)
        for (operation in listOf(ManagerOperation.FIDO_INFO, ManagerOperation.PIV_INFO)) {
            begin(operation)
            state().value = model.state.value.copy(awaitingNfc = true)
            state().value = model.state.value.copy(userPresenceRequired = true)
            assertEquals(if (operation == ManagerOperation.FIDO_INFO) 0 else 1, events.size)
            finish(result)
            finish(result)
        }
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.CONFIRM), events)
    }

    @Test fun unknownAndAcceptedWriteFailuresNeverSignalSuccess() {
        observe()
        for (result in listOf(
            OperationResult.Failed(FailureCode.PIN_INVALID, Effect.NONE),
            OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN),
            OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.APPLIED, writeAccepted = true),
        )) {
            begin(ManagerOperation.PIV_CHANGE_PIN)
            finish(result)
        }
        assertEquals(List(3) { HapticFeedbackConstants.REJECT }, events)
    }

    @Test fun cancelledAndRetiredOperationsAreSilent() {
        observe()
        begin()
        finish(OperationResult.Failed(FailureCode.CANCELLED, Effect.UNKNOWN, writeAccepted = true))
        begin()
        model.cancel()
        finish(OperationResult.Failed(FailureCode.PIN_INVALID, Effect.APPLIED))
        assertTrue(events.isEmpty())
    }

    @Test fun reopeningCachedResultOrResubscribingDoesNotReplayFeedback() {
        begin()
        finish(OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE))
        val subscription = observe()
        assertTrue(events.isEmpty())
        begin()
        finish(OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE))
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), events)
        subscription.cancel()
        observe()
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), events)
    }

    @Test fun protectedRecognitionCompletionSignalsOnceWithoutAResultReceipt() {
        observe()
        begin(ManagerOperation.PIV_INFO)
        val result = OperationResult.Completed(ManagerReport(emptyList(), protectedPivRecognition = true), Effect.NONE)
        finish(result)
        finish(result)
        assertNull(model.state.value.result)
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), events)
    }

    @Test fun unownedResultChangesAndReaderRefreshStaySilent() {
        observe()
        state().value = model.state.value.copy(result = OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE))
        model.refreshReaders()
        finish(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE))
        assertTrue(events.isEmpty())
    }

    @Test fun usbFailureAfterDeviceDisappearsBeforeDetachBroadcastStaysSilent() {
        observe()
        val usb = RuntimeEnvironment.getApplication().getSystemService(UsbManager::class.java)
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "haptic-lost-device")
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        val reader = UsbReaderDescriptor(device, 1, CardWire.CTAP)
        model.selectReader(reader)
        begin()
        ReflectionHelpers.setField(model, "cancelled", false)
        val channel = ManagerTestChannel(CardWire.CTAP) { _, _ ->
            shadowOf(usb).removeUsbDevice(device)
            throw TransportException(Dispatch.NOT_DISPATCHED)
        }
        ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java).apply {
            isAccessible = true
        }.invoke(model, OperationInput(ManagerOperation.FIDO_INFO), { channel })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.state.value.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(model.state.value.busy)
        assertSame(reader, model.state.value.selectedReader)
        assertEquals(FailureCode.TRANSPORT_FAILURE, (model.state.value.result as OperationResult.Failed).code)
        assertTrue(events.isEmpty())
    }
}
