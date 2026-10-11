package cc.cardkey.provider

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.fido.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64

class PrfInputs(val first: ByteArray, val second: ByteArray? = null) {
    fun salts(): ByteArray = sha256("WebAuthn PRF\u0000".toByteArray(Charsets.UTF_8) + first) +
        (second?.let { sha256("WebAuthn PRF\u0000".toByteArray(Charsets.UTF_8) + it) } ?: byteArrayOf())
    fun sameAs(other: PrfInputs?): Boolean = other != null && first.contentEquals(other.first) &&
        (second?.contentEquals(other.second) ?: (other.second == null))
}

class CreateExtensionInputs(val credProps: Boolean = false, val protection: Int? = null,
    val enforceProtection: Boolean = false, val credBlob: ByteArray? = null,
    val uvm: Boolean = false, val prf: Boolean = false, val minPinLength: Boolean = false,
    val largeBlobSupport: String? = null)

class GetExtensionInputs(val getCredBlob: Boolean = false, val uvm: Boolean = false,
    val prf: Boolean = false, val prfInputs: PrfInputs? = null,
    val largeBlobRead: Boolean = false, val largeBlobWrite: ByteArray? = null,
    val prfByCredential: Map<String, PrfInputs> = emptyMap()) {
    fun inputsFor(id: ByteArray): PrfInputs? = prfByCredential[encodeExtensionBytes(id)] ?: prfInputs
}

internal object ExtensionParser {
    fun create(value: JsonElement?): CreateExtensionInputs {
        val ext = value?.objectValue() ?: return CreateExtensionInputs()
        unsupported(ext, true)
        val policy = ext.optional("credentialProtectionPolicy")?.stringValue()?.let {
            when (it) {
                "userVerificationOptional" -> 1
                "userVerificationOptionalWithCredentialIDList" -> 2
                "userVerificationRequired" -> 3
                else -> invalidRequest()
            }
        }
        val enforce = ext.optional("enforceCredentialProtectionPolicy")?.booleanValue() ?: false
        if (enforce && policy == null) invalidRequest()
        val blob = ext.optional("largeBlob")?.objectValue()
        if (blob?.has("read") == true || blob?.has("write") == true) unsupportedRequest()
        val support = blob?.let { it.optional("support")?.stringValue() ?: "preferred" }?.also {
            if (it !in setOf("required", "preferred")) invalidRequest()
        }
        ext.optional("prf")?.objectValue()?.let {
            if (it.has("evalByCredential")) unsupportedRequest()
            it.optional("eval")?.let(::prfValues)
        }
        return CreateExtensionInputs(ext.flag("credProps"), policy, enforce,
            ext.optional("credBlob")?.let { decodeBase64Url(it.stringValue(), 0, 65535) },
            ext.flag("uvm"), ext.has("prf"), ext.flag("minPinLength"), support)
    }

    fun get(value: JsonElement?, allowIds: List<ByteArray>): GetExtensionInputs {
        val ext = value?.objectValue() ?: return GetExtensionInputs()
        unsupported(ext, false)
        val blob = ext.optional("largeBlob")?.objectValue()
        if (blob?.has("support") == true) unsupportedRequest()
        if (blob?.has("read") == true && blob.has("write")) unsupportedRequest()
        val read = blob?.optional("read")?.booleanValue() ?: false
        val write = blob?.optional("write")?.let { decodeBase64Url(it.stringValue(), 0, MAX_PROVIDER_BLOB) }
        if (write != null && allowIds.size != 1) unsupportedRequest()
        val prf = ext.optional("prf")?.objectValue()
        val fallback = prf?.optional("eval")?.let(::prfValues)
        if (prf?.optional("evalByCredential")?.objectValue()?.size()?.let { it > 0 } == true && allowIds.isEmpty()) unsupportedRequest()
        val perCredential = prf?.optional("evalByCredential")?.objectValue()?.entrySet()?.associate { (key, item) ->
            val id = decodeBase64Url(key, 1, 4096)
            if (allowIds.none { it.contentEquals(id) }) invalidRequest()
            key to prfValues(item)
        } ?: emptyMap()
        if (perCredential.isNotEmpty() && allowIds.isEmpty()) unsupportedRequest()
        return GetExtensionInputs(ext.flag("getCredBlob"), ext.flag("uvm"), prf != null, fallback, read, write, perCredential)
    }

    private fun prfValues(value: JsonElement): PrfInputs = value.objectValue().let {
        PrfInputs(decodeBase64Url(it.required("first").stringValue(), 0, 65535),
            it.optional("second")?.let { second -> decodeBase64Url(second.stringValue(), 0, 65535) })
    }
    private fun JsonObject.flag(name: String) = optional(name)?.booleanValue() ?: false
    private fun unsupported(ext: JsonObject, create: Boolean) {
        if (ext.has(if (create) "appidExclude" else "appid") || ext.has("payment")) unsupportedRequest()
    }
}

internal const val MAX_PROVIDER_BLOB = 65535
internal fun encodeExtensionBytes(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

class ClientExtensionOutputs(val residentKey: Boolean? = null, val prfEnabled: Boolean? = null,
    val prfRequested: Boolean = false, val prfResult: ByteArray? = null,
    val largeBlobSupported: Boolean? = null, val largeBlobRequested: Boolean = false,
    val largeBlob: ByteArray? = null, val largeBlobWritten: Boolean? = null,
    val uvm: List<List<Long>>? = null) : AutoCloseable {
    internal fun json() = JsonObject().apply {
        residentKey?.let { add("credProps", JsonObject().apply { addProperty("rk", it) }) }
        if (prfRequested) add("prf", JsonObject().apply {
            prfEnabled?.let { addProperty("enabled", it) }
            prfResult?.let {
                if (it.size != 32 && it.size != 64) malformed()
                add("results", JsonObject().apply {
                    addProperty("first", encodeExtensionBytes(it.copyOfRange(0, 32)))
                    if (it.size == 64) addProperty("second", encodeExtensionBytes(it.copyOfRange(32, 64)))
                })
            }
        })
        if (largeBlobRequested) add("largeBlob", JsonObject().apply {
            largeBlobSupported?.let { addProperty("supported", it) }
            largeBlob?.let { addProperty("blob", encodeExtensionBytes(it)) }
            largeBlobWritten?.let { addProperty("written", it) }
        })
        uvm?.let { rows -> add("uvm", JsonArray().apply {
            rows.forEach { row -> add(JsonArray().apply { row.forEach(::add) }) }
        }) }
    }
    override fun close() { prfResult?.fill(0); largeBlob?.fill(0) }
}

class ProvidedAssertion(val assertion: Assertion, val extensions: ClientExtensionOutputs = ClientExtensionOutputs()) : AutoCloseable {
    override fun close() {
        extensions.close()
        assertion.hmacSecret?.fill(0)
        assertion.largeBlobKey?.fill(0)
    }
}

internal fun FidoInfo.supportsLargeBlob() = "largeBlobKey" in extensions && options["largeBlobs"] == true

internal fun uvmOutput(auth: AuthenticatorData, requested: Boolean): List<List<Long>>? {
    val value = auth.extensions?.get("uvm") ?: return null
    if (!requested) malformed()
    val rows = value.array()
    if (rows.size !in 1..3) malformed()
    return rows.map { row ->
        val cells = row.array()
        if (cells.size != 3) malformed()
        cells.mapIndexed { index, cell -> cell.number().also {
            if (it !in 0..(if (index == 0) 0xffffffffL else 0xffffL)) malformed()
        } }
    }
}

internal fun CreateExtensionInputs.forCard(info: FidoInfo, resident: Boolean): MakeExtensions {
    if (enforceProtection && (protection ?: 1) > 1 && "credProtect" !in info.extensions)
        throw CardFailure(FailureCode.UNSUPPORTED)
    val large = largeBlobSupport != null && resident && info.supportsLargeBlob()
    if (largeBlobSupport == "required" && !large) throw CardFailure(FailureCode.UNSUPPORTED)
    return MakeExtensions(protection?.takeIf { "credProtect" in info.extensions },
        credBlob?.takeIf { "credBlob" in info.extensions && it.size <= (info.maxCredentialBlobLength ?: 0) },
        prf && "hmac-secret" in info.extensions, minPinLength && "minPinLength" in info.extensions,
        large, uvm && "uvm" in info.extensions)
}
