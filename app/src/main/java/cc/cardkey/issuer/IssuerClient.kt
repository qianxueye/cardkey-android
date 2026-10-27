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
internal data class BadgeRevision(val id: String, val card: String, val person: String, val name: String, val reference: String, val department: String, val title: String, val badge: String, val created: String, val sourceCreated: String?, val actor: String?, val front: BadgeImage, val back: BadgeImage)
internal data class IssuerPage<T>(val items: List<T>, val next: String)
internal class IssuerFailure(val status: Int) : IOException()
internal class IssuerIdentityFailure : IllegalArgumentException()
internal enum class IssuerRole { EMPLOYEE, OPERATOR, ADMINISTRATOR }
internal data class IssuerIdentity(val id: String, val name: String, val role: IssuerRole, val personID: String?) {
    val staff get() = role != IssuerRole.EMPLOYEE
}
internal fun JsonObject.text(key: String): String {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
    return value.asString
}

internal class IssuerClient(val origin: String, private val token: String, client: OkHttpClient = OkHttpClient()) {
    private var identity: IssuerIdentity? = null
    private fun account() = requireNotNull(identity)
    private fun staff() = account().also { require(it.staff) }
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
    suspend fun me(): IssuerIdentity {
        identity = null
        val result = json("/v1/me")
        val verified = try {
            val value = result.get("operator")
            require(value != null && value.isJsonObject)
            val operator = value.asJsonObject
            val role = when (operator.text("role")) {
                "employee" -> IssuerRole.EMPLOYEE
                "operator" -> IssuerRole.OPERATOR
                "administrator" -> IssuerRole.ADMINISTRATOR
                else -> throw IssuerIdentityFailure()
            }
            val advertised = result.get("capabilities")
            require(advertised != null && advertised.isJsonArray)
            val capabilities = advertised.asJsonArray.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
            }
            val staffCapabilities = setOf("people:read", "people:write", "cards:read", "cards:write", "issuances:write", "events:read")
            val expected = when (role) {
                IssuerRole.EMPLOYEE -> setOf("self:read", "self:artwork:read")
                IssuerRole.OPERATOR -> staffCapabilities
                IssuerRole.ADMINISTRATOR -> staffCapabilities + "issuances:close-out"
            }
            require(capabilities.size == expected.size && capabilities.toSet() == expected)
            val administrator = operator.get("administrator")
            require(administrator != null && administrator.isJsonPrimitive && administrator.asJsonPrimitive.isBoolean &&
                administrator.asBoolean == (role == IssuerRole.ADMINISTRATOR))
            val person = if (role == IssuerRole.EMPLOYEE) recordID(operator.text("person_id"))
                else { require(!operator.has("person_id")); null }
            val id = operator.text("id")
            val name = operator.text("display_name")
            require(listOf(id, name).all { it.isNotBlank() && it.codePointCount(0, it.length) <= 255 && '\u0000' !in it })
            IssuerIdentity(id, name, role, person)
        } catch (_: IllegalArgumentException) { throw IssuerIdentityFailure() }
          catch (_: IllegalStateException) { throw IssuerIdentityFailure() }
        identity = verified
        return verified
    }
    private fun <T> page(value: JsonObject, decode: (JsonObject) -> T): IssuerPage<T> {
        val items = value.getAsJsonArray("items")
        require(items.size() <= 100)
        return IssuerPage(items.map { decode(it.asJsonObject) }, value.text("next_cursor").also { require(it.length <= 1024) })
    }
    private fun person(value: JsonObject): IssuerPerson {
        val status = value.text("employment_status")
        require(status in setOf("active", "inactive"))
        return IssuerPerson(recordID(value.text("id")), value.text("display_name"), value.text("employee_reference"),
            value.text("department"), value.text("job_title"), value.text("badge_number"), status == "active")
    }
    suspend fun people(cursor: String = ""): IssuerPage<IssuerPerson> {
        staff()
        return page(json("/v1/people", mapOf("limit" to "100", "cursor" to cursor)), ::person)
    }
    suspend fun selfPerson(): IssuerPerson {
        val account = account(); require(!account.staff)
        return person(json("/v1/me/person")).also { require(it.id == account.personID) }
    }
    suspend fun cards(person: String, cursor: String = ""): IssuerPage<IssuerCard> {
        val account = account()
        recordID(person)
        if (!account.staff) require(person == account.personID)
        val path = if (account.staff) "/v1/cards" else "/v1/me/cards"
        val params = mapOf("limit" to "100", "cursor" to cursor) + if (account.staff) mapOf("person_id" to person) else emptyMap()
        return page(json(path, params)) {
            IssuerCard(recordID(it.text("id")), recordID(it.text("person_id")), it.text("serial"), it.text("status")).also { card -> require(card.person == person) }
        }
    }
    private fun image(value: JsonObject) = BadgeImage(value.text("media_type"), value.get("byte_length").asInt,
        value.get("width").asInt, value.get("height").asInt, value.text("accessibility_label")).also {
        require(it.type in setOf("image/png", "image/jpeg") && it.length in 1..1_048_576 && it.width in 1..1200 && it.height in 1..1200 && it.label.isNotBlank() && it.label.codePointCount(0, it.label.length) <= 200 && '\u0000' !in it.label)
    }
    private fun revision(value: JsonObject, card: IssuerCard): BadgeRevision {
        val person = value.getAsJsonObject("person_snapshot")
        return BadgeRevision(recordID(value.text("id")), recordID(value.text("card_id")), recordID(value.text("person_id")),
            person.text("display_name"), person.text("employee_reference"), person.text("department"), person.text("job_title"), person.text("badge_number"),
            value.text("created_at"), if (account().staff) value.text("source_created_at") else null,
            if (account().staff) value.text("actor") else null, image(value.getAsJsonObject("front")), image(value.getAsJsonObject("back"))).also {
            require(it.card == card.id && it.person == card.person)
        }
    }
    suspend fun current(card: IssuerCard): BadgeRevision? {
        val account = account()
        if (!account.staff) require(card.person == account.personID)
        return try { revision(json("/v1/${if (account.staff) "" else "me/"}cards/${recordID(card.id)}/badge-current"), card) }
            catch (failure: IssuerFailure) { if (failure.status == 404) null else throw failure }
    }
    suspend fun history(card: IssuerCard, cursor: String = ""): IssuerPage<BadgeRevision> {
        staff()
        return page(json("/v1/cards/${recordID(card.id)}/badge-revisions", mapOf("limit" to "100", "cursor" to cursor))) { revision(it, card) }
    }
    suspend fun artwork(revision: BadgeRevision, back: Boolean): cc.cardkey.ui.CardArtworkPayload {
        val account = account()
        if (!account.staff) require(revision.person == account.personID)
        val descriptor = if (back) revision.back else revision.front
        val bytes = get("/v1/${if (account.staff) "" else "me/"}badge-revisions/${recordID(revision.id)}/${if (back) "back" else "front"}", media = descriptor)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth == descriptor.width && options.outHeight == descriptor.height && options.outMimeType == descriptor.type)
        return cc.cardkey.ui.CardArtworkPayload(bytes, descriptor.label)
    }
}
