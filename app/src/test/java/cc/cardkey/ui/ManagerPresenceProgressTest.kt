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
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ManagerPresenceProgressTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class Channel : CardChannel {
        override val wire = CardWire.CTAP
        override val supportsCtap2 = true
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 7609
        val listener = AtomicReference<((Boolean) -> Unit)?>(null)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun setUserPresenceListener(listener: ((Boolean) -> Unit)?) {
            if (listener == null) assertNull(CardOperationGate.tryAcquire())
            this.listener.set(listener)
        }
        override fun transmit(command: ByteArray): ByteArray = error("No APDU")
        override fun exchangeCtap2(command: ByteArray): ByteArray {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return success(info())
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private fun reader(): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "progress-device")
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        return UsbReaderDescriptor(device, 1, CardWire.CTAP)
    }
    private fun start(model: ManagerViewModel, channel: Channel) {
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        state.value = state.value.copy(busy = true, resultWire = CardWire.CTAP)
        val run = ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java)
        run.isAccessible = true
        run.invoke(model, OperationInput(ManagerOperation.FIDO_INFO), { channel })
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    @Test fun requiredProcessingAndCancellationUpdatesNeverBecomePresenceProof() {
        val model = ManagerViewModel(app)
        model.selectReader(reader())
        val channel = Channel()
        start(model, channel)
        assertTrue(channel.entered.await(5, TimeUnit.SECONDS))
        val callback = requireNotNull(channel.listener.get())
        try {
            callback(true)
            assertTrue(model.state.value.userPresenceRequired)
            assertEquals(cc.cardkey.R.string.status_hidUserPresenceRequired, model.state.value.presenceMessage)
            assertNull(model.state.value.result)
            callback(false)
            assertFalse(model.state.value.userPresenceRequired)
            assertNull(model.state.value.presenceMessage)
            callback(true)
            model.cancel()
            assertFalse(model.state.value.userPresenceRequired)
            assertNull(model.state.value.presenceMessage)
            callback(true)
            assertFalse(model.state.value.userPresenceRequired)
            assertNull(model.state.value.presenceMessage)
            assertNull(CardOperationGate.tryAcquire())
        } finally { channel.release.countDown() }
        await { model.state.value.result != null }
        assertFalse(model.state.value.userPresenceRequired)
        assertNull(model.state.value.presenceMessage)
        assertEquals(FailureCode.CANCELLED, (model.state.value.result as OperationResult.Failed).code)
        assertNull(channel.listener.get())
        callback(true)
        assertFalse(model.state.value.userPresenceRequired)
        assertNull(model.state.value.presenceMessage)
    }

    @Test fun replacementAtSameUsbPathRejectsOriginalAttachmentProgress() {
        val model = ManagerViewModel(app)
        val original = reader()
        model.selectReader(original)
        val channel = Channel()
        start(model, channel)
        assertTrue(channel.entered.await(5, TimeUnit.SECONDS))
        val callback = requireNotNull(channel.listener.get())
        try {
            callback(true)
            assertTrue(model.state.value.userPresenceRequired)
            assertEquals(cc.cardkey.R.string.status_hidUserPresenceRequired, model.state.value.presenceMessage)
            UsbAuthenticatorReader.invalidate(original)
            val replacement = reader()
            assertNotEquals(original.routeKey, replacement.routeKey)
            callback(true)
            assertFalse(model.state.value.userPresenceRequired)
            assertNull(model.state.value.presenceMessage)
            callback(false)
            assertFalse(model.state.value.userPresenceRequired)
            assertNull(model.state.value.presenceMessage)
        } finally { channel.release.countDown() }
        await { model.state.value.result != null }
    }

    @Test fun queuedTrueAndRetiredListenerCannotReviveProgressAfterTerminal() {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val model = ManagerViewModel(app)
        val channel = Channel()
        start(model, channel)
        scheduler.runCurrent()
        assertTrue(channel.entered.await(5, TimeUnit.SECONDS))
        val callback = requireNotNull(channel.listener.get())
        callback(true)
        channel.release.countDown()
        await { channel.listener.get() == null }
        scheduler.runCurrent()
        await { scheduler.runCurrent(); model.state.value.result != null }
        assertFalse(model.state.value.userPresenceRequired)
        assertNull(model.state.value.presenceMessage)
        callback(true)
        scheduler.runCurrent()
        assertFalse(model.state.value.userPresenceRequired)
        assertNull(model.state.value.presenceMessage)
        assertTrue(model.state.value.result is OperationResult.Completed)
    }
}
