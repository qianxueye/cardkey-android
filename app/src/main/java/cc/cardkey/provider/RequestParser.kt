package cc.cardkey.provider

import cc.cardkey.protocol.fido.FidoUser
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.util.Base64

object RequestParser {
    fun parseCreate(json: String): ParsedCreate = parseRequest {
        val root = StrictJson.parse(json).objectValue()
        val rp = root.required("rp").objectValue()
        val user = root.required("user").objectValue()
        val algorithms = root.required("pubKeyCredParams").arrayValue()
        if (algorithms.size() !in 1..64) invalidRequest()
        val supported = algorithms.map {
            val algorithm = it.objectValue()
            val type = algorithm.required("type").stringValue()
            val alg = algorithm.required("alg").integerValue()
            type == "public-key" && alg == -7L
        }.any { it }
        if (!supported) unsupportedRequest()
        val selection = root.optional("authenticatorSelection")?.objectValue()
        selection?.optional("authenticatorAttachment")?.stringValue()?.let {
            if (it != "cross-platform") unsupportedRequest()
        }
        val legacyResident = selection?.optional("requireResidentKey")?.booleanValue() ?: false
        val resident = selection?.optional("residentKey")?.stringValue()?.let {
            when (it) {
                "discouraged" -> ResidentKeyRequirement.DISCOURAGED
                "preferred" -> ResidentKeyRequirement.PREFERRED
                "required" -> ResidentKeyRequirement.REQUIRED
                else -> invalidRequest()
            }
        } ?: if (legacyResident) ResidentKeyRequirement.REQUIRED else ResidentKeyRequirement.DISCOURAGED
        val attestation = when (root.optional("attestation")?.stringValue() ?: "none") {
            "none" -> AttestationPreference.NONE
            "indirect" -> AttestationPreference.INDIRECT
            "direct" -> AttestationPreference.DIRECT
            "enterprise" -> unsupportedRequest()
            else -> invalidRequest()
        }
        commonFields(root)
        val extensions = ExtensionParser.create(root.optional("extensions"))
        ParsedCreate(
            rpId = validateRpId(rp.required("id").stringValue()),
            rpName = rp.required("name").stringValue().boundedText(),
            user = FidoUser(
                decodeBase64Url(user.required("id").stringValue(), 1, 64),
                user.required("name").stringValue().boundedText(),
                user.required("displayName").stringValue().boundedText(),
            ),
            challenge = challenge(root),
            residentKey = resident,
            userVerification = verification(selection?.optional("userVerification")),
            excludeIds = descriptors(root.optional("excludeCredentials")),
            attestation = attestation,
            extensions = extensions,
        )
    }

    fun parseGet(json: String): ParsedGet = parseRequest {
        val root = StrictJson.parse(json).objectValue()
        commonFields(root)
        val allowIds = descriptors(root.optional("allowCredentials"))
        val extensions = ExtensionParser.get(root.optional("extensions"), allowIds)
        ParsedGet(
            validateRpId(root.required("rpId").stringValue()), challenge(root),
            allowIds, verification(root.optional("userVerification")), extensions,
        )
    }

    private fun commonFields(root: JsonObject) {
        root.optional("timeout")?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber ||
                it.asBigDecimal.signum() < 0 || it.asBigDecimal > BigDecimal("4294967295")) invalidRequest()
        }
        root.optional("hints")?.arrayValue()?.forEach { it.stringValue() }
    }

    private fun challenge(root: JsonObject): String = root.required("challenge").stringValue().also {
        decodeBase64Url(it, 1, 4096)
    }

    private fun verification(value: JsonElement?): UserVerificationRequirement = when (value?.stringValue() ?: "preferred") {
        "discouraged" -> UserVerificationRequirement.DISCOURAGED
        "preferred" -> UserVerificationRequirement.PREFERRED
        "required" -> UserVerificationRequirement.REQUIRED
        else -> invalidRequest()
    }

    private fun descriptors(value: JsonElement?): List<ByteArray> {
        if (value == null) return emptyList()
        val array = value.arrayValue()
        if (array.size() > 64) invalidRequest()
        return array.map {
            val entry = it.objectValue()
            if (entry.required("type").stringValue() != "public-key") unsupportedRequest()
            entry.optional("transports")?.arrayValue()?.forEach { transport -> transport.stringValue() }
            decodeBase64Url(entry.required("id").stringValue(), 1, 4096)
        }
    }


}

internal fun validateRpId(value: String): String {
    if (value.length !in 1..253 || value.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != '.' }) invalidRequest()
    val labels = value.split('.')
    if (labels.any { it.length !in 1..63 || it.startsWith('-') || it.endsWith('-') } ||
        labels.all { label -> label.all { it in '0'..'9' } }) invalidRequest()
    return value
}

internal fun decodeBase64Url(value: String, minimum: Int, maximum: Int): ByteArray {
    if (value.length > (maximum * 4 + 2) / 3 || value.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != '_' }) invalidRequest()
    val bytes = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) { invalidRequest() }
    if (bytes.size !in minimum..maximum || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) invalidRequest()
    return bytes
}

internal object StrictJson {
    const val MAX_BYTES = 262144

    fun parse(json: String): JsonElement {
        if (json.length > MAX_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) invalidRequest()
        return JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            val result = read(reader, 0)
            if (reader.peek() != JsonToken.END_DOCUMENT) invalidRequest()
            result
        }
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        if (depth > 32) invalidRequest()
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (has(name)) invalidRequest()
                    add(name, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(read(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> invalidRequest()
        }
    }
}

internal fun JsonObject.required(key: String): JsonElement = get(key) ?: invalidRequest()
internal fun JsonObject.optional(key: String): JsonElement? = get(key)
internal fun JsonElement.objectValue(): JsonObject = if (isJsonObject) asJsonObject else invalidRequest()
internal fun JsonElement.arrayValue(): JsonArray = if (isJsonArray) asJsonArray else invalidRequest()
internal fun JsonElement.stringValue(): String = if (isJsonPrimitive && asJsonPrimitive.isString) asString else invalidRequest()
internal fun JsonElement.booleanValue(): Boolean = if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else invalidRequest()
internal fun JsonElement.integerValue(): Long {
    if (!isJsonPrimitive || !asJsonPrimitive.isNumber) invalidRequest()
    return try { asBigDecimal.longValueExact() } catch (_: ArithmeticException) { invalidRequest() }
}
private fun String.boundedText(): String = also { if (length > 1024) invalidRequest() }
internal fun invalidRequest(): Nothing = throw ProviderRequestException()
internal fun unsupportedRequest(): Nothing = throw ProviderRequestException(true)
private inline fun <T> parseRequest(block: () -> T): T = try { block() } catch (failure: ProviderRequestException) {
    throw failure
} catch (_: Exception) { invalidRequest() }
