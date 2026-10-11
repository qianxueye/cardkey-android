package cc.cardkey.transport

import android.hardware.usb.UsbManager
import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.CardWire

object UsbAuthenticatorReader {
    fun discover(manager: UsbManager): List<UsbReaderDescriptor> =
        UsbCcidReader.discover(manager) + UsbHidReader.discover(manager)

    fun connect(manager: UsbManager, descriptor: UsbReaderDescriptor,
        allowInitialPowerOn: Boolean = false, timeoutMillis: Int = 15000): CardChannel = when (descriptor.wire) {
        CardWire.APDU -> UsbCcidReader.connect(manager, descriptor, allowInitialPowerOn, timeoutMillis)
        CardWire.CTAP -> UsbHidReader.connect(manager, descriptor, timeoutMillis)
    }

    fun isCardAbsent(manager: UsbManager, descriptor: UsbReaderDescriptor, timeoutMillis: Int = 1500): Boolean =
        descriptor.wire == CardWire.APDU && UsbCcidReader.isCardAbsent(manager, descriptor, timeoutMillis)
}
