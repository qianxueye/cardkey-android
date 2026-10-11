package cc.cardkey.protocol

import java.io.Closeable
import java.io.IOException

data class Apdu(val cla: Int = 0, val ins: Int, val p1: Int = 0, val p2: Int = 0,
                val data: ByteArray = byteArrayOf(), val le: Int? = 256)

data class ApduResponse(val data: ByteArray, val status: Int) {
    fun requireSuccess(): ByteArray {
        if (status != 0x9000) throw CardFailure(
            when {
                status == 0x63c0 -> FailureCode.PIN_BLOCKED
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
    val wire get() = channel.wire
    val supportsCtap2 get() = channel.supportsCtap2
    val supportsCtap1 get() = channel.supportsCtap1
    var effect = Effect.NONE
        private set
    private var finished = false
    private var running = false
    private var writeAccepted = false
    val hasAcceptedWrite get() = writeAccepted
    private var maxCtap2CommandBytes = 1024

    fun requireContact() {
        if (medium != Medium.CONTACT) throw CardFailure(FailureCode.CONTACT_REQUIRED)
    }

    fun applied() { effect = Effect.APPLIED; writeAccepted = true }
    fun securityStateChanged() { effect = Effect.APPLIED }
    fun securityStateUncertain() { effect = Effect.UNKNOWN }

    private var secureOwner: Closeable? = null

    @Synchronized internal fun bindSecure(owner: Closeable) {
        check(!finished)
        invalidateSecure()
        secureOwner = owner
    }

    @Synchronized internal fun ownsSecure(owner: Closeable) = !finished && secureOwner === owner

    @Synchronized fun invalidateSecure() {
        val previous = secureOwner
        secureOwner = null
        previous?.close()
    }

    @Synchronized internal fun exchangeSecure(owner: Closeable, apdu: Apdu, effectful: Boolean, finalDispatch: () -> Unit): ApduResponse {
        if (!ownsSecure(owner)) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        return exchangeWire(apdu, effectful, finalDispatch)
    }

    @Synchronized fun exchange(apdu: Apdu, effectful: Boolean = false): ApduResponse {
        invalidateSecure()
        return exchangeWire(apdu, effectful)
    }

    @Synchronized internal fun setCtap2MaxCommandBytes(maximum: Int) {
        if (maximum <= 0) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        maxCtap2CommandBytes = maximum
    }

    @Synchronized fun exchangeCtap2(command: ByteArray, effectful: Boolean = false): ByteArray {
        check(!finished)
        if (command.size > maxCtap2CommandBytes) throw CardFailure(FailureCode.CAPACITY_EXCEEDED)
        if (wire == CardWire.APDU) return exchange(Apdu(cla = 0x80, ins = 0x10, data = command), effectful).requireSuccess()
        if (!supportsCtap2) throw CardFailure(FailureCode.UNSUPPORTED)
        require(command.isNotEmpty())
        invalidateSecure()
        val response = exchangeTyped(command, effectful, channel::exchangeCtap2)
        if (response.isEmpty()) {
            if (effectful) securityStateUncertain()
            throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        }
        return response
    }

    @Synchronized fun exchangeCtap1(apdu: Apdu, effectful: Boolean = false): ApduResponse {
        check(!finished)
        if (wire == CardWire.APDU) return exchange(apdu, effectful)
        if (!supportsCtap1) throw CardFailure(FailureCode.UNSUPPORTED)
        require(apdu.data.size <= 65535 && apdu.le in listOf(null, 256))
        invalidateSecure()
        val command = byteArrayOf(apdu.cla.toByte(), apdu.ins.toByte(), apdu.p1.toByte(), apdu.p2.toByte(),
            0, (apdu.data.size ushr 8).toByte(), apdu.data.size.toByte()) + apdu.data +
            (if (apdu.le == null) byteArrayOf() else byteArrayOf(0, 0))
        val response = parseResponse(exchangeTyped(command, effectful, channel::exchangeCtap1), effectful)
        if (effectful && response.status == 0x6f00) securityStateUncertain()
        return response
    }

    private fun exchangeTyped(command: ByteArray, effectful: Boolean, exchange: (ByteArray) -> ByteArray): ByteArray {
        if (command.size > channel.maxCommandBytes) throw CardFailure(FailureCode.CAPACITY_EXCEEDED)
        return try { exchange(command) }
        catch (e: TransportException) {
            if (effectful && e.dispatch == Dispatch.RESPONSE_UNAVAILABLE) securityStateUncertain()
            throw e
        } catch (e: IOException) {
            if (effectful) securityStateUncertain()
            throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, e)
        } catch (e: CardFailure) {
            if (effectful && e.code == FailureCode.MALFORMED_RESPONSE) securityStateUncertain()
            throw e
        }
    }

    private fun exchangeWire(apdu: Apdu, effectful: Boolean, finalDispatch: (() -> Unit)? = null): ApduResponse {
        check(!finished)
        if (wire != CardWire.APDU) throw CardFailure(FailureCode.UNSUPPORTED)
        require(apdu.data.size <= 65535 && apdu.le in listOf(null, 256))
        val chunkSize = minOf(255, channel.maxCommandBytes - 6)
        if (chunkSize < 1) throw CardFailure(FailureCode.UNSUPPORTED)
        var offset = 0
        while (apdu.data.size - offset > chunkSize) {
            val part = send(encode(apdu.copy(cla = apdu.cla or 0x10,
                data = apdu.data.copyOfRange(offset, offset + chunkSize), le = null)), effectful && finalDispatch == null)
            part.requireSuccess()
            if (part.data.isNotEmpty()) {
                if (effectful && finalDispatch == null) securityStateUncertain()
                throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            }
            offset += chunkSize
        }
        val finalCommand = encode(apdu.copy(data = apdu.data.copyOfRange(offset, apdu.data.size)))
        finalDispatch?.invoke()
        var response = send(finalCommand, effectful)
        var data = response.data
        var continuations = 0
        while (response.status ushr 8 == 0x61) {
            if (++continuations > 256 || data.size > 65536) {
                if (effectful) securityStateUncertain()
                throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            }
            response = send(byteArrayOf((if (apdu.cla == 0x0c) 0 else apdu.cla).toByte(), 0xc0.toByte(), 0, 0,
                (response.status and 0xff).toByte()), effectful)
            data += response.data
        }
        if (data.size > 65536) {
            if (effectful) securityStateUncertain()
            throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        }
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
        return parseResponse(bytes, effectful)
    }

    private fun parseResponse(bytes: ByteArray, effectful: Boolean): ApduResponse {
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
        catch (e: TransportException) { OperationResult.Failed(if (e.cancelled) FailureCode.CANCELLED else FailureCode.TRANSPORT_FAILURE, effect, dispatch = e.dispatch, writeAccepted = writeAccepted) }
        catch (_: java.security.GeneralSecurityException) { OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, effect, writeAccepted = writeAccepted) }
        catch (_: IllegalArgumentException) { OperationResult.Failed(FailureCode.INVALID_INPUT, effect, writeAccepted = writeAccepted) }
        finally { close() }
    }

    @Synchronized override fun close() {
        invalidateSecure()
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
