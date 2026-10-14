package cc.cardkey.enrollment

import com.google.gson.JsonObject
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

enum class IssuanceFailure { INVALID, REJECTED, UNKNOWN }
class EnrollmentException(val failure: IssuanceFailure) : Exception()

class EnrollmentCA(address: String, root: ByteArray) {
    val endpoint: HttpUrl
    private val selectedRoot = EnrollmentProfile.root(root)
    val anchor get() = selectedRoot.copyOf()
    init {
        val uri = URI(address)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/"))
        endpoint = address.toHttpUrl().newBuilder().encodedPath("/1.0/sign").build()
    }
    fun checkToken(token: String, csr: EnrollmentCsr, now: Long = System.currentTimeMillis()) {
        require(token.length in 1..16384)
        val pieces = token.split('.')
        require(pieces.size == 3 && pieces.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' } })
        val claims = GsonBuilder().setStrictness(Strictness.STRICT).create()
            .fromJson(Base64.getUrlDecoder().decode(pieces[1]).toString(Charsets.UTF_8), JsonObject::class.java)
        require(claims["sub"].asString == csr.cn)
        require(claims["cnf"].asJsonObject["x5rt#S256"].asString == csr.binding)
        fun seconds(key: String): Long {
            val value = claims[key]
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            return value.asBigDecimal.longValueExact()
        }
        val expiry = seconds("exp")
        val issued = seconds("iat")
        val time = now / 1000
        require(issued >= 0 && expiry > time && expiry > issued && expiry - issued <= 600 && issued <= time + 30)
        if (claims.has("nbf")) require(seconds("nbf") <= time)
        val audience = claims["aud"]
        val audiences = if (audience.isJsonArray) audience.asJsonArray.map { it.asString } else listOf(audience.asString)
        require(endpoint.toString() in audiences)
    }
    internal fun client(): OkHttpClient {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("selected", EnrollmentProfile.certificate(selectedRoot))
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val manager = managers.trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, manager)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .cache(null).callTimeout(45, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()
    }
    fun call(csr: EnrollmentCsr, token: String): Call {
        checkToken(token, csr)
        val body = JsonObject().apply { addProperty("csr", csr.pem()); addProperty("ott", token) }.toString()
        val dispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        val singleAttempt = client().newBuilder().addNetworkInterceptor { chain ->
            // Some HTTP follow-ups (for example 503 Retry-After: 0) are independent of connection retry policy.
            if (!dispatched.compareAndSet(false, true)) throw java.io.IOException()
            chain.proceed(chain.request())
        }.build()
        return singleAttempt.newCall(Request.Builder().url(endpoint).post(body.toRequestBody("application/json".toMediaType())).build())
    }
    fun execute(call: Call, csr: EnrollmentCsr): EnrollmentBundle {
        try {
            call.execute().use { response ->
                if (response.code in setOf(400, 401, 403, 404, 405, 422)) throw EnrollmentException(IssuanceFailure.REJECTED)
                if (response.code != 201) throw EnrollmentException(IssuanceFailure.UNKNOWN)
                try {
                    val body = requireNotNull(response.body)
                    val length = body.contentLength()
                    require(length <= EnrollmentProfile.MAX_BUNDLE)
                    val source = body.source()
                    require(!source.request(EnrollmentProfile.MAX_BUNDLE.toLong() + 1))
                    val bytes = source.readByteArray()
                    if (length >= 0 && bytes.size.toLong() != length) throw java.io.IOException()
                    val objectValue = GsonBuilder().setStrictness(Strictness.STRICT).create()
                        .fromJson(bytes.toString(Charsets.UTF_8), JsonObject::class.java)
                    val entries = objectValue["certChain"].asJsonArray
                    require(entries.size() in 1..5)
                    val chain = entries.flatMap { EnrollmentProfile.certificates(it.asString) }
                    return EnrollmentProfile.validate(chain, selectedRoot, csr)
                } catch (_: java.io.IOException) { throw EnrollmentException(IssuanceFailure.UNKNOWN) }
                catch (_: Exception) { throw EnrollmentException(IssuanceFailure.INVALID) }
            }
        } catch (failure: EnrollmentException) { throw failure }
        catch (_: Exception) { throw EnrollmentException(IssuanceFailure.UNKNOWN) }
    }
}
