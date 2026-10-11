package cc.cardkey.protocol

import java.io.Closeable
import java.io.IOException

enum class Medium { CONTACT, NFC, UNKNOWN }

interface CardChannel : Closeable {
    val medium: Medium
    val maxCommandBytes: Int
    fun transmit(command: ByteArray): ByteArray
}

enum class Dispatch { NOT_DISPATCHED, RESPONSE_UNAVAILABLE }

class TransportException(val dispatch: Dispatch, cause: Throwable? = null, val cancelled: Boolean = false) : IOException(dispatch.name, cause)

enum class FailureCode {
    UNSUPPORTED, CONTACT_REQUIRED, PHYSICAL_ACTION_REQUIRED, BUSY, CANCELLED,
    PIN_INVALID, PIN_BLOCKED, AUTHORIZATION_DENIED, CERTIFICATE_KEY_MISMATCH,
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
