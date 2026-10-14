package cc.cardkey.provider

import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Medium
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.transport.UsbReaderDescriptor
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProviderRecognitionActivityTest {
    private fun state(activity: ProviderActivity, name: String, value: Boolean) =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "set$name", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, value))
    private fun activity(): ProviderActivity = Robolectric.buildActivity(ProviderActivity::class.java,
        Intent().setAction(ProviderRequests.ACTION_GET)).get().also {
        ReflectionHelpers.setField(it, "active", true)
        ReflectionHelpers.setField(it, "presenceGeneration", 1L)
        ReflectionHelpers.setField(it, "clientData", ClientData(byteArrayOf(), ByteArray(32), true))
        state(it, "Loading", false)
        state(it, "Busy", true)
        val runner = ReflectionHelpers.getField<ProviderOperationRunner>(it, "runner")
        ReflectionHelpers.getField<AtomicReference<CardChannel?>>(runner, "channel").set(object : CardChannel {
            override val medium = Medium.NFC
            override val maxCommandBytes = 261
            override fun transmit(command: ByteArray): ByteArray = error("No IO")
            override fun close() = Unit
        })
    }
    private fun observed(activity: ProviderActivity): ProviderRecognition? = ReflectionHelpers.callInstanceMethod(activity, "getRecognition")
    private fun pin(activity: ProviderActivity): String = ReflectionHelpers.callInstanceMethod(activity, "getPin")
    private fun input(activity: ProviderActivity) = ReflectionHelpers.callInstanceMethod<Unit>(activity, "setPin",
        ReflectionHelpers.ClassParameter.from(String::class.java, "1234"))
    private fun publish(activity: ProviderActivity, reader: UsbReaderDescriptor? = null, generation: Long = 1L) {
        ProviderActivity::class.java.getDeclaredMethod("updateRecognition", Long::class.javaPrimitiveType,
            UsbReaderDescriptor::class.java, ProviderRecognition::class.java).apply { isAccessible = true }
            .invoke(activity, generation, reader, ProviderRecognition(true))
    }
    private fun reader(): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "provider-recognition")
        ReflectionHelpers.setField(device, "mConfigurations", emptyArray<UsbConfiguration>())
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        return UsbReaderDescriptor(device, 1, CardWire.CTAP)
    }
    private fun selected(activity: ProviderActivity, reader: UsbReaderDescriptor) =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setSelected", ReflectionHelpers.ClassParameter.from(UsbReaderDescriptor::class.java, reader))

    @Test fun staleInactivePermissionAndUnverifiedCallbacksCannotEnableSecretInput() {
        for (retirement in listOf("generation", "inactive", "permission", "rejected", "caller", "complete")) {
            val activity = activity()
            assertNull(observed(activity))
            when (retirement) {
                "generation" -> ReflectionHelpers.setField(activity, "presenceGeneration", 2L)
                "inactive" -> ReflectionHelpers.setField(activity, "active", false)
                "permission" -> state(activity, "PermissionPending", true)
                "rejected" -> state(activity, "Rejected", true)
                "caller" -> ReflectionHelpers.setField(activity, "clientData", null)
                "complete" -> ReflectionHelpers.setField(activity, "completed", true)
            }
            publish(activity)
            assertNull(observed(activity))
            assertEquals("", pin(activity))
            ReflectionHelpers.getField<ProviderOperationRunner>(activity, "runner").cancel()
        }
    }

    @Test fun cancelAndPauseRetireRecognitionClearInputAndRejectQueuedCompletion() {
        for (method in listOf("cancelRequest", "onPause")) {
            val activity = activity()
            publish(activity)
            assertEquals(ProviderRecognition(true), observed(activity))
            input(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, method)
            assertNull(observed(activity))
            assertEquals("", pin(activity))
            publish(activity)
            assertNull(observed(activity))
            assertFalse(ReflectionHelpers.getField<ProviderOperationRunner>(activity, "runner").isActive)
        }
    }

    @Test fun detachRetiresRecognitionAndSamePathReplacementCannotRepublishOldObservation() {
        val activity = activity()
        val original = reader()
        selected(activity, original)
        publish(activity, original)
        assertNotNull(observed(activity))
        input(activity)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(activity, "receiver")
        receiver.onReceive(activity, Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, original.device))
        assertNull(observed(activity))
        assertEquals("", pin(activity))
        val replacement = reader()
        selected(activity, replacement)
        publish(activity, original)
        assertNull(observed(activity))
        assertFalse(UsbAuthenticatorReader.isCurrent(activity.getSystemService(UsbManager::class.java), original))
    }
}
