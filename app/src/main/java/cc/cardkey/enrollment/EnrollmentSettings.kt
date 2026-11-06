package cc.cardkey.enrollment

import android.content.SharedPreferences
import cc.cardkey.protocol.piv.KeyAlgorithm
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

class EnrollmentSettings(private val preferences: SharedPreferences) {
    fun load(): String = preferences.getString("ca_origin", null)?.let {
        runCatching { enrollmentOrigin(it) }.getOrNull()
    }.orEmpty()

    fun save(address: String) {
        val editor = preferences.edit()
        if (address.isEmpty()) editor.remove("ca_origin") else editor.putString("ca_origin", address)
        check(editor.commit()) { "Could not save enrollment settings" }
    }

    internal fun saveReceipts(receipts: List<IssuanceReceipt>) {
        val encoded = JsonArray().apply {
            receipts.forEach { receipt ->
                add(JsonObject().apply {
                    addProperty("csr", Base64.getEncoder().encodeToString(receipt.csr.der))
                    addProperty("root", Base64.getEncoder().encodeToString(receipt.ca.anchor))
                    addProperty("address", receipt.ca.endpoint.newBuilder().encodedPath("/").build().toString())
                    addProperty("purpose", receipt.csr.selection.purpose.name)
                    addProperty("slot", receipt.csr.selection.slot)
                    addProperty("algorithm", receipt.csr.selection.algorithm.name)
                    addProperty("issuerOrigin", receipt.target.issuerOrigin)
                    addProperty("personId", receipt.target.personId)
                    addProperty("cardId", receipt.target.cardId)
                    addProperty("caId", receipt.target.caId)
                    addProperty("employeeReference", receipt.target.identity.employeeReference)
                    addProperty("displayName", receipt.target.identity.displayName)
                    addProperty("upn", receipt.target.identity.upn)
                    addProperty("email", receipt.target.identity.email)
                    addProperty("issuanceId", receipt.issuanceId)
                    addProperty("registrationKey", receipt.registrationKey)
                    addProperty("mintKey", receipt.mintKey)
                    addProperty("resultKey", receipt.resultKey)
                    addProperty("certificateId", receipt.certificateId)
                    addProperty("reportPending", receipt.reportPending)
                    receipt.bundle?.let { bundle ->
                        addProperty("verifiedAt", bundle.verifiedAt)
                        add("chain", JsonArray().apply {
                            bundle.chain.forEach { add(Base64.getEncoder().encodeToString(it)) }
                        })
                    }
                    addProperty("stage", receipt.stage.name)
                })
            }
        }
        check(preferences.edit().putString("issuance_receipts", encoded.toString()).commit()) {
            "Could not save enrollment recovery"
        }
    }

    internal fun loadReceipts(): List<IssuanceReceipt> {
        val encoded = preferences.getString("issuance_receipts", null) ?: return emptyList()
        return JsonParser.parseString(encoded).asJsonArray.map { element ->
            val value = element.asJsonObject
            fun string(key: String) = value[key].asString
            val identity = EnrollmentIdentity(string("employeeReference"), string("displayName"), string("upn"), string("email"))
            val selection = EnrollmentSelection(EnrollmentPurpose.valueOf(string("purpose")), value["slot"].asInt,
                KeyAlgorithm.valueOf(string("algorithm")))
            val csr = EnrollmentCsr(Base64.getDecoder().decode(string("csr")), identity, selection)
            val target = EnrollmentTarget(string("issuerOrigin"), string("personId"), string("cardId"), string("caId"), identity)
            val ca = EnrollmentCA(string("address"), Base64.getDecoder().decode(string("root")))
            val bundle = if (value.has("chain")) EnrollmentBundle(value["chain"].asJsonArray.map {
                Base64.getDecoder().decode(it.asString)
            }, value["verifiedAt"].asLong) else null
            val stage = EnrollmentStage.valueOf(string("stage"))
            require(stage !in setOf(EnrollmentStage.VERIFIED, EnrollmentStage.IMPORTED) || bundle != null)
            if (bundle != null) EnrollmentProfile.validate(bundle.chain, ca.anchor, csr, now = bundle.verifiedAt, issuedAt = bundle.verifiedAt)
            require(listOf("issuanceId", "registrationKey", "mintKey", "resultKey", "certificateId").all { string(it).isNotBlank() })
            IssuanceReceipt(csr, ca, stage, target,
                string("issuanceId"), string("registrationKey"), string("mintKey"), string("resultKey"),
                string("certificateId"), bundle, value["reportPending"].asBoolean)
        }
    }
}
