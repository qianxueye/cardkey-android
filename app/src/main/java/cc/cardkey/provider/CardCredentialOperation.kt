package cc.cardkey.provider

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.CardSession
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.fido.*
import java.math.BigInteger

data class CreatedCredential(val result: MakeCredentialResult, val credentialId: ByteArray, val residentKey: Boolean)

object CardCredentialOperation {
    fun create(session: CardSession, request: ParsedCreate, clientData: ClientData,
        pin: CharArray? = null): CreatedCredential = try {
        validateClientData(clientData)
        val client = FidoClient(session)
        client.select()
        val info = client.getInfo()
        val resident = when (request.residentKey) {
            ResidentKeyRequirement.REQUIRED -> {
                if (info.options["rk"] != true) throw CardFailure(FailureCode.UNSUPPORTED)
                true
            }
            ResidentKeyRequirement.PREFERRED -> info.options["rk"] == true
            ResidentKeyRequirement.DISCOURAGED -> false
        }
        val requireUv = requireUv(request.userVerification, info, pin)
        val result = client.makeCredential(MakeCredentialRequest(
            request.rpId, request.rpName, request.user, clientData.hash, listOf(-7), resident,
            requireUv, request.excludeIds), pin)
        val auth = validateAuth(result.authData, request.rpId, requireUv)
        val id = auth.credentialId ?: malformed()
        if (request.excludeIds.any { it.contentEquals(id) }) malformed()
        val key = auth.publicKey ?: malformed()
        if (key[3].number() != -7L || key[-4] != null) malformed()
        FidoDiagnostics.publicKey(key)
        if (result.enterpriseAttestation == true || result.largeBlobKey != null) malformed()
        val verified = FidoDiagnostics.verifyRegistration(result, request.rpId, clientData.hash, requireUv)
        if (verified.signatureValid == false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        val output = when (request.attestation) {
            AttestationPreference.NONE, AttestationPreference.INDIRECT -> {
                val privateData = result.authData.copyOf()
                privateData.fill(0, 37, 53)
                result.copy(format = "none", authData = privateData, statement = cborMap())
            }
            AttestationPreference.DIRECT -> result
        }
        CreatedCredential(output, id.copyOf(), resident)
    } finally { pin?.fill('\u0000') }

    fun get(session: CardSession, request: ParsedGet, clientData: ClientData,
        pin: CharArray? = null): List<Assertion> = try {
        validateClientData(clientData)
        val client = FidoClient(session)
        client.select()
        val requireUv = requireUv(request.userVerification, client.getInfo(), pin)
        val results = client.getAssertion(GetAssertionRequest(request.rpId, clientData.hash,
            request.allowIds, requireUv), pin)
        val seen = mutableSetOf<Cbor.Bytes>()
        for (result in results) {
            val auth = validateAuth(result.authData, request.rpId, requireUv)
            if (auth.credentialId != null || result.credentialId.size !in 1..4096 ||
                !seen.add(Cbor.Bytes(result.credentialId)) ||
                (request.allowIds.isNotEmpty() && request.allowIds.none { it.contentEquals(result.credentialId) }) ||
                (request.allowIds.isEmpty() && result.user == null) ||
                (result.user != null && result.user.id.size !in 1..64) ||
                result.hmacSecret != null || result.largeBlobKey != null) malformed()
            validateSignature(result.signature)
        }
        results
    } finally { pin?.fill('\u0000') }

    private fun requireUv(requirement: UserVerificationRequirement, info: FidoInfo, pin: CharArray?): Boolean {
        val available = pin != null || info.options["uv"] == true
        if (requirement == UserVerificationRequirement.REQUIRED && !available)
            throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        return pin != null || requirement == UserVerificationRequirement.REQUIRED ||
            (requirement == UserVerificationRequirement.PREFERRED && available)
    }

    private fun validateAuth(data: ByteArray, rpId: String, requireUv: Boolean): AuthenticatorData {
        val auth = AuthenticatorData.parse(data)
        if (!auth.rpIdHash.contentEquals(sha256(rpId.toByteArray(Charsets.UTF_8))) ||
            !auth.userPresent || (requireUv && !auth.userVerified))
            throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        if (auth.flags and 0x18 != 0) malformed()
        return auth
    }

    private fun validateSignature(signature: ByteArray) {
        if (signature.size !in 8..72 || signature[0] != 0x30.toByte() ||
            signature[1].toInt() != signature.size - 2) malformed()
        var offset = 2
        val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        repeat(2) {
            if (offset + 2 > signature.size || signature[offset++] != 2.toByte()) malformed()
            val length = signature[offset++].toInt() and 255
            if (length !in 1..33 || offset + length > signature.size || signature[offset].toInt() < 0 ||
                (length > 1 && signature[offset] == 0.toByte() && signature[offset + 1].toInt() >= 0)) malformed()
            val scalar = BigInteger(signature.copyOfRange(offset, offset + length))
            if (scalar.signum() <= 0 || scalar >= order) malformed()
            offset += length
        }
        if (offset != signature.size) malformed()
    }
}

internal fun validateClientData(data: ClientData) {
    require(data.hash.size == 32)
    if (!data.browserDelegated) require(data.json.isNotEmpty() && sha256(data.json).contentEquals(data.hash))
}
