package cc.cardkey.issuer

import cc.cardkey.enrollment.EnrollmentCsr
import cc.cardkey.enrollment.EnrollmentIdentity
import cc.cardkey.enrollment.EnrollmentPurpose
import cc.cardkey.R
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.Base64

internal enum class IssuerMintError(val code: String) {
    UNAUTHORIZED("unauthorized"), STAFF_REQUIRED("staff_required"), NOT_FOUND("not_found"),
    BUSY("busy"), RETRY_TRANSACTION("retry_transaction"), IDENTIFIER_CONFLICT("identifier_conflict"),
    INVALID_REQUEST("invalid_request"), STORAGE_FAILURE("storage_failure"),
    INVALID_IDEMPOTENCY_KEY("invalid_idempotency_key"), IDEMPOTENCY_CONFLICT("idempotency_conflict"),
    REQUEST_TOO_LARGE("request_too_large"), INVALID_ISSUANCE("invalid_issuance"),
    ISSUANCE_NOT_PENDING("issuance_not_pending"), ISSUANCE_TERMINAL("issuance_terminal"),
    SIGNING_ATTEMPT_UNRESOLVED("signing_attempt_unresolved"),
    KEY_MANAGEMENT_ALGORITHM_MISMATCH("key_management_algorithm_mismatch"), CARD_PERSON_MISMATCH("card_person_mismatch"),
    CARD_INELIGIBLE("card_ineligible"), PERSON_INACTIVE("person_inactive"),
    SIGNING_TOKEN_LIVE("signing_token_live"), SIGNING_TOKEN_NOT_REPLAYABLE("signing_token_not_replayable"),
    SIGNING_UNAVAILABLE("signing_unavailable"), INVALID_PURPOSE("invalid_purpose"),
    INVALID_SLOT("invalid_slot"), SLOT_PURPOSE_MISMATCH("slot_purpose_mismatch"),
    CARD_AUTH_DISABLED("card_auth_disabled"), INVALID_CERTIFICATE_IDENTITY("invalid_certificate_identity"),
    INVALID_CSR("invalid_csr"), CSR_NAME_MISMATCH("csr_name_mismatch"), UNKNOWN("");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it != UNKNOWN && it.code == code } ?: UNKNOWN
    }
}
internal class IssuerEnrollmentFailure(val status: Int, val reason: IssuerMintError) : IOException()
internal fun issuerEnrollmentError(failure: Exception, reporting: Boolean = false): Int =
    when ((failure as? IssuerEnrollmentFailure)?.reason) {
        IssuerMintError.UNAUTHORIZED -> R.string.issuer_unauthorized
        IssuerMintError.STAFF_REQUIRED -> R.string.issuer_forbidden
        IssuerMintError.SIGNING_TOKEN_LIVE -> if (reporting) R.string.enrollment_signingTokenLive else R.string.enrollment_tokenAlreadyRequested
        IssuerMintError.SIGNING_TOKEN_NOT_REPLAYABLE -> R.string.enrollment_tokenAlreadyRequested
        IssuerMintError.SIGNING_ATTEMPT_UNRESOLVED -> R.string.enrollment_signingAttemptUnresolved
        IssuerMintError.ISSUANCE_NOT_PENDING -> R.string.enrollment_issuanceNotPending
        IssuerMintError.ISSUANCE_TERMINAL -> R.string.enrollment_issuanceTerminal
        IssuerMintError.SIGNING_UNAVAILABLE -> R.string.enrollment_signingUnavailable
        IssuerMintError.KEY_MANAGEMENT_ALGORITHM_MISMATCH -> R.string.enrollment_keyManagementRsaRequired
        IssuerMintError.CARD_INELIGIBLE, IssuerMintError.CARD_PERSON_MISMATCH,
        IssuerMintError.PERSON_INACTIVE -> R.string.enrollment_issuerIneligible
        else -> if (reporting) R.string.enrollment_resultUnreported else R.string.enrollment_issuerRequestFailed
    }

internal class IssuerIssuanceExpectation(val personID: String, val cardID: String, val caID: String, val csr: EnrollmentCsr) {
    init { recordID(personID); recordID(cardID); require(caID.isNotBlank()) }
    val purpose get() = when (csr.selection.purpose) {
        EnrollmentPurpose.TLS -> "piv_auth"
        EnrollmentPurpose.DOCUMENT -> "signing"
        EnrollmentPurpose.KEY_MANAGEMENT -> "key_management"
    }
    internal fun request() = JsonObject().apply {
        addProperty("person_id", personID); addProperty("card_id", cardID); addProperty("ca_id", caID)
        addProperty("csr_der", Base64.getEncoder().encodeToString(csr.der))
        addProperty("slot", "%02X".format(csr.selection.slot)); addProperty("algorithm", csr.selection.algorithm.name)
        addProperty("purpose", purpose); addProperty("common_name", csr.cn)
    }
}
internal data class IssuerIssuance(val id: String, val personID: String, val cardID: String, val caID: String,
                                 val csrDer: String, val slot: String, val algorithm: String, val purpose: String,
                                 val commonName: String, val identity: EnrollmentIdentity, val outcome: String,
                                 val certificateID: String) {
    fun validate(expected: IssuerIssuanceExpectation) {
        val request = expected.request()
        require(personID == expected.personID && cardID == expected.cardID && caID == expected.caID)
        require(csrDer == request.text("csr_der") && slot == request.text("slot") &&
            algorithm == request.text("algorithm") && purpose == expected.purpose && commonName == expected.csr.cn)
        require(identity == expected.csr.identity)
    }
}
internal class IssuerSigningToken(val token: String, val jti: String, val exp: Long, val provisionerRole: String)

private fun decodeIssuance(value: ByteArray): IssuerIssuance {
    val row = JsonParser.parseString(value.toString(Charsets.UTF_8)).asJsonObject
    val identity = row.getAsJsonObject("certificate_identity")
    return IssuerIssuance(recordID(row.text("id")), recordID(row.text("person_id")), recordID(row.text("card_id")),
        row.text("ca_id"), row.text("csr_der"), row.text("slot"), row.text("algorithm"), row.text("purpose"),
        row.text("common_name"), EnrollmentIdentity(identity.text("employee_reference"), identity.text("display_name"),
            identity.text("upn"), identity.text("email")), row.text("outcome"), row.text("certificate_id")).also {
        require(it.outcome in setOf("pending", "issued", "rejected", "not_submitted", "unknown", "not_issued"))
    }
}
internal suspend fun IssuerClient.registerIssuance(id: String, key: String, personID: String, cardID: String,
                                                 caID: String, csr: EnrollmentCsr): IssuerIssuance {
    staff()
    val expected = IssuerIssuanceExpectation(personID, cardID, caID, csr)
    return decodeIssuance(exchange("/v1/issuances/${recordID(id)}", method = "PUT", key = key, payload = expected.request()))
        .also { require(it.id == id); it.validate(expected) }
}
internal suspend fun IssuerClient.issuance(id: String): IssuerIssuance {
    staff()
    return decodeIssuance(exchange("/v1/issuances/${recordID(id)}")).also { require(it.id == id) }
}
internal suspend fun IssuerClient.mintIssuanceSigningToken(id: String, key: String, expected: IssuerIssuanceExpectation): IssuerSigningToken {
    staff(); recordID(key)
    val registered = issuance(id)
    registered.validate(expected)
    require(registered.outcome == "pending")
    val row = JsonParser.parseString(exchange("/v1/issuances/${recordID(id)}/token", method = "POST", key = key)
        .toString(Charsets.UTF_8)).asJsonObject
    val expiry = row.get("exp")
    require(expiry != null && expiry.isJsonPrimitive && expiry.asJsonPrimitive.isNumber && Regex("[0-9]+").matches(expiry.asString))
    return IssuerSigningToken(row.text("token"), row.text("jti"), expiry.asLong, row.text("provisioner_role")).also {
        require(it.token.isNotBlank() && it.token.length <= 32768 && it.jti.isNotBlank() && it.jti.length <= 255 &&
            it.exp > 0 && it.provisionerRole == expected.purpose)
    }
}
internal suspend fun IssuerClient.reportIssuanceResult(id: String, key: String, expected: IssuerIssuanceExpectation,
                                                     outcome: String, certificateID: String? = null,
                                                     chain: List<ByteArray> = emptyList()): IssuerIssuance {
    staff()
    require(outcome in setOf("issued", "rejected", "not_submitted", "unknown"))
    require(if (outcome == "issued") certificateID != null && chain.isNotEmpty() else certificateID == null && chain.isEmpty())
    require(chain.size <= 8 && chain.all { it.size in 1..65536 })
    val payload = JsonObject().apply {
        addProperty("outcome", outcome)
        if (certificateID != null) addProperty("certificate_id", recordID(certificateID))
        if (chain.isNotEmpty()) add("chain_der", JsonArray().apply { chain.forEach { add(Base64.getEncoder().encodeToString(it)) } })
    }
    return decodeIssuance(exchange("/v1/issuances/${recordID(id)}/result", method = "PUT", key = key, payload = payload))
        .also { require(it.id == id); it.validate(expected); require(it.outcome == outcome) }
}
