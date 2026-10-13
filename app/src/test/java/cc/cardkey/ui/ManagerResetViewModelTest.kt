package cc.cardkey.ui

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.transport.UsbReaderDescriptor
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ManagerResetViewModelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun reader(wire: CardWire = CardWire.CTAP): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "reset-device")
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        return UsbReaderDescriptor(device, 1, wire)
    }
    private fun state(model: ManagerViewModel) = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
    private fun run(model: ManagerViewModel, operation: ManagerOperation, channel: CardChannel) {
        val selected = model.state.value.selectedReader
        ReflectionHelpers.setField(model, "operationRoute", selected?.routeKey ?: "nfc")
        ReflectionHelpers.setField(model, "operationWire", channel.wire)
        state(model).value = model.state.value.copy(busy = true, result = null, operation = operation, resultWire = channel.wire)
        val run = ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java)
        run.isAccessible = true
        run.invoke(model, OperationInput(operation), { channel })
    }
    private fun await(model: ManagerViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.state.value.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(model.state.value.busy)
    }
    private fun channel(respond: (Int) -> ByteArray) = ManagerTestChannel(CardWire.CTAP) { code, _ ->
        assertNull(CardOperationGate.tryAcquire())
        respond(code)
    }

    @Test fun capabilityBelongsToNegotiatedSelectedAttachmentAndIsRetiredOnReaderChange() {
        val model = ManagerViewModel(app)
        val original = reader()
        model.selectReader(original)
        assertFalse(model.state.value.supportsFidoReset)
        run(model, ManagerOperation.FIDO_INFO, channel { success(info()) })
        await(model)
        assertTrue(model.state.value.supportsFidoReset)
        UsbAuthenticatorReader.invalidate(original)
        model.selectReader(reader())
        assertFalse(model.state.value.supportsFidoReset)
        model.selectReader(null)
        assertFalse(model.state.value.supportsFidoReset)
        assertTrue(model.state.value.supportsPiv)
    }

    @Test fun unknownCapabilityApduAndNfcResetAreRefusedBeforeConnection() {
        for (selected in listOf(null, reader(CardWire.APDU), reader())) {
            val model = ManagerViewModel(app)
            model.selectReader(selected)
            model.submit(OperationInput(ManagerOperation.FIDO_RESET))
            assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED), model.state.value.result)
            assertFalse(model.state.value.awaitingNfc)
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun confirmedResetKeepsOneLeaseAndBothUnknownAndAcceptedFailuresFenceFurtherWrites() {
        for (accepted in listOf(false, true)) {
            val model = ManagerViewModel(app)
            model.selectReader(reader())
            val channel = channel { code -> when (code) {
                7 -> if (accepted) success() else throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                4 -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                else -> error("Unexpected command")
            } }
            run(model, ManagerOperation.FIDO_RESET, channel)
            await(model)
            val result = model.state.value.result as OperationResult.Failed
            assertEquals(if (accepted) Effect.APPLIED else Effect.UNKNOWN, result.effect)
            assertEquals(accepted, result.writeAccepted)
            assertTrue(model.state.value.requiresInspection)
            assertEquals(if (accepted) listOf(7, 4) else listOf(7), channel.commands.map { it.first })
            for (operation in listOf(ManagerOperation.FIDO_RESET, ManagerOperation.FIDO_SET_PIN, ManagerOperation.PIV_RESET)) {
                model.submit(OperationInput(operation))
                assertSame(result, model.state.value.result)
                assertFalse(model.state.value.busy)
            }
            assertTrue(channel.closed)
            CardOperationGate.tryAcquire()!!.close()
        }
    }

    @Test fun cancellationBeforeResetReplyCannotReadStatusOrReviveResultsAndKeepsLeaseUntilTerminal() {
        val model = ManagerViewModel(app)
        model.selectReader(reader())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = channel { code ->
            assertEquals(7, code)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            success()
        }
        val cancellable = object : CardChannel by delegate { override fun cancel() = Unit }
        run(model, ManagerOperation.FIDO_RESET, cancellable)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            model.cancel()
            assertNull(model.state.value.result)
            assertNull(CardOperationGate.tryAcquire())
        } finally { release.countDown() }
        await(model)
        val result = model.state.value.result as OperationResult.Failed
        assertEquals(FailureCode.CANCELLED, result.code)
        assertEquals(Effect.UNKNOWN, result.effect)
        assertTrue(model.state.value.requiresInspection)
        assertEquals(listOf(7), delegate.commands.map { it.first })
        assertNull(model.state.value.presenceMessage)
        CardOperationGate.tryAcquire()!!.close()
    }

    @Test fun resetSubmissionRetiresProtectedExportEvenWhenPermissionIsMissing() {
        val model = ManagerViewModel(app)
        val original = reader()
        model.selectReader(original)
        val secret = byteArrayOf(9, 8)
        state(model).value = model.state.value.copy(ctap2Reader = original.routeKey,
            result = OperationResult.Completed(ManagerReport(emptyList(), secret, protectedExport = true), Effect.NONE))
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(original.device, false)
        model.submit(OperationInput(ManagerOperation.FIDO_RESET))
        assertArrayEquals(ByteArray(2), secret)
        assertTrue(model.state.value.result is OperationResult.Failed)
    }
    @Test fun actualCtap1SessionCannotDispatchResetOrKeepPriorCtap2Capability() {
        val model = ManagerViewModel(app)
        val selected = reader()
        model.selectReader(selected)
        state(model).value = model.state.value.copy(ctap2Reader = selected.routeKey)
        val delegate = channel { error("CTAP1 reset dispatched") }
        val ctap1 = object : CardChannel by delegate { override val supportsCtap2 = false }
        run(model, ManagerOperation.FIDO_RESET, ctap1)
        await(model)
        assertEquals(FailureCode.UNSUPPORTED, (model.state.value.result as OperationResult.Failed).code)
        assertFalse(model.state.value.supportsFidoReset)
        assertTrue(delegate.commands.isEmpty())
    }
    @Test fun cancelledOrForeignReadbackKeepsAcceptedResetWithoutPublishingStaleStatus() {
        for (foreign in listOf(false, true)) {
            val model = ManagerViewModel(app)
            val original = reader()
            model.selectReader(original)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val delegate = channel { code -> when (code) {
                7 -> success()
                4 -> {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    success(info())
                }
                else -> error("Unexpected command")
            } }
            val cancellable = object : CardChannel by delegate { override fun cancel() = Unit }
            run(model, ManagerOperation.FIDO_RESET, cancellable)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (foreign) {
                    UsbAuthenticatorReader.invalidate(original)
                    reader()
                } else model.cancel()
                assertNull(CardOperationGate.tryAcquire())
            } finally { release.countDown() }
            await(model)
            val result = model.state.value.result as OperationResult.Failed
            assertEquals(FailureCode.CANCELLED, result.code)
            assertEquals(Effect.APPLIED, result.effect)
            assertTrue(result.writeAccepted)
            assertTrue(model.state.value.requiresInspection)
            assertFalse(model.state.value.supportsFidoReset)
            assertEquals(listOf(7, 4), delegate.commands.map { it.first })
            CardOperationGate.tryAcquire()!!.close()
        }
    }
}
