package cc.cardkey.transport

import android.hardware.usb.UsbManager
import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException

object UsbAuthenticatorReader {
    private val attachments = mutableMapOf<String, Long>()
    private var nextAttachment = 0L

    internal fun attachment(deviceName: String): Long = synchronized(attachments) {
        attachments.getOrPut(deviceName) { ++nextAttachment }
    }

    fun invalidate(descriptor: UsbReaderDescriptor) {
        synchronized(attachments) {
            if (attachments[descriptor.device.deviceName] == descriptor.attachment)
                attachments.remove(descriptor.device.deviceName)
        }
    }

    fun isCurrent(manager: UsbManager, descriptor: UsbReaderDescriptor): Boolean =
        synchronized(attachments) { attachments[descriptor.device.deviceName] == descriptor.attachment } &&
            manager.deviceList.containsKey(descriptor.device.deviceName)

    fun discover(manager: UsbManager): List<UsbReaderDescriptor> {
        val attached = manager.deviceList.keys
        synchronized(attachments) { attachments.keys.retainAll(attached) }
        return UsbCcidReader.discover(manager) + UsbHidReader.discover(manager)
    }

    fun connect(manager: UsbManager, descriptor: UsbReaderDescriptor,
        allowInitialPowerOn: Boolean = false, timeoutMillis: Int = 15000): CardChannel {
        if (!isCurrent(manager, descriptor)) throw TransportException(Dispatch.NOT_DISPATCHED)
        return when (descriptor.wire) {
            CardWire.APDU -> UsbCcidReader.connect(manager, descriptor, allowInitialPowerOn, timeoutMillis)
            CardWire.CTAP -> UsbHidReader.connect(manager, descriptor, timeoutMillis)
        }
    }

    fun isCardAbsent(manager: UsbManager, descriptor: UsbReaderDescriptor, timeoutMillis: Int = 1500): Boolean =
        descriptor.wire == CardWire.APDU && UsbCcidReader.isCardAbsent(manager, descriptor, timeoutMillis)
}
