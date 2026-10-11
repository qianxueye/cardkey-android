package cc.cardkey.ui

import cc.cardkey.R
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import org.junit.Assert.*
import org.junit.Test

class ConfirmationPolicyTest {
    private fun info(pin: Int, puk: Int, serial: Long = 1) = PivInfo("1.0.0", serial,
        RetryMetadata(false, 3, pin), RetryMetadata(false, 3, puk), ManagementAlgorithm.TDES, false)

    @Test fun deletionConfirmsAccountLossAndPinRetryRisk() {
        val input = OperationInput(ManagerOperation.FIDO_DELETE, pin = "123456".toCharArray())
        input.use { assertEquals(listOf(R.string.confirm_credentialDelete, R.string.confirm_authenticate), ConfirmationPolicy.messages(it)) }
    }

    @Test fun pinChangeDoesNotDuplicateRetryWarning() {
        val input = OperationInput(ManagerOperation.PIV_CHANGE_PIN, pin = "123456".toCharArray())
        input.use { assertEquals(listOf(R.string.confirm_pinChange), ConfirmationPolicy.messages(it)) }
    }

    @Test fun alwaysUvConfirmationMatchesRequestedDirection() {
        assertEquals(R.string.confirm_alwaysUvEnable, ConfirmationPolicy.messages(OperationInput(ManagerOperation.FIDO_ALWAYS_UV, enabled = true)).first())
        assertEquals(R.string.confirm_alwaysUvDisable, ConfirmationPolicy.messages(OperationInput(ManagerOperation.FIDO_ALWAYS_UV, enabled = false)).first())
    }

    @Test fun initialPinSetupAndPublicReadDoNotClaimCurrentPinVerification() {
        assertTrue(ConfirmationPolicy.messages(OperationInput(ManagerOperation.FIDO_SET_PIN)).isEmpty())
        assertTrue(ConfirmationPolicy.messages(OperationInput(ManagerOperation.PIV_INFO)).isEmpty())
    }

    @Test fun lastRetryWarningRequiresBothLiveConditions() {
        assertEquals("PIN" to "PUK", ConfirmationPolicy.lastRetry(ManagerOperation.PIV_SIGN, info(1, 0)))
        assertNull(ConfirmationPolicy.lastRetry(ManagerOperation.PIV_SIGN, info(2, 0)))
        assertNull(ConfirmationPolicy.lastRetry(ManagerOperation.PIV_SIGN, info(1, 1)))
        assertNull(ConfirmationPolicy.lastRetry(ManagerOperation.FIDO_CHANGE_PIN, info(1, 0)))
        assertNull(ConfirmationPolicy.lastRetry(ManagerOperation.PIV_INFO, info(1, 0)))
    }

    @Test fun pukChangeAndUnblockUsePukAsCurrentVerificationItem() {
        assertEquals("PUK" to "PIN", ConfirmationPolicy.lastRetry(ManagerOperation.PIV_CHANGE_PUK, info(0, 1)))
        assertEquals("PUK" to "PIN", ConfirmationPolicy.lastRetry(ManagerOperation.PIV_UNBLOCK, info(0, 1)))
        assertNull(ConfirmationPolicy.lastRetry(ManagerOperation.PIV_UNBLOCK, info(1, 0)))
    }

    @Test fun changedCardOrRetryFactsInvalidateLastRetryApproval() {
        val before = info(1, 0)
        assertTrue(ConfirmationPolicy.retryFactsUnchanged(before, info(1, 0)))
        assertFalse(ConfirmationPolicy.retryFactsUnchanged(before, info(1, 0, serial = 2)))
        assertFalse(ConfirmationPolicy.retryFactsUnchanged(before, info(2, 0)))
        assertFalse(ConfirmationPolicy.retryFactsUnchanged(before, info(1, 1)))
    }

    @Test fun lostWriteResponseNeverDisplaysSuccessOrNoDispatch() {
        val result = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE)
        assertEquals(listOf(R.string.operation_unknown, R.string.error_transport), ConfirmationPolicy.resultMessages(result))
    }

    @Test fun acceptedWriteRemainsDistinctFromUnknownReadbackResponse() {
        val result = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
            dispatch = Dispatch.RESPONSE_UNAVAILABLE, writeAccepted = true)
        assertEquals(listOf(R.string.operation_appliedReadbackFailed, R.string.error_transport), ConfirmationPolicy.resultMessages(result))
    }

    @Test fun knownBlockedPinResponseIsNotDescribedAsMissingResponse() {
        val result = OperationResult.Failed(FailureCode.PIN_BLOCKED, Effect.UNKNOWN, status = 0x32)
        assertEquals(listOf(R.string.error_pinBlocked), ConfirmationPolicy.resultMessages(result))
    }

    @Test fun notDispatchedTargetKeepsTheNoDispatchMessageAfterSecurityStateChange() {
        val result = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.APPLIED, dispatch = Dispatch.NOT_DISPATCHED)
        assertEquals(listOf(R.string.operation_notDispatched, R.string.error_transport), ConfirmationPolicy.resultMessages(result))
    }

    @Test fun malformedWriteResponseRetainsUnknownResult() {
        val result = OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.UNKNOWN)
        assertEquals(listOf(R.string.operation_unknown, R.string.error_malformed), ConfirmationPolicy.resultMessages(result))
    }

    @Test fun uncertaintyAllowsInspectionButNotAnotherMutation() {
        assertTrue(ConfirmationPolicy.canInspect(ManagerOperation.FIDO_CREDENTIALS))
        assertTrue(ConfirmationPolicy.canInspect(ManagerOperation.PIV_SLOT))
        assertFalse(ConfirmationPolicy.canInspect(ManagerOperation.FIDO_DELETE))
        assertFalse(ConfirmationPolicy.canInspect(ManagerOperation.PIV_GENERATE))
    }
    @Test fun secretReadsRequireSeparateApprovedConfirmation() {
        assertTrue(ConfirmationPolicy.messages(OperationInput(ManagerOperation.FIDO_ASSERT,
            assertionExtensions = cc.cardkey.protocol.fido.AssertionExtensions(hmacSalts = ByteArray(32))))
            .contains(R.string.confirm_secretRead))
        assertTrue(ConfirmationPolicy.messages(OperationInput(ManagerOperation.FIDO_REGISTER,
            makeExtensions = cc.cardkey.protocol.fido.MakeExtensions(largeBlobKey = true)))
            .contains(R.string.confirm_secretRead))
    }

}
