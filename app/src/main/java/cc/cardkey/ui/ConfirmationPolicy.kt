package cc.cardkey.ui

import cc.cardkey.R
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.PivInfo

object ConfirmationPolicy {
    fun messages(input: OperationInput): List<Int> {
        val primary = when (input.operation) {
            ManagerOperation.FIDO_CHANGE_PIN, ManagerOperation.PIV_CHANGE_PIN,
            ManagerOperation.PIV_CHANGE_PUK, ManagerOperation.PIV_UNBLOCK -> R.string.confirm_pinChange
            ManagerOperation.PIV_ADMIN -> R.string.confirm_adminChange
            ManagerOperation.FIDO_BLOB_WRITE -> R.string.confirm_blobWrite
            ManagerOperation.FIDO_DELETE -> R.string.confirm_credentialDelete
            ManagerOperation.FIDO_UPDATE -> R.string.confirm_credentialUpdate
            ManagerOperation.FIDO_ALWAYS_UV -> if (input.enabled) R.string.confirm_alwaysUvEnable else R.string.confirm_alwaysUvDisable
            ManagerOperation.FIDO_MIN_PIN -> R.string.confirm_minPin
            ManagerOperation.FIDO_ENTERPRISE -> R.string.confirm_enterprise
            ManagerOperation.PIV_GENERATE -> R.string.confirm_keyGenerate
            ManagerOperation.PIV_IMPORT -> R.string.confirm_certificateImport
            ManagerOperation.PIV_ENROLL_IMPORT -> R.string.enrollment_confirmImport
            ManagerOperation.FIDO_REGISTER, ManagerOperation.U2F_REGISTER -> R.string.confirm_registration
            ManagerOperation.FIDO_RESET -> R.string.confirm_fidoReset
            ManagerOperation.PIV_RESET -> R.string.confirm_pivReset
            else -> null
        }
        val pinWarning = input.pin.isNotEmpty() && primary != R.string.confirm_pinChange &&
            input.operation !in setOf(ManagerOperation.FIDO_SET_PIN, ManagerOperation.FIDO_RESET, ManagerOperation.PIV_ADMIN,
                ManagerOperation.PIV_GENERATE, ManagerOperation.PIV_IMPORT, ManagerOperation.PIV_RESET)
        val secretWarning = input.operation == ManagerOperation.FIDO_ASSERT &&
            (input.assertionExtensions.hmacSalts != null || input.assertionExtensions.largeBlobKey || input.assertionExtensions.credentialBlob) ||
            input.operation == ManagerOperation.FIDO_REGISTER && input.makeExtensions.largeBlobKey ||
            input.operation in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)
        return listOfNotNull(primary) + (if (secretWarning) listOf(R.string.confirm_secretRead) else emptyList()) + if (pinWarning) listOf(R.string.confirm_authenticate) else emptyList()
    }

    fun pivVerificationReference(operation: ManagerOperation): String? = when (operation) {
        ManagerOperation.PIV_CHANGE_PUK, ManagerOperation.PIV_UNBLOCK -> "PUK"
        ManagerOperation.PIV_CHANGE_PIN, ManagerOperation.PIV_CSR, ManagerOperation.PIV_ENROLL_CSR, ManagerOperation.PIV_SIGN,
        ManagerOperation.PIV_ECDH, ManagerOperation.PIV_DECRYPT -> "PIN"
        else -> null
    }

    fun lastRetry(operation: ManagerOperation, info: PivInfo): Pair<String, String>? {
        val reference = pivVerificationReference(operation) ?: return null
        val current = if (reference == "PIN") info.pin else info.puk
        val other = if (reference == "PIN") info.puk else info.pin
        return if (current.remaining == 1 && other.remaining == 0)
            reference to if (reference == "PIN") "PUK" else "PIN" else null
    }

    fun retryFactsUnchanged(before: PivInfo, after: PivInfo): Boolean =
        before.serial == after.serial && before.pin == after.pin && before.puk == after.puk

    fun canInspect(operation: ManagerOperation): Boolean = operation in setOf(
        ManagerOperation.FIDO_INFO, ManagerOperation.FIDO_RETRIES, ManagerOperation.FIDO_CAPACITY,
        ManagerOperation.FIDO_RPS, ManagerOperation.FIDO_CREDENTIALS, ManagerOperation.FIDO_BLOB_READ,
        ManagerOperation.U2F_INFO, ManagerOperation.PIV_INFO, ManagerOperation.PIV_SLOT,
    )

    fun presenceMessage(required: Boolean, busy: Boolean, wire: CardWire): Int? =
        R.string.status_hidUserPresenceRequired.takeIf { required && busy && wire == CardWire.CTAP }

    fun resultMessages(result: OperationResult<*>, wire: CardWire = CardWire.APDU,
        operation: ManagerOperation? = null, requiresInspection: Boolean = false): List<Int> = when (result) {
        is OperationResult.Completed -> listOf(if (operation == ManagerOperation.FIDO_RESET)
            R.string.operation_fidoResetReadback else R.string.operation_completed)
        is OperationResult.Failed -> {
            val effect = when {
                result.writeAccepted -> R.string.operation_appliedReadbackFailed
                result.effect == Effect.UNKNOWN && (result.dispatch == Dispatch.RESPONSE_UNAVAILABLE ||
                    result.code == FailureCode.MALFORMED_RESPONSE || result.status in setOf(0x7f, 0x6f00, 0x2d)) -> R.string.operation_unknown
                result.dispatch == Dispatch.NOT_DISPATCHED -> R.string.operation_notDispatched
                else -> null
            }
            val reason = when (result.code) {
                FailureCode.CONTACT_REQUIRED -> R.string.error_contactRequired
                FailureCode.PHYSICAL_ACTION_REQUIRED -> if (wire == CardWire.CTAP) {
                    if (result.effect == Effect.UNKNOWN || result.writeAccepted || requiresInspection) R.string.error_recoveryRequired
                    else R.string.error_hidPhysicalActionRequired
                } else R.string.error_physicalActionRequired
                FailureCode.PIN_INVALID -> R.string.error_pinInvalid
                FailureCode.PIN_BLOCKED -> if (wire == CardWire.CTAP && result.status == 0x34)
                    R.string.error_hidPowerCycleRequired else R.string.error_pinBlocked
                FailureCode.AUTHORIZATION_DENIED -> R.string.error_authorization
                FailureCode.MALFORMED_RESPONSE -> R.string.error_malformed
                FailureCode.CERTIFICATE_KEY_MISMATCH -> R.string.error_keyMismatch
                FailureCode.INVALID_INPUT -> R.string.error_invalidInput
                FailureCode.TRANSPORT_FAILURE, FailureCode.BUSY -> R.string.error_transport
                FailureCode.CANCELLED -> R.string.error_cancelled
                FailureCode.NFC_UNAVAILABLE -> R.string.error_nfcUnavailable
                FailureCode.NFC_DISABLED -> R.string.error_nfcDisabled
                FailureCode.CAPACITY_EXCEEDED -> R.string.error_capacityExceeded
                FailureCode.RECOVERY_REQUIRED -> R.string.error_recoveryRequired
                FailureCode.CARD_REJECTED -> if (result.effect == Effect.UNKNOWN) R.string.error_recoveryRequired else R.string.error_cardRejected
                else -> R.string.error_unsupported
            }
            listOfNotNull(effect, reason).distinct()
        }
    }
}
