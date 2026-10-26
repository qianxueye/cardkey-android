package cc.cardkey.issuer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun issuerOrigin(value: String): String {
    val url = value.trim().toHttpUrl()
    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath == "/" && url.query == null && url.fragment == null)
    return url.toString().removeSuffix("/")
}
internal fun recordID(value: String): String {
    require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value))
    return value
}
internal data class IssuerPerson(val id: String, val name: String, val reference: String, val department: String, val title: String, val badge: String, val active: Boolean)
internal data class IssuerCard(val id: String, val person: String, val serial: String, val status: String)
internal data class BadgeImage(val type: String, val length: Int, val width: Int, val height: Int, val label: String)
internal data class BadgeRevision(val id: String, val card: String, val person: String, val name: String, val reference: String, val department: String, val title: String, val badge: String, val created: String, val sourceCreated: String, val actor: String, val front: BadgeImage, val back: BadgeImage)
internal data class IssuerPage<T>(val items: List<T>, val next: String)
internal class IssuerFailure(val status: Int) : IOException()
internal fun JsonObject.text(key: String): String = get(key).asString

internal class IssuerClient(val origin: String, private val token: String, client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(java.time.Duration.ofSeconds(25)).cache(null).build()

    private suspend fun get(path: String, params: Map<String, String> = emptyMap(), media: BadgeImage? = null): ByteArray {
        val url = origin.toHttpUrl().newBuilder().encodedPath(path).apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", media?.type ?: "application/json").build()
        val limit = if (media == null) 4_194_304 else 1_048_576
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (it.code != 200) throw IssuerFailure(it.code)
                        val body = requireNotNull(it.body)
                        val type = body.contentType()?.let { type -> "${type.type}/${type.subtype}" }
                        require(type == (media?.type ?: "application/json"))
                        require(body.contentLength() <= limit)
                        val bytes = body.byteStream().use { input -> input.readNBytes(limit + 1) }
                        require(bytes.size in 1..limit)
                        if (media != null) require(bytes.size == media.length)
                        bytes
                    } }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
    private suspend fun json(path: String, params: Map<String, String> = emptyMap()) =
        JsonParser.parseString(get(path, params).toString(Charsets.UTF_8)).asJsonObject
    suspend fun me(): String {
        val result = json("/v1/me")
        val capabilities = result.getAsJsonArray("capabilities").map { it.asString }
        require("cards:read" in capabilities && "people:read" in capabilities)
        return result.getAsJsonObject("operator").text("id").also { require(it.isNotBlank()) }
    }
    private fun <T> page(value: JsonObject, decode: (JsonObject) -> T): IssuerPage<T> {
        val items = value.getAsJsonArray("items")
        require(items.size() <= 100)
        return IssuerPage(items.map { decode(it.asJsonObject) }, value.text("next_cursor").also { require(it.length <= 1024) })
    }
    suspend fun people(cursor: String = "") = page(json("/v1/people", mapOf("limit" to "100", "cursor" to cursor))) {
        IssuerPerson(recordID(it.text("id")), it.text("display_name"), it.text("employee_reference"), it.text("department"), it.text("job_title"), it.text("badge_number"), it.text("employment_status") == "active")
    }
    suspend fun cards(person: String, cursor: String = "") = page(json("/v1/cards", mapOf("person_id" to recordID(person), "limit" to "100", "cursor" to cursor))) {
        IssuerCard(recordID(it.text("id")), recordID(it.text("person_id")), it.text("serial"), it.text("status")).also { card -> require(card.person == person) }
    }
    private fun image(value: JsonObject) = BadgeImage(value.text("media_type"), value.get("byte_length").asInt,
        value.get("width").asInt, value.get("height").asInt, value.text("accessibility_label")).also {
        require(it.type in setOf("image/png", "image/jpeg") && it.length in 1..1_048_576 && it.width in 1..1200 && it.height in 1..1200 && it.label.isNotBlank() && it.label.codePointCount(0, it.label.length) <= 200 && '\u0000' !in it.label)
    }
    private fun revision(value: JsonObject, card: IssuerCard): BadgeRevision {
        val person = value.getAsJsonObject("person_snapshot")
        return BadgeRevision(recordID(value.text("id")), recordID(value.text("card_id")), recordID(value.text("person_id")),
            person.text("display_name"), person.text("employee_reference"), person.text("department"), person.text("job_title"), person.text("badge_number"),
            value.text("created_at"), value.text("source_created_at"), value.text("actor"), image(value.getAsJsonObject("front")), image(value.getAsJsonObject("back"))).also {
            require(it.card == card.id && it.person == card.person)
        }
    }
    suspend fun current(card: IssuerCard): BadgeRevision? = try { revision(json("/v1/cards/${recordID(card.id)}/badge-current"), card) }
        catch (failure: IssuerFailure) { if (failure.status == 404) null else throw failure }
    suspend fun history(card: IssuerCard, cursor: String = "") = page(json("/v1/cards/${recordID(card.id)}/badge-revisions", mapOf("limit" to "100", "cursor" to cursor))) { revision(it, card) }
    suspend fun artwork(revision: BadgeRevision, back: Boolean): cc.cardkey.ui.CardArtworkPayload {
        val descriptor = if (back) revision.back else revision.front
        val bytes = get("/v1/badge-revisions/${recordID(revision.id)}/${if (back) "back" else "front"}", media = descriptor)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth == descriptor.width && options.outHeight == descriptor.height && options.outMimeType == descriptor.type)
        return cc.cardkey.ui.CardArtworkPayload(bytes, descriptor.label)
    }
}
