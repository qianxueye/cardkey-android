package cc.cardkey.provider

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.fido.FidoInfo

internal data class ProviderRecognition(val pinRequired: Boolean) {
    companion object {
        fun from(info: FidoInfo, create: ParsedCreate?, get: ParsedGet?): ProviderRecognition {
            if (info.versions.none { it in setOf("FIDO_2_0", "FIDO_2_1_PRE", "FIDO_2_1", "FIDO_2_2") })
                throw CardFailure(FailureCode.UNSUPPORTED)
            val requirement = create?.userVerification ?: requireNotNull(get).userVerification
            val prf = get?.extensions?.let { input ->
                "hmac-secret" in info.extensions && if (get.allowIds.isEmpty()) input.prfInputs != null
                else get.allowIds.any { input.inputsFor(it) != null }
            } == true
            val resident = create?.residentKey == ResidentKeyRequirement.REQUIRED ||
                (create?.residentKey == ResidentKeyRequirement.PREFERRED && info.options["rk"] == true)
            if (create != null) {
                if (resident && info.options["rk"] != true) throw CardFailure(FailureCode.UNSUPPORTED)
                if (info.algorithms?.none { it.type == "public-key" && it.algorithm == -7 } == true)
                    throw CardFailure(FailureCode.UNSUPPORTED)
                create.extensions.forCard(info, resident)
            }
            val protectedCreate = create != null && info.options["clientPin"] == true &&
                (resident || info.options["makeCredUvNotRqd"] != true)
            val blobPin = get?.extensions?.largeBlobWrite != null && info.supportsLargeBlob() &&
                listOf("clientPin", "uv", "alwaysUv").any { info.options[it] == true }
            val verificationRequired = requirement == UserVerificationRequirement.REQUIRED || prf ||
                info.options["alwaysUv"] == true || protectedCreate
            val pinAvailable = info.options["clientPin"] == true && info.pinProtocols.any { it == 1 || it == 2 }
            val builtInUv = info.options["uv"] == true
            val pinRequired = blobPin || (!builtInUv && (verificationRequired ||
                (requirement == UserVerificationRequirement.PREFERRED && pinAvailable)))
            if ((pinRequired && (!pinAvailable || info.forcePinChange == true)) ||
                (verificationRequired && !builtInUv && !pinAvailable))
                throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            return ProviderRecognition(pinRequired)
        }
    }
}
