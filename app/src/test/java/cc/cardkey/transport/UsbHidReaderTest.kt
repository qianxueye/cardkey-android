package cc.cardkey.transport

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import cc.cardkey.MainActivity
import cc.cardkey.provider.ProviderActivity
import cc.cardkey.ui.ManagerViewModel
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUsbDeviceConnection
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [UsbHidReaderTest.DescriptorConnection::class])
class UsbHidReaderTest {
    private lateinit var manager: UsbManager

    @Before fun setup() {
        manager = RuntimeEnvironment.getApplication().getSystemService(Context.USB_SERVICE) as UsbManager
        DescriptorConnection.report = bytes(0x06, 0xd0, 0xf1, 0x09, 0x01, 0xa1, 0x01,
            0x09, 0x20, 0x15, 0, 0x26, 0xff, 0, 0x75, 8, 0x95, 64, 0x81, 2,
            0x09, 0x21, 0x15, 0, 0x26, 0xff, 0, 0x75, 8, 0x95, 64, 0x91, 2, 0xc0)
        DescriptorConnection.rawReads = 0
        DescriptorConnection.claims = 0
        DescriptorConnection.closes = 0
        DescriptorConnection.shortRead = false
    }

    @Test fun unpermittedCandidateIsListedWithoutOpeningAndCannotInitialize() {
        val device = device()
        shadowOf(manager).addOrUpdateUsbDevice(device, false)
        val candidate = UsbHidReader.discover(manager).single()
        assertEquals(CardWire.CTAP, candidate.wire)
        assertEquals(0, DescriptorConnection.rawReads)
        try { UsbHidReader.connect(manager, candidate); fail() }
        catch (failure: TransportException) { assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch) }
        assertEquals(0, DescriptorConnection.rawReads)
        assertEquals(0, DescriptorConnection.claims)
    }

    @Test fun permittedDiscoveryNeverOpensOrReadsDeviceOnUiThread() {
        shadowOf(manager).addOrUpdateUsbDevice(device(), true)
        assertEquals(1, UsbHidReader.discover(manager).size)
        DescriptorConnection.report[1] = 1
        assertEquals(1, UsbHidReader.discover(manager).size)
        assertEquals(0, DescriptorConnection.rawReads)
        assertEquals(0, DescriptorConnection.closes)
        assertEquals(0, DescriptorConnection.claims)
    }

    @Test fun connectRevalidatesFidoUsageBeforeClaimOrInitAndRejectsTruncatedDescriptor() {
        val device = device()
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        val candidate = UsbHidReader.discover(manager).single()
        for (shortRead in listOf(true, false)) {
            DescriptorConnection.shortRead = shortRead
            if (!shortRead) DescriptorConnection.report[4] = 2
            try { UsbHidReader.connect(manager, candidate); fail() }
            catch (failure: TransportException) { assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch) }
            assertEquals(0, DescriptorConnection.claims)
        }
        assertEquals(2, DescriptorConnection.closes)
    }

    @Test fun discoveryRejectsNonInterruptWrongSizeDuplicateDirectionAndAlternateInterfaces() {
        val invalid = listOf(
            intf(arrayOf(endpoint(0x81, type = 2), endpoint(1))),
            intf(arrayOf(endpoint(0x81, size = 63), endpoint(1))),
            intf(arrayOf(endpoint(0x81), endpoint(0x82))),
            intf(arrayOf(endpoint(0x81))),
            intf(arrayOf(endpoint(0x81), endpoint(1), endpoint(2))),
            intf(arrayOf(endpoint(0x81, interval = 0), endpoint(1))),
            intf(alternate = 1),
            intf(subclass = 1),
        )
        invalid.forEachIndexed { index, intf ->
            shadowOf(manager).addOrUpdateUsbDevice(device(arrayOf(intf), "/dev/bus/usb/001/${index + 1}"), false)
        }
        assertTrue(UsbHidReader.discover(manager).isEmpty())
        assertEquals(0, DescriptorConnection.rawReads)
    }

    @Test fun samePathReattachHasNewRouteAndOldDescriptorCannotOpenEitherBackend() {
        val oldDevice = device()
        shadowOf(manager).addOrUpdateUsbDevice(oldDevice, true)
        val old = UsbAuthenticatorReader.discover(manager).single()
        val oldApdu = UsbReaderDescriptor(oldDevice, 1)
        shadowOf(manager).removeUsbDevice(oldDevice)
        UsbAuthenticatorReader.invalidate(old)
        UsbAuthenticatorReader.invalidate(old)
        shadowOf(manager).addOrUpdateUsbDevice(device(), true)
        val fresh = UsbAuthenticatorReader.discover(manager).single()
        assertNotEquals(old.routeKey, fresh.routeKey)
        UsbAuthenticatorReader.invalidate(old)
        assertTrue(UsbAuthenticatorReader.isCurrent(manager, fresh))
        assertFalse(UsbAuthenticatorReader.isCurrent(manager, old))
        val reads = DescriptorConnection.rawReads
        for (connect in listOf<() -> Unit>(
            { UsbAuthenticatorReader.connect(manager, old) },
            { UsbHidReader.connect(manager, old) },
            { UsbCcidReader.connect(manager, oldApdu) },
        )) {
            try { connect(); fail() }
            catch (failure: TransportException) { assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch) }
        }
        assertEquals(reads, DescriptorConnection.rawReads)
        assertEquals(0, DescriptorConnection.claims)
    }

    @Test fun discoveryObservingAbsenceAlsoRetiresAttachmentGeneration() {
        val oldDevice = device()
        shadowOf(manager).addOrUpdateUsbDevice(oldDevice, false)
        val old = UsbAuthenticatorReader.discover(manager).single()
        shadowOf(manager).removeUsbDevice(oldDevice)
        assertTrue(UsbAuthenticatorReader.discover(manager).isEmpty())
        shadowOf(manager).addOrUpdateUsbDevice(device(), false)
        val fresh = UsbAuthenticatorReader.discover(manager).single()
        assertNotEquals(old.routeKey, fresh.routeKey)
        assertFalse(UsbAuthenticatorReader.isCurrent(manager, old))
    }

    @Test fun bothActivityDetachCallbacksRetireOldSelectionAndPermissionWithoutInvalidatingReplacement() {
        val oldDevice = device()
        shadowOf(manager).addOrUpdateUsbDevice(oldDevice, true)
        val old = UsbAuthenticatorReader.discover(manager).single()
        val model = ManagerViewModel(RuntimeEnvironment.getApplication())
        model.refreshReaders()
        model.selectReader(old)
        val main = Robolectric.buildActivity(MainActivity::class.java).get()
        ReflectionHelpers.setField(main, "model\$delegate", lazyOf(model))
        ReflectionHelpers.setField(main, "usb\$delegate", lazyOf(manager))
        ReflectionHelpers.setField(main, "requestedReader", old)
        val provider = Robolectric.buildActivity(ProviderActivity::class.java).get()
        ReflectionHelpers.setField(provider, "usb\$delegate", lazyOf(manager))
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setReaders",
            ReflectionHelpers.ClassParameter.from(List::class.java, listOf(old)))
        ReflectionHelpers.setField(provider, "pendingReader", old)
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setSelected",
            ReflectionHelpers.ClassParameter.from(UsbReaderDescriptor::class.java, old))
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setBusy",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        shadowOf(manager).addOrUpdateUsbDevice(device(), true)
        val detach = Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, oldDevice)
        val mainReceiver = ReflectionHelpers.getField<BroadcastReceiver>(main, "receiver")
        val providerReceiver = ReflectionHelpers.getField<BroadcastReceiver>(provider, "receiver")
        mainReceiver.onReceive(RuntimeEnvironment.getApplication(), detach)
        val replacement = UsbAuthenticatorReader.discover(manager).single()
        providerReceiver.onReceive(RuntimeEnvironment.getApplication(), detach)
        assertTrue(UsbAuthenticatorReader.isCurrent(manager, replacement))
        assertNull(model.state.value.selectedReader)
        assertNull(ReflectionHelpers.getField<UsbReaderDescriptor?>(main, "requestedReader"))
        assertNull(ReflectionHelpers.getField<UsbReaderDescriptor?>(provider, "pendingReader"))
        assertNull(ReflectionHelpers.callInstanceMethod<UsbReaderDescriptor?>(provider, "getSelected"))
        assertTrue(ReflectionHelpers.getField(provider, "requestCancelled"))
        assertTrue(ReflectionHelpers.callInstanceMethod(provider, "getBusy"))
        assertFalse(ReflectionHelpers.getField(provider, "completed"))
        assertFalse(UsbAuthenticatorReader.isCurrent(manager, old))
    }

    @Test fun latePermissionCallbackCannotSelectOldGenerationOrConsumeNewPendingRequest() {
        val oldDevice = device()
        shadowOf(manager).addOrUpdateUsbDevice(oldDevice, true)
        val old = UsbAuthenticatorReader.discover(manager).single()
        UsbAuthenticatorReader.invalidate(old)
        val fresh = UsbAuthenticatorReader.discover(manager).single()
        val model = ManagerViewModel(RuntimeEnvironment.getApplication())
        val main = Robolectric.buildActivity(MainActivity::class.java).get()
        ReflectionHelpers.setField(main, "model\$delegate", lazyOf(model))
        ReflectionHelpers.setField(main, "usb\$delegate", lazyOf(manager))
        ReflectionHelpers.setField(main, "requestedReader", fresh)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(main, "receiver")
        val action = RuntimeEnvironment.getApplication().packageName + ".USB_PERMISSION"
        receiver.onReceive(RuntimeEnvironment.getApplication(), Intent(action).setIdentifier(old.routeKey))
        assertSame(fresh, ReflectionHelpers.getField<UsbReaderDescriptor?>(main, "requestedReader"))
        assertNull(model.state.value.selectedReader)
        receiver.onReceive(RuntimeEnvironment.getApplication(), Intent(action).setIdentifier(fresh.routeKey))
        assertEquals(fresh.routeKey, model.state.value.selectedReader?.routeKey)
        assertNull(ReflectionHelpers.getField<UsbReaderDescriptor?>(main, "requestedReader"))
        val provider = Robolectric.buildActivity(ProviderActivity::class.java).get()
        ReflectionHelpers.setField(provider, "usb\$delegate", lazyOf(manager))
        ReflectionHelpers.setField(provider, "pendingReader", fresh)
        val providerReceiver = ReflectionHelpers.getField<BroadcastReceiver>(provider, "receiver")
        val providerAction = RuntimeEnvironment.getApplication().packageName + ".PROVIDER_USB_PERMISSION"
        providerReceiver.onReceive(RuntimeEnvironment.getApplication(), Intent(providerAction).setIdentifier(old.routeKey))
        assertSame(fresh, ReflectionHelpers.getField<UsbReaderDescriptor?>(provider, "pendingReader"))
        assertNull(ReflectionHelpers.callInstanceMethod<UsbReaderDescriptor?>(provider, "getSelected"))
        providerReceiver.onReceive(RuntimeEnvironment.getApplication(), Intent(providerAction).setIdentifier(fresh.routeKey))
        assertEquals(fresh.routeKey, ReflectionHelpers.callInstanceMethod<UsbReaderDescriptor?>(provider, "getSelected")?.routeKey)
        assertNull(ReflectionHelpers.getField<UsbReaderDescriptor?>(provider, "pendingReader"))
    }

    @Test fun routeSeparatesWireAndInterfaceAndHidNeverUsesApduRemovalProbe() {
        val device = device()
        val hid = UsbReaderDescriptor(device, 1, CardWire.CTAP)
        val apdu = UsbReaderDescriptor(device, 1, CardWire.APDU)
        val other = UsbReaderDescriptor(device, 2, CardWire.CTAP)
        assertEquals(3, setOf(hid.routeKey, apdu.routeKey, other.routeKey).size)
        assertFalse(UsbAuthenticatorReader.isCardAbsent(manager, hid))
        assertEquals(0, DescriptorConnection.rawReads)
    }

    private fun device(interfaces: Array<UsbInterface> = arrayOf(intf()), name: String = "/dev/bus/usb/001/001") =
        Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", name)
            ReflectionHelpers.setField(it, "mInterfaces", interfaces)
        }

    private fun intf(endpoints: Array<UsbEndpoint> = arrayOf(endpoint(0x81), endpoint(1)), alternate: Int = 0, subclass: Int = 0) =
        Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", 1)
            ReflectionHelpers.setField(it, "mAlternateSetting", alternate)
            ReflectionHelpers.setField(it, "mClass", 3)
            ReflectionHelpers.setField(it, "mSubclass", subclass)
            ReflectionHelpers.setField(it, "mProtocol", 0)
            ReflectionHelpers.setField(it, "mEndpoints", endpoints)
        }

    private fun endpoint(address: Int, type: Int = 3, size: Int = 64, interval: Int = 5) =
        Shadow.newInstanceOf(UsbEndpoint::class.java).also {
            ReflectionHelpers.setField(it, "mAddress", address)
            ReflectionHelpers.setField(it, "mAttributes", type)
            ReflectionHelpers.setField(it, "mMaxPacketSize", size)
            ReflectionHelpers.setField(it, "mInterval", interval)
        }

    @Implements(UsbDeviceConnection::class)
    class DescriptorConnection : ShadowUsbDeviceConnection() {
        @Implementation fun getRawDescriptors(): ByteArray {
            rawReads++
            return bytes(9, 4, 1, 0, 2, 3, 0, 0, 0, 9, 0x21, 0x11, 1, 0, 1, 0x22, report.size, 0)
        }
        @Implementation override fun controlTransfer(requestType: Int, request: Int, value: Int,
            index: Int, buffer: ByteArray, length: Int, timeout: Int): Int {
            assertEquals(0x81, requestType); assertEquals(6, request)
            assertEquals(0x2200, value); assertEquals(1, index)
            assertEquals(report.size, length)
            report.copyInto(buffer)
            return if (shortRead) report.size - 1 else report.size
        }
        @Implementation override fun claimInterface(intf: UsbInterface, force: Boolean): Boolean { claims++; return false }
        @Implementation fun close() { closes++ }
        companion object {
            var report = byteArrayOf()
            var rawReads = 0
            var claims = 0
            var closes = 0
            var shortRead = false
        }
    }
    companion object { private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray() }
}
