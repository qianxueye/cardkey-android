package cc.cardkey.transport

import android.content.Context
import android.hardware.usb.*
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.FidoClient
import cc.cardkey.protocol.piv.PivClient
import cc.cardkey.provider.ProviderActivity
import cc.cardkey.ui.ManagerViewModel
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
@Config(sdk = [34], shadows = [UsbCcidReaderTest.DescriptorConnection::class])
class UsbCcidReaderTest {
    private lateinit var manager: UsbManager

    @Before fun setup() {
        manager = RuntimeEnvironment.getApplication().getSystemService(Context.USB_SERVICE) as UsbManager
        DescriptorConnection.raw = descriptor()
        DescriptorConnection.rawReads = 0
        DescriptorConnection.claims = 0
        DescriptorConnection.closes = 0
        DescriptorConnection.writes.clear()
    }

    @Test fun unrelatedVendorsAndCompositeInterfacesReachManagerAndProviderWithoutOpening() {
        for ((index, ids) in listOf(0x072f to 0x2200, 0x076b to 0x3021).withIndex()) {
            shadowOf(manager).addOrUpdateUsbDevice(device(ids.first, ids.second, index,
                arrayOf(intf(id = 0, klass = 0xff), intf())), false)
        }
        val model = ManagerViewModel(RuntimeEnvironment.getApplication())
        model.refreshReaders()
        assertEquals(2, model.state.value.readers.size)
        assertTrue(model.state.value.readers.all { it.interfaceId == 1 && it.wire == CardWire.APDU })
        val provider = Robolectric.buildActivity(ProviderActivity::class.java).get()
        ReflectionHelpers.setField(provider, "usb\$delegate", lazyOf(manager))
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "refreshReaders")
        val readers = ReflectionHelpers.callInstanceMethod<List<UsbReaderDescriptor>>(provider, "getReaders")
        assertEquals(model.state.value.readers.map { it.routeKey }.toSet(), readers.map { it.routeKey }.toSet())
        assertEquals(0, DescriptorConnection.rawReads)
        for (reader in readers) rejected { UsbAuthenticatorReader.connect(manager, reader) }
        assertEquals(0, DescriptorConnection.claims)
    }

    @Test fun genericConnectionRunsPublicFidoAndPivWithoutPromotingItsMedium() {
        for ((index, ids) in listOf(0x072f to 0x2200, 0x076b to 0x3021).withIndex()) {
            val device = device(ids.first, ids.second, index)
            shadowOf(manager).addOrUpdateUsbDevice(device, true)
            val reader = UsbAuthenticatorReader.discover(manager).single { it.device.deviceName == device.deviceName }
            val channel = UsbAuthenticatorReader.connect(manager, reader)
            assertEquals(Medium.UNKNOWN, channel.medium)
            val result = CardSession(channel).run { session ->
                val fido = FidoClient(session)
                fido.select()
                assertEquals(listOf("FIDO_2_0"), fido.getInfo().versions)
                assertEquals("1.2.3", PivClient(session).readInfo().version)
                assertEquals(Medium.UNKNOWN, session.medium)
                val count = DescriptorConnection.writes.size
                val pin = "123456".toCharArray()
                try { PivClient(session).verifyPin(pin); fail("Public reads cannot authorize plaintext PIN") }
                catch (failure: CardFailure) { assertEquals(FailureCode.CONTACT_REQUIRED, failure.code) }
                assertTrue(pin.all { it == '\u0000' })
                assertEquals(count, DescriptorConnection.writes.size)
            }
            assertTrue("$result", result is OperationResult.Completed)
        }
        assertEquals(2, DescriptorConnection.claims)
        assertEquals(2, DescriptorConnection.closes)
        assertFalse(DescriptorConnection.writes.any { it[0] == 0x62.toByte() })
    }

    @Test fun establishedIdentivQualificationKeepsContactOperations() {
        val device = device(0x04e6, 0x5810)
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        val channel = UsbAuthenticatorReader.connect(manager, UsbAuthenticatorReader.discover(manager).single())
        assertEquals(Medium.CONTACT, channel.medium)
        val result = CardSession(channel).run { PivClient(it).verifyPin("123456".toCharArray()) }
        assertTrue("$result", result is OperationResult.Completed)
        assertTrue(DescriptorConnection.writes.any { it[0] == 0x6f.toByte() && it[11] == 0x20.toByte() })
    }

    @Test fun malformedOrUnsupportedCapabilitiesRejectBeforeClaimOrCardCommand() {
        val device = device()
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        val reader = UsbAuthenticatorReader.discover(manager).single()
        val unsupported = listOf(
            descriptor().copyOf(30), descriptor().also { it[9 + 4] = 1 },
            descriptor(features = 0x0004a), descriptor(features = 0x6004a),
            descriptor(features = 0x1004a, protocols = 1), descriptor(features = 0x40048),
            descriptor(features = 0x4000a), descriptor(messageBytes = 270),
            descriptor().also { it[3] = 1 }, descriptor().also { it[2] = 2 },
        )
        for (raw in unsupported) {
            DescriptorConnection.raw = raw
            rejected { UsbAuthenticatorReader.connect(manager, reader) }
        }
        assertEquals(0, DescriptorConnection.claims)
        assertTrue(DescriptorConnection.writes.isEmpty())
        assertEquals(unsupported.size, DescriptorConnection.closes)
    }

    @Test fun unsupportedInterfaceAndEndpointShapesAreNotListed() {
        val invalid = listOf(
            intf(alternate = 1), intf(klass = 0xff), intf(subclass = 1), intf(protocol = 1),
            intf(endpoints = arrayOf(endpoint(0x81))),
            intf(endpoints = arrayOf(endpoint(0x81), endpoint(0x82))),
            intf(endpoints = arrayOf(endpoint(0x81), endpoint(1), endpoint(2))),
            intf(endpoints = arrayOf(endpoint(0x81, type = 3), endpoint(1))),
            intf(endpoints = arrayOf(endpoint(0x81, size = 0), endpoint(1))),
            intf(endpoints = arrayOf(endpoint(0x81), endpoint(1, size = 1025))),
        )
        invalid.forEachIndexed { index, intf ->
            shadowOf(manager).addOrUpdateUsbDevice(device(index = index, interfaces = arrayOf(intf)), true)
        }
        assertTrue(UsbCcidReader.discover(manager).isEmpty())
        assertEquals(0, DescriptorConnection.rawReads)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Reader must be rejected") }
        catch (failure: TransportException) { assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch) }
    }

    private fun device(vendor: Int = 0x072f, product: Int = 0x2200, index: Int = 0,
                       interfaces: Array<UsbInterface> = arrayOf(intf())) =
        Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "/dev/bus/usb/003/$index")
            ReflectionHelpers.setField(it, "mVendorId", vendor)
            ReflectionHelpers.setField(it, "mProductId", product)
            ReflectionHelpers.setField(it, "mInterfaces", interfaces)
        }

    private fun intf(id: Int = 1, alternate: Int = 0, klass: Int = 0x0b, subclass: Int = 0, protocol: Int = 0,
                     endpoints: Array<UsbEndpoint> = arrayOf(endpoint(0x81), endpoint(1))) =
        Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", id)
            ReflectionHelpers.setField(it, "mAlternateSetting", alternate)
            ReflectionHelpers.setField(it, "mClass", klass)
            ReflectionHelpers.setField(it, "mSubclass", subclass)
            ReflectionHelpers.setField(it, "mProtocol", protocol)
            ReflectionHelpers.setField(it, "mEndpoints", endpoints)
        }

    private fun endpoint(address: Int, type: Int = 2, size: Int = 64) =
        Shadow.newInstanceOf(UsbEndpoint::class.java).also {
            ReflectionHelpers.setField(it, "mAddress", address)
            ReflectionHelpers.setField(it, "mAttributes", type)
            ReflectionHelpers.setField(it, "mMaxPacketSize", size)
            ReflectionHelpers.setField(it, "mInterval", 0)
        }

    @Implements(UsbDeviceConnection::class)
    class DescriptorConnection : ShadowUsbDeviceConnection() {
        private var response = byteArrayOf()
        @Implementation fun getRawDescriptors(): ByteArray { rawReads++; return raw }
        @Implementation override fun claimInterface(intf: UsbInterface, force: Boolean): Boolean { claims++; return true }
        @Implementation override fun releaseInterface(intf: UsbInterface): Boolean = true
        @Implementation fun close() { closes++ }
        @Implementation override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                val frame = buffer.copyOf(length)
                writes += frame
                val type = frame[0].toInt() and 255
                val data = when (type) {
                    0x65 -> byteArrayOf()
                    0x6c -> ByteArray(7)
                    0x6f -> {
                        val apdu = frame.copyOfRange(10, frame.size)
                        when (apdu[1].toInt() and 255) {
                            0xa4 -> if (apdu[5] == 0xa0.toByte() && apdu[8] == 6.toByte())
                                "FIDO_2_0".toByteArray() + "9000".hexBytes() else "9000".hexBytes()
                            0x10 -> "00a20181684649444f5f325f300350${"00".repeat(16)}9000".hexBytes()
                            0xfd -> "0102039000".hexBytes()
                            0xf8 -> "000000079000".hexBytes()
                            0xf7 -> if (apdu[3] == 0x9b.toByte()) "0101030501019000".hexBytes() else "050101060203039000".hexBytes()
                            0x20 -> "9000".hexBytes()
                            else -> error("Unexpected APDU")
                        }
                    }
                    else -> error("Unexpected CCID command")
                }
                response = ByteArray(10).also {
                    it[0] = (if (type == 0x65) 0x81 else if (type == 0x6c) 0x82 else 0x80).toByte()
                    for (i in 0..3) it[1 + i] = (data.size ushr (8 * i)).toByte()
                    it[6] = frame[6]
                    it[9] = (if (type == 0x6c) 1 else 0).toByte()
                } + data
                return length
            }
            val count = minOf(length, response.size)
            response.copyInto(buffer, endIndex = count)
            response = response.copyOfRange(count, response.size)
            return count
        }
        companion object {
            var raw = byteArrayOf()
            var rawReads = 0
            var claims = 0
            var closes = 0
            val writes = mutableListOf<ByteArray>()
        }
    }

    companion object {
        private fun descriptor(features: Int = 0x4004a, protocols: Int = 3, messageBytes: Int = 271): ByteArray {
            val ccid = ByteArray(54)
            ccid[0] = 54; ccid[1] = 0x21; ccid[2] = 0x10; ccid[3] = 1
            for ((offset, value) in listOf(6 to protocols, 40 to features, 44 to messageBytes))
                for (i in 0..3) ccid[offset + i] = (value ushr (i * 8)).toByte()
            return byteArrayOf(9, 4, 1, 0, 2, 0x0b, 0, 0, 0) + ccid
        }
    }
}
