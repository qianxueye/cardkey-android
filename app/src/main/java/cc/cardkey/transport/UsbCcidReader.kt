package cc.cardkey.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException
import java.util.concurrent.atomic.AtomicBoolean

class UsbReaderDescriptor internal constructor(
    val device: UsbDevice,
    val interfaceId: Int,
    val wire: CardWire = CardWire.APDU,
) {
    val routeKey: String = "usb:${device.deviceName.length}:${device.deviceName}:$interfaceId:${wire.name}"
}

object UsbCcidReader {
    fun discover(manager: UsbManager): List<UsbReaderDescriptor> = manager.deviceList.values
        .filter { it.vendorId == 0x04e6 && it.productId == 0x5810 }
        .flatMap { device ->
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { isCcid(it) && endpoints(it) != null }
                .map { UsbReaderDescriptor(device, it.id) }
        }

    fun connect(
        manager: UsbManager,
        descriptor: UsbReaderDescriptor,
        allowInitialPowerOn: Boolean = false,
        timeoutMillis: Int = 15000,
    ): CardChannel {
        val channel = openChannel(manager, descriptor, timeoutMillis)
        try {
            channel.initialize(allowInitialPowerOn)
            return channel
        } catch (error: Exception) {
            channel.close()
            throw TransportException(Dispatch.NOT_DISPATCHED, error)
        }
    }

    fun isCardAbsent(
        manager: UsbManager,
        descriptor: UsbReaderDescriptor,
        timeoutMillis: Int = 1500,
    ): Boolean = try {
        openChannel(manager, descriptor, timeoutMillis).use { it.isCardAbsent() }
    } catch (_: Exception) {
        false
    }

    private fun openChannel(
        manager: UsbManager,
        descriptor: UsbReaderDescriptor,
        timeoutMillis: Int,
    ): CcidChannel {
        val device = descriptor.device
        if (descriptor.wire != CardWire.APDU || device.vendorId != 0x04e6 || device.productId != 0x5810 ||
            !manager.hasPermission(device) || Thread.currentThread().isInterrupted) {
            throw TransportException(Dispatch.NOT_DISPATCHED)
        }
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .singleOrNull { it.id == descriptor.interfaceId && isCcid(it) }
            ?: throw TransportException(Dispatch.NOT_DISPATCHED)
        val endpoints = endpoints(intf) ?: throw TransportException(Dispatch.NOT_DISPATCHED)
        val connection = try {
            manager.openDevice(device) ?: throw TransportException(Dispatch.NOT_DISPATCHED)
        } catch (error: Exception) {
            throw TransportException(Dispatch.NOT_DISPATCHED, error)
        }
        var claimed = false
        try {
            val capabilities = CcidCapabilities.parse(connection.rawDescriptors, intf.id)
            require(timeoutMillis in 1..60000)
            claimed = connection.claimInterface(intf, false)
            check(claimed)
            val io = object : CcidIo {
                private val closed = AtomicBoolean(false)
                override fun write(message: ByteArray, timeoutMillis: Int): Int =
                    connection.bulkTransfer(endpoints.second, message, message.size, timeoutMillis)
                override fun read(buffer: ByteArray, timeoutMillis: Int): Int =
                    connection.bulkTransfer(endpoints.first, buffer, buffer.size, timeoutMillis)
                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        try { connection.releaseInterface(intf) } finally { connection.close() }
                    }
                }
            }
            return CcidChannel(io, capabilities, endpoints.first.maxPacketSize, timeoutMillis)
        } catch (error: Exception) {
            if (claimed) runCatching { connection.releaseInterface(intf) }
            connection.close()
            throw TransportException(Dispatch.NOT_DISPATCHED, error)
        }
    }

    private fun isCcid(intf: UsbInterface): Boolean = intf.interfaceClass == 0x0b &&
        intf.interfaceSubclass == 0 && intf.interfaceProtocol == 0 && intf.alternateSetting == 0

    private fun endpoints(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val bulk = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = bulk.singleOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val output = bulk.singleOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        return input to output
    }
}
