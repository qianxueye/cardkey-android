package cc.cardkey.provider

import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Medium
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private fun publish(activity: ProviderActivity, reader: UsbReaderDescriptor? = null, generation: Long = 1L, observation: ProviderRecognition = ProviderRecognition(true)) {
        ProviderActivity::class.java.getDeclaredMethod("updateRecognition", Long::class.javaPrimitiveType,
            UsbReaderDescriptor::class.java, ProviderRecognition::class.java).apply { isAccessible = true }
            .invoke(activity, generation, reader, observation)
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
    @Test fun verifiedNoPinPolicyContinuesAutomaticallyWithinTheExistingClaimAndSession() = runTest {
        val activity = activity()
        val runner = ReflectionHelpers.getField<ProviderOperationRunner>(activity, "runner")
        val data = ClientData(byteArrayOf(), ByteArray(32), true)
        val request = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED)
        val registry = RequestRegistry()
        val claim = registry.claim(registry.offer(OfferedRequest(ProviderRequestKind.GET, "{}", data.hash,
            CallerSnapshot("synthetic.provider", setOf("certificate"), null), "fixture")))
        val channel = FidoChannel { code, _ -> when (code) {
            4 -> success(info())
            2 -> success(cborMap(1 to descriptor(byteArrayOf(1)),
                2 to (sha256("example.com".toByteArray()) + byteArrayOf(1, 0, 0, 0, 1)).cbor(),
                3 to byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 1).cbor()))
            else -> error("No-PIN policy queried or verified a PIN")
        } }
        var observations = 0
        val operation = async { runner.recognizeAndExecute(null, request, data, claim = claim,
            onRecognized = { observed -> observations++; assertFalse(observed.pinRequired); publish(activity, observation = observed) }) {
            assertFalse(claim.reserveTransport())
            channel
        } }
        val result = operation.await() as OperationResult.Completed
        assertEquals(1, observations)
        assertEquals(listOf(4, 2), channel.commands.map { it.first })
        assertEquals("", pin(activity))
        assertNull(observed(activity))
        assertTrue(channel.closed)
        (result.value as ProviderOutput.Assertions).values.forEach { it.close() }
        claim.finish(cancelled = false)
    }
    @Test fun deniedPermissionClearsPendingStateWithoutStartingOrRebindingAnOperation() {
        val activity = Robolectric.buildActivity(ProviderActivity::class.java,
            Intent().setAction(ProviderRequests.ACTION_GET)).get()
        val reader = reader()
        val usb = activity.getSystemService(UsbManager::class.java)
        shadowOf(usb).addOrUpdateUsbDevice(reader.device, false)
        ReflectionHelpers.setField(activity, "pendingReader", reader)
        state(activity, "PermissionPending", true)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(activity, "receiver")
        val action = ReflectionHelpers.callInstanceMethod<String>(activity, "getPermissionAction")
        receiver.onReceive(activity, Intent(action).setIdentifier("stale-reader"))
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getPermissionPending"))
        receiver.onReceive(activity, Intent(action).setIdentifier(reader.routeKey))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getPermissionPending"))
        assertEquals(cc.cardkey.R.string.reader_permissionDenied,
            ReflectionHelpers.callInstanceMethod<Int?>(activity, "getConnectionMessage"))
        assertFalse(ReflectionHelpers.getField<ProviderOperationRunner>(activity, "runner").isActive)
        assertEquals("", pin(activity))
    }
    @Test fun unavailableNfcKeepsTheVerifiedTaskRecoverableWithoutClaimingTransportSuccess() {
        org.robolectric.shadows.ShadowNfcAdapter.setNfcHardwareExists(false)
        val activity = Robolectric.buildActivity(ProviderActivity::class.java).get()
        ReflectionHelpers.setField(activity, "active", true)
        ReflectionHelpers.setField(activity, "clientData", ClientData(byteArrayOf(), ByteArray(32), true))
        state(activity, "Loading", false)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "recognize")
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getBusy"))
        assertEquals(cc.cardkey.R.string.error_nfcUnavailable,
            ReflectionHelpers.callInstanceMethod<Int?>(activity, "getConnectionMessage"))
        assertFalse(ReflectionHelpers.getField<ProviderOperationRunner>(activity, "runner").isActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun finalAttemptRequiresItsCurrentVisibleApprovalAndDeclineNeverAuthenticates() = runTest {
        for (retirement in listOf("decline", "background", "approve")) {
            val approve = retirement == "approve"
            val activity = activity()
            val runner = ReflectionHelpers.getField<ProviderOperationRunner>(activity, "runner")
            val data = ClientData(byteArrayOf(), ByteArray(32), true)
            val request = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.REQUIRED)
            val registry = RequestRegistry()
            val claim = registry.claim(registry.offer(OfferedRequest(ProviderRequestKind.GET, "{}", data.hash,
                CallerSnapshot("synthetic.provider", setOf("certificate"), null), "fixture")))
            val server = FidoClientTest.PinServer(2)
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("clientPin" to true, "pinUvAuthToken" to true)))
                6 -> if (map!![2].number() == 1L) success(cborMap(3 to 1.cbor(), 4 to false.cbor())) else server.respond(map, 2, "example.com")
                2 -> success(cborMap(1 to descriptor(byteArrayOf(1)),
                    2 to (sha256("example.com".toByteArray()) + byteArrayOf(5, 0, 0, 0, 1)).cbor(),
                    3 to byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 1).cbor()))
                else -> error("Unexpected provider command")
            } }
            val operation = async { runner.recognizeAndExecute(null, request, data, claim = claim,
                onRecognized = { publish(activity, observation = it) }) { channel } }
            runCurrent()
            input(activity)
            val confirm = ProviderActivity::class.java.getDeclaredMethod("confirm", ProviderRecognition::class.java).apply { isAccessible = true }
            confirm.invoke(activity, null)
            val approval = ReflectionHelpers.callInstanceMethod<ProviderRecognition?>(activity, "getFinalAttempt")!!
            assertEquals(0, server.tokenRequests)
            assertNull(CardOperationGate.tryAcquire())
            confirm.invoke(activity, ProviderRecognition(true, 1))
            assertEquals(0, server.tokenRequests)
            if (approve) confirm.invoke(activity, approval)
            else ReflectionHelpers.callInstanceMethod<Unit>(activity, if (retirement == "background") "onPause" else "cancelRequest")
            val result = operation.await()
            if (approve) {
                assertTrue(result is OperationResult.Completed)
                assertEquals(1, server.tokenRequests)
                ((result as OperationResult.Completed).value as ProviderOutput.Assertions).values.forEach { it.close() }
            } else {
                assertEquals(FailureCode.CANCELLED, (result as OperationResult.Failed).code)
                assertEquals(0, server.tokenRequests)
            }
            assertEquals("", pin(activity))
            assertNull(ReflectionHelpers.callInstanceMethod<ProviderRecognition?>(activity, "getFinalAttempt"))
            assertTrue(channel.closed)
            claim.finish(cancelled = !approve)
        }
    }

}
