package cc.cardkey.transport

import java.nio.ByteBuffer
import org.junit.Assert.*

internal class SyntheticNativeHidConnection(
    private val peer: CtaphidIo,
    private val reports: FidoHidReports,
) : HidInterruptConnection {
    private lateinit var active: Request
    val buffers = mutableListOf<ByteBuffer>()
    val nativeWrites = mutableListOf<ByteArray>()
    var inputID: Int = reports.inputReportID
    var inputLength: Int = reports.inputNativeBytes
    var outputLength: Int = reports.outputNativeBytes
    override fun request(input: Boolean): HidInterruptRequest = Request(input).also { active = it }
    override fun await(timeoutMillis: Int): HidInterruptRequest {
        if (active.completed) return active
        active.completed = true
        val buffer = active.buffer
        if (active.input) {
            val payload = ByteArray(reports.inputBytes)
            assertEquals(payload.size, peer.read(payload, timeoutMillis))
            if (reports.inputReportID != 0) buffer.put(inputID.toByte())
            buffer.put(payload)
            buffer.position(inputLength)
        } else {
            val native = ByteArray(buffer.remaining()).also { buffer.get(it) }
            nativeWrites += native
            val payload = if (reports.outputReportID != 0) {
                assertEquals(reports.outputReportID, native[0].toInt() and 255)
                native.copyOfRange(1, native.size)
            } else native
            assertEquals(reports.outputBytes, payload.size)
            assertEquals(payload.size, peer.write(payload, timeoutMillis))
            buffer.position(outputLength)
        }
        return active
    }
    override fun releaseInterface() = Unit
    override fun close() { peer.close() }
    private inner class Request(val input: Boolean) : HidInterruptRequest {
        var completed = false
        lateinit var buffer: ByteBuffer
        override fun queue(buffer: ByteBuffer): Boolean {
            assertTrue(buffer.isDirect)
            assertEquals(if (input) reports.inputNativeBytes else reports.outputNativeBytes, buffer.capacity())
            assertEquals(0, buffer.position())
            this.buffer = buffer
            buffers += buffer
            return true
        }
        override fun cancel() { peer.interrupt() }
        override fun close() = Unit
    }
}
