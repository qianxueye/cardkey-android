package cc.cardkey.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException
import java.nio.ByteBuffer

object UsbHidReader {
    fun discover(manager: UsbManager): List<UsbReaderDescriptor> = manager.deviceList.values.flatMap { device ->
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { isHid(it) && endpoints(it) != null }
            .filter { intf ->
                if (!manager.hasPermission(device)) true
                else runCatching {
                    val connection = manager.openDevice(device) ?: return@runCatching false
                    try { validateDescriptor(connection, intf, 1000); true } finally { connection.close() }
                }.getOrDefault(false)
            }
            .map { UsbReaderDescriptor(device, it.id, CardWire.CTAP) }
    }

    fun connect(manager: UsbManager, descriptor: UsbReaderDescriptor, timeoutMillis: Int = 15000): CardChannel {
        val device = descriptor.device
        if (descriptor.wire != CardWire.CTAP || !manager.hasPermission(device) ||
            Thread.currentThread().isInterrupted || timeoutMillis !in 1..60000) {
            throw TransportException(Dispatch.NOT_DISPATCHED)
        }
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .singleOrNull { it.id == descriptor.interfaceId && isHid(it) }
            ?: throw TransportException(Dispatch.NOT_DISPATCHED)
        val endpoints = endpoints(intf) ?: throw TransportException(Dispatch.NOT_DISPATCHED)
        val connection = try { manager.openDevice(device) ?: throw TransportException(Dispatch.NOT_DISPATCHED) }
            catch (failure: Exception) { throw TransportException(Dispatch.NOT_DISPATCHED, failure) }
        var claimed = false
        var channel: CtaphidChannel? = null
        try {
            validateDescriptor(connection, intf, timeoutMillis)
            claimed = connection.claimInterface(intf, false)
            check(claimed)
            channel = CtaphidChannel(HidInterruptIo(AndroidInterruptConnection(connection, intf, endpoints)), timeoutMillis)
            channel.initialize()
            return channel
        } catch (failure: Exception) {
            if (channel != null) channel.close()
            else {
                if (claimed) runCatching { connection.releaseInterface(intf) }
                connection.close()
            }
            throw TransportException(Dispatch.NOT_DISPATCHED, failure)
        }
    }

    private fun validateDescriptor(connection: UsbDeviceConnection, intf: UsbInterface, timeoutMillis: Int) {
        val report = ByteArray(FidoHidDescriptor.reportLength(connection.rawDescriptors, intf.id))
        val count = connection.controlTransfer(0x81, 6, 0x2200, intf.id, report, report.size, timeoutMillis)
        require(count == report.size)
        FidoHidDescriptor.validate(report)
    }

    private fun isHid(intf: UsbInterface): Boolean = intf.interfaceClass == UsbConstants.USB_CLASS_HID &&
        intf.interfaceSubclass == 0 && intf.interfaceProtocol == 0 && intf.alternateSetting == 0

    private fun endpoints(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        if (intf.endpointCount != 2) return null
        val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
        if (endpoints.any { it.type != UsbConstants.USB_ENDPOINT_XFER_INT || it.maxPacketSize != 64 || it.interval <= 0 }) return null
        val input = endpoints.singleOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val output = endpoints.singleOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        return input to output
    }

    private class AndroidInterruptConnection(
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val endpoints: Pair<UsbEndpoint, UsbEndpoint>,
    ) : HidInterruptConnection {
        private var active: AndroidRequest? = null
        override fun request(input: Boolean): HidInterruptRequest {
            val native = UsbRequest()
            try { check(native.initialize(connection, if (input) endpoints.first else endpoints.second)) }
            catch (failure: Exception) { native.close(); throw failure }
            return AndroidRequest(native).also { active = it }
        }
        override fun await(timeoutMillis: Int): HidInterruptRequest? {
            val result = connection.requestWait(timeoutMillis.toLong()) ?: return null
            return active?.takeIf { it.native === result }
        }
        override fun releaseInterface() { connection.releaseInterface(intf) }
        override fun close() { connection.close() }
        private class AndroidRequest(val native: UsbRequest) : HidInterruptRequest {
            override fun queue(buffer: ByteBuffer): Boolean = native.queue(buffer)
            override fun cancel() { native.cancel() }
            override fun close() { native.close() }
        }
    }
}
