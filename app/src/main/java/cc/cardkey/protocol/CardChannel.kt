package cc.cardkey.protocol

import java.io.Closeable
import java.io.IOException

enum class CardWire { APDU, CTAP }

enum class Medium { CONTACT, NFC, UNKNOWN }

interface CardChannel : Closeable {
    val wire: CardWire get() = CardWire.APDU
    val supportsCtap2: Boolean get() = false
    val supportsCtap1: Boolean get() = false
    val medium: Medium
    val maxCommandBytes: Int
    fun transmit(command: ByteArray): ByteArray
    fun exchangeCtap2(command: ByteArray): ByteArray = throw CardFailure(FailureCode.UNSUPPORTED)
    fun exchangeCtap1(command: ByteArray): ByteArray = throw CardFailure(FailureCode.UNSUPPORTED)
    fun setUserPresenceListener(listener: ((Boolean) -> Unit)?) = Unit
    fun cancel() = close()
}

enum class Dispatch { NOT_DISPATCHED, RESPONSE_UNAVAILABLE }

class TransportException(val dispatch: Dispatch, cause: Throwable? = null, val cancelled: Boolean = false) : IOException(dispatch.name, cause)

enum class FailureCode {
    UNSUPPORTED, CONTACT_REQUIRED, PHYSICAL_ACTION_REQUIRED, BUSY, CANCELLED,
    PIN_INVALID, PIN_BLOCKED, PIN_SETUP_REQUIRED, PIN_CHANGE_REQUIRED, AUTHORIZATION_DENIED, CERTIFICATE_KEY_MISMATCH,
    MALFORMED_RESPONSE, CAPACITY_EXCEEDED, RECOVERY_REQUIRED, TRANSPORT_FAILURE,
    INVALID_INPUT, CARD_REJECTED, NFC_UNAVAILABLE, NFC_DISABLED
}

class CardFailure(val code: FailureCode, val status: Int? = null) : Exception(code.name)

enum class Effect { NONE, APPLIED, UNKNOWN }

sealed interface OperationResult<out T> {
    data class Completed<T>(val value: T, val effect: Effect) : OperationResult<T>
    data class Failed(val code: FailureCode, val effect: Effect, val status: Int? = null,
        val dispatch: Dispatch? = null, val writeAccepted: Boolean = false) : OperationResult<Nothing>
}
