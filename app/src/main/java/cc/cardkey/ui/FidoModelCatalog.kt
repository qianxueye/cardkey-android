package cc.cardkey.ui

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface FidoModelName {
    data class Known(val name: String) : FidoModelName
    data object Missing : FidoModelName
    data object Failed : FidoModelName
}

internal class FidoModelCatalog(
    private val fetch: (String?) -> Response,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var names: Map<String, Map<String, String>>? = null
    private var etag: String? = null
    private var nextFetch = 0L
    private var flight: Deferred<Unit>? = null

    suspend fun lookup(aaguid: String, locale: String): FidoModelName {
        val pending = synchronized(this) {
            if (now() < nextFetch) null else flight ?: scope.async(start = CoroutineStart.LAZY) { refresh() }
                .also { flight = it; it.start() }
        }
        pending?.await()
        return cached(aaguid, locale) ?: FidoModelName.Failed
    }

    fun cached(aaguid: String, locale: String): FidoModelName? = synchronized(this) {
            val catalog = names ?: return@synchronized null
            val localized = catalog[aaguid] ?: return@synchronized FidoModelName.Missing
            val language = Locale.forLanguageTag(locale).language
            val name = sequenceOf(locale, language, "en-US").mapNotNull { tag ->
                localized.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
            }.firstOrNull()
            name?.let(FidoModelName::Known) ?: FidoModelName.Missing
    }

    private fun refresh() {
        var cooldown = 60_000L
        try {
            fetch(etag).use { response ->
                if (response.code == 429) {
                    val retry = response.header("Retry-After")
                    val seconds = retry?.toLongOrNull()?.coerceIn(0, 86_400)?.times(1000)
                    val date = retry?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now() }.getOrNull() }
                    cooldown = (seconds ?: date ?: cooldown).coerceIn(60_000, 86_400_000)
                } else if (response.code == 304 && names != null) {
                    cooldown = freshness(response)
                } else {
                    check(response.isSuccessful)
                    val body = requireNotNull(response.body)
                    check(body.contentLength() <= 10 * 1024 * 1024)
                    val json = body.source().apply { request(10L * 1024 * 1024 + 1) }
                    check(json.buffer.size <= 10L * 1024 * 1024)
                    val root = JsonParser.parseString(json.readUtf8()).asJsonObject
                    check(root["no"].asLong >= 0)
                    val parsed = root.entrySet().filter { it.key != "no" }.associate { (key, value) ->
                        check(UUID.fromString(key).toString() == key)
                        key to value.asJsonObject["friendlyNames"].asJsonObject.entrySet().associate { (tag, name) ->
                            check(name.isJsonPrimitive && name.asJsonPrimitive.isString)
                            tag to name.asString.trim()
                        }.filterValues { it.isNotEmpty() }
                    }
                    synchronized(this) { names = parsed; etag = response.header("ETag") ?: "\"${root["no"].asLong}\"" }
                    cooldown = freshness(response)
                }
            }
        } catch (_: Exception) {
            // A failed refresh keeps the last successfully parsed catalog.
        } finally {
            synchronized(this) { nextFetch = now() + cooldown; flight = null }
        }
    }

    private fun freshness(response: Response): Long {
        val maxAge = response.cacheControl.maxAgeSeconds
        val age = response.header("Age")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        return if (response.cacheControl.noCache || response.cacheControl.noStore) 0
        else if (maxAge >= 0) ((maxAge.toLong() - age).coerceAtLeast(0) * 1000)
        else TimeUnit.HOURS.toMillis(1)
    }

    companion object {
        @Volatile private var shared: FidoModelCatalog? = null
        fun shared(cacheDir: File): FidoModelCatalog = shared ?: synchronized(this) {
            shared ?: run {
                val client = OkHttpClient.Builder().cache(Cache(File(cacheDir, "fido-model-http"), 12L * 1024 * 1024))
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
                FidoModelCatalog({ tag ->
                    val request = Request.Builder().url("https://c-mds.fidoalliance.org/")
                    if (tag != null) request.header("If-None-Match", tag)
                    client.newCall(request.build()).execute()
                }).also { shared = it }
            }
        }
    }
}
