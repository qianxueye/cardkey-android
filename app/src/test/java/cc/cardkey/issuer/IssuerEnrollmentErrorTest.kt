package cc.cardkey.issuer

import cc.cardkey.R
import org.junit.Assert.assertEquals
import org.junit.Test

class IssuerEnrollmentErrorTest {
    private fun message(reason: IssuerMintError, reporting: Boolean) =
        issuerEnrollmentError(IssuerEnrollmentFailure(409, reason), reporting)

    @Test fun liveTokenDuringMintSaysAuthorizationWasAlreadyRequested() {
        assertEquals(R.string.enrollment_tokenAlreadyRequested, message(IssuerMintError.SIGNING_TOKEN_LIVE, reporting = false))
    }

    @Test fun liveTokenDuringResultReportSaysTheResultCannotBeRecordedYet() {
        assertEquals(R.string.enrollment_signingTokenLive, message(IssuerMintError.SIGNING_TOKEN_LIVE, reporting = true))
    }

    @Test fun eachNewIssuerCodeHasItsOwnMessage() {
        for (reporting in listOf(false, true)) {
            assertEquals(R.string.enrollment_signingAttemptUnresolved, message(IssuerMintError.SIGNING_ATTEMPT_UNRESOLVED, reporting))
            assertEquals(R.string.enrollment_issuanceNotPending, message(IssuerMintError.ISSUANCE_NOT_PENDING, reporting))
            assertEquals(R.string.enrollment_issuanceTerminal, message(IssuerMintError.ISSUANCE_TERMINAL, reporting))
            assertEquals(R.string.enrollment_signingUnavailable, message(IssuerMintError.SIGNING_UNAVAILABLE, reporting))
            assertEquals(R.string.enrollment_keyManagementRsaRequired, message(IssuerMintError.KEY_MANAGEMENT_ALGORITHM_MISMATCH, reporting))
        }
    }
}
