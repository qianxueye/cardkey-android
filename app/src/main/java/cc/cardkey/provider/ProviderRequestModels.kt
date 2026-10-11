package cc.cardkey.provider

import cc.cardkey.protocol.fido.FidoUser

enum class ResidentKeyRequirement { DISCOURAGED, PREFERRED, REQUIRED }
enum class UserVerificationRequirement { DISCOURAGED, PREFERRED, REQUIRED }
enum class AttestationPreference { NONE, INDIRECT, DIRECT }

data class ParsedCreate(
    val rpId: String,
    val rpName: String,
    val user: FidoUser,
    val challenge: String,
    val residentKey: ResidentKeyRequirement,
    val userVerification: UserVerificationRequirement,
    val excludeIds: List<ByteArray>,
    val attestation: AttestationPreference,
    val extensions: CreateExtensionInputs = CreateExtensionInputs(),
)

data class ParsedGet(
    val rpId: String,
    val challenge: String,
    val allowIds: List<ByteArray>,
    val userVerification: UserVerificationRequirement,
    val extensions: GetExtensionInputs = GetExtensionInputs(),
)

data class ClientData(val json: ByteArray, val hash: ByteArray, val browserDelegated: Boolean)

class ProviderRequestException(val unsupported: Boolean = false) : IllegalArgumentException()
class CallerVerificationException : SecurityException()
