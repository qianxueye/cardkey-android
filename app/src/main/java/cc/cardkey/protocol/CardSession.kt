package cc.cardkey.protocol

import java.io.Closeable
import java.io.IOException

data class Apdu(val cla: Int = 0, val ins: Int, val p1: Int = 0, val p2: Int = 0,
                val data: ByteArray = byteArrayOf(), val le: Int? = 256)

data class ApduResponse(val data: ByteArray, val status: Int) {
    fun requireSuccess(): ByteArray {
        if (status != 0x9000) throw CardFailure(
            when {
                status and 0xfff0 == 0x63c0 -> FailureCode.PIN_INVALID
                status == 0x6983 -> FailureCode.PIN_BLOCKED
                status == 0x6982 -> FailureCode.AUTHORIZATION_DENIED
                else -> FailureCode.CARD_REJECTED
            }, status)
        return data
    }
}

class CardSession(private val channel: CardChannel) : Closeable {
    val medium get() = channel.medium
    var effect = Effect.NONE
        private set
    private var finished = false
    private var running = false
    private var writeAccepted = false

    fun requireContact() {
        if (medium != Medium.CONTACT) throw CardFailure(FailureCode.CONTACT_REQUIRED)
    }

    fun applied() { effect = Effect.APPLIED; writeAccepted = true }
    fun securityStateChanged() { effect = Effect.APPLIED }
    fun securityStateUncertain() { effect = Effect.UNKNOWN }

    fun exchange(apdu: Apdu, effectful: Boolean = false): ApduResponse {
        check(!finished)
        require(apdu.data.size <= 65535 && apdu.le in listOf(null, 256))
        val chunkSize = minOf(255, channel.maxCommandBytes - 6)
        if (chunkSize < 1) throw CardFailure(FailureCode.UNSUPPORTED)
        var offset = 0
        while (apdu.data.size - offset > chunkSize) {
            val part = send(encode(apdu.copy(cla = apdu.cla or 0x10,
                data = apdu.data.copyOfRange(offset, offset + chunkSize), le = null)), effectful)
            part.requireSuccess()
            if (part.data.isNotEmpty()) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            offset += chunkSize
        }
        var response = send(encode(apdu.copy(data = apdu.data.copyOfRange(offset, apdu.data.size))), effectful)
        var data = response.data
        var continuations = 0
        while (response.status ushr 8 == 0x61) {
            if (++continuations > 256 || data.size > 65536) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            response = send(byteArrayOf(apdu.cla.toByte(), 0xc0.toByte(), 0, 0,
                (response.status and 0xff).toByte()), effectful)
            data += response.data
        }
        if (data.size > 65536) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        return ApduResponse(data, response.status)
    }

    private fun send(command: ByteArray, effectful: Boolean): ApduResponse {
        val bytes = try { channel.transmit(command) } catch (e: TransportException) {
            if (effectful && e.dispatch == Dispatch.RESPONSE_UNAVAILABLE) effect = Effect.UNKNOWN
            throw e
        } catch (e: IOException) {
            if (effectful) effect = Effect.UNKNOWN
            throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, e)
        }
        if (bytes.size < 2) {
            if (effectful) effect = Effect.UNKNOWN
            throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        }
        val status = ((bytes[bytes.size - 2].toInt() and 255) shl 8) or (bytes.last().toInt() and 255)
        return ApduResponse(bytes.copyOf(bytes.size - 2), status)
    }

    fun <T> run(operation: (CardSession) -> T): OperationResult<T> {
        check(!running && !finished)
        running = true
        return try { OperationResult.Completed(operation(this), effect) }
        catch (e: CardFailure) { OperationResult.Failed(e.code, effect, e.status, writeAccepted = writeAccepted) }
        catch (e: TransportException) { OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, effect, dispatch = e.dispatch, writeAccepted = writeAccepted) }
        catch (_: java.security.GeneralSecurityException) { OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, effect, writeAccepted = writeAccepted) }
        catch (_: IllegalArgumentException) { OperationResult.Failed(FailureCode.INVALID_INPUT, effect, writeAccepted = writeAccepted) }
        finally { close() }
    }

    override fun close() {
        if (!finished) { finished = true; try { channel.close() } catch (_: IOException) { } }
    }

    companion object {
        fun encode(apdu: Apdu): ByteArray {
            require(apdu.data.size <= 255)
            val header = byteArrayOf(apdu.cla.toByte(), apdu.ins.toByte(), apdu.p1.toByte(), apdu.p2.toByte())
            return header + (if (apdu.data.isEmpty()) byteArrayOf() else byteArrayOf(apdu.data.size.toByte()) + apdu.data) +
                (if (apdu.le == null) byteArrayOf() else byteArrayOf(0))
        }
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
fun String.hexBytes(): ByteArray {
    val compact = filterNot(Char::isWhitespace)
    require(compact.length % 2 == 0 && compact.all { it.digitToIntOrNull(16) != null })
    return compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
