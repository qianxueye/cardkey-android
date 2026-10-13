package cc.cardkey.provider

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import cc.cardkey.protocol.*
import java.util.concurrent.atomic.AtomicReference
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.transport.UsbReaderDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProviderPresenceActivityTest {
    private fun activity(): ProviderActivity = Robolectric.buildActivity(ProviderActivity::class.java).get().also {
        workerActive(it, true)
        ReflectionHelpers.setField(it, "active", true)
        ReflectionHelpers.setField(it, "presenceGeneration", 1L)
        ReflectionHelpers.callInstanceMethod<Unit>(it, "setBusy", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }
    private fun workerActive(activity: ProviderActivity, active: Boolean) {
        val runner = ReflectionHelpers.getField<ProviderOperationRunner>(activity, "runner")
        val channel = ReflectionHelpers.getField<AtomicReference<CardChannel?>>(runner, "channel")
        channel.set(if (active) object : CardChannel {
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 7609
            override fun transmit(command: ByteArray): ByteArray = error("No IO")
            override fun close() = Unit
        } else null)
    }
    private fun update(activity: ProviderActivity, generation: Long, reader: UsbReaderDescriptor?, required: Boolean) {
        val method = ProviderActivity::class.java.getDeclaredMethod("updateUserPresence", Long::class.javaPrimitiveType,
            UsbReaderDescriptor::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(activity, generation, reader, required)
    }
    private fun required(activity: ProviderActivity): Boolean = ReflectionHelpers.callInstanceMethod(activity, "getUserPresenceRequired")
    private fun message(activity: ProviderActivity): Int? = ReflectionHelpers.callInstanceMethod(activity, "getPresenceMessage")
    private fun selected(activity: ProviderActivity, reader: UsbReaderDescriptor) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setSelected", ReflectionHelpers.ClassParameter.from(UsbReaderDescriptor::class.java, reader))
    }
    private fun reader(): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "provider-progress")
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        return UsbReaderDescriptor(device, 1, CardWire.CTAP)
    }

    @Test fun queuedOldGenerationAndTerminalProgressCannotReviveHintState() {
        val activity = activity()
        update(activity, 1L, null, true)
        assertTrue(required(activity))
        update(activity, 1L, null, false)
        assertFalse(required(activity))
        assertNull(message(activity))
        Handler(Looper.getMainLooper()).post { update(activity, 1L, null, true) }
        ReflectionHelpers.setField(activity, "presenceGeneration", 2L)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(required(activity))
        assertNull(message(activity))
        workerActive(activity, false)
        update(activity, 2L, null, true)
        assertFalse(required(activity))
        assertNull(message(activity))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setBusy", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        update(activity, 2L, null, true)
        assertFalse(required(activity))
        assertNull(message(activity))
        ReflectionHelpers.setField(activity, "completed", true)
        update(activity, 2L, null, true)
        assertFalse(required(activity))
        assertNull(message(activity))
    }

    @Test fun samePathReplacementAndCancellationRejectCapturedReaderProgress() {
        val activity = activity()
        val original = reader()
        selected(activity, original)
        update(activity, 1L, original, true)
        assertTrue(required(activity))
        assertEquals(cc.cardkey.R.string.status_hidUserPresenceRequired, message(activity))
        UsbAuthenticatorReader.invalidate(original)
        val replacement = reader()
        selected(activity, replacement)
        update(activity, 1L, original, true)
        assertFalse(required(activity))
        assertNull(message(activity))
        update(activity, 1L, replacement, true)
        assertTrue(required(activity))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelRequest")
        assertFalse(required(activity))
        assertNull(message(activity))
        update(activity, 1L, replacement, true)
        assertFalse(required(activity))
        assertNull(message(activity))
    }
}
