package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode

data class FidoInfo(val versions: List<String>, val extensions: Set<String>, val options: Map<String, Boolean>,
    val pinProtocols: List<Int>, val minPinLength: Int, val maxCredentialBlobLength: Int?, val raw: Cbor.Map) {
    internal fun protocol() = pinProtocols.firstOrNull { it == 2 } ?: pinProtocols.firstOrNull { it == 1 }
        ?: throw CardFailure(FailureCode.UNSUPPORTED)
    internal fun requireOption(name: String) { if (options[name] != true) throw CardFailure(FailureCode.UNSUPPORTED) }
}
data class PinRetries(val pin: Int, val powerCycleState: Boolean?, val uv: Int?)
data class CredentialCapacity(val existing: Int, val remaining: Int)
data class RelyingParty(val id: String, val name: String?, val idHash: ByteArray)
data class FidoUser(val id: ByteArray, val name: String?, val displayName: String?) {
    internal fun encode(): Cbor.Map {
        require(id.size in 1..64)
        return textMap("id" to id.cbor(), "name" to name?.cbor(), "displayName" to displayName?.cbor())
    }
}
data class Credential(val id: ByteArray, val user: FidoUser, val publicKey: Cbor.Map, val protection: Int?)
data class MakeCredentialRequest(val rpId: String, val rpName: String, val user: FidoUser,
    val clientDataHash: ByteArray, val algorithms: List<Int> = listOf(-7), val residentKey: Boolean = true,
    val requireUv: Boolean = true, val excludeIds: List<ByteArray> = emptyList(),
    val extensions: MakeExtensions = MakeExtensions(), val enterpriseAttestation: Int? = null)
data class MakeExtensions(val credentialProtection: Int? = null, val credentialBlob: ByteArray? = null,
    val hmacSecret: Boolean = false, val minPinLength: Boolean = false, val largeBlobKey: Boolean = false)
data class GetAssertionRequest(val rpId: String, val clientDataHash: ByteArray,
    val allowIds: List<ByteArray> = emptyList(), val requireUv: Boolean = true,
    val extensions: AssertionExtensions = AssertionExtensions())
data class AssertionExtensions(val credentialBlob: Boolean = false, val hmacSalts: ByteArray? = null,
    val largeBlobKey: Boolean = false)
data class MakeCredentialResult(val format: String, val authData: ByteArray, val statement: Cbor.Map,
    val largeBlobKey: ByteArray?, val enterpriseAttestation: Boolean?)
data class Assertion(val credentialId: ByteArray, val authData: ByteArray, val signature: ByteArray,
    val user: FidoUser?, val hmacSecret: ByteArray?, val largeBlobKey: ByteArray?)

internal fun descriptor(id: ByteArray): Cbor.Map {
    require(id.isNotEmpty() && id.size <= 4096)
    return textMap("id" to id.cbor(), "type" to "public-key".cbor())
}
internal fun parseDescriptor(value: Cbor?): ByteArray {
    val map = value.map()
    if (map["type"].text() != "public-key") malformed()
    return map["id"].bytes().also { if (it.isEmpty() || it.size > 4096) malformed() }
}
internal fun parseUser(value: Cbor?): FidoUser {
    val map = value.map(); val id = map["id"].bytes()
    if (id.size !in 1..64) malformed()
    return FidoUser(id, map["name"]?.text(), map["displayName"]?.text())
}
internal fun Cbor?.count(maximum: Int = 4096): Int {
    val value = number(); if (value !in 0..maximum.toLong()) malformed(); return value.toInt()
}
