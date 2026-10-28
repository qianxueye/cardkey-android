package cc.cardkey.ui

import android.content.res.Resources
import cc.cardkey.R
import cc.cardkey.protocol.CardWire
import cc.cardkey.transport.UsbReaderDescriptor

internal fun UsbReaderDescriptor.displayLabel(resources: Resources): String = resources.getString(
    R.string.reader_usbInterface, device.productName ?: "USB",
    if (wire == CardWire.APDU) "CCID" else "FIDO HID", interfaceId,
)
