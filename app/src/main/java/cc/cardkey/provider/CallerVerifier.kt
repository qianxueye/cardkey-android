package cc.cardkey.provider

import androidx.credentials.provider.CallingAppInfo
import com.google.gson.JsonObject
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class CallerVerifier internal constructor(private val assetLinks: AssetLinksFetcher) {
    constructor() : this(HttpsAssetLinksFetcher())

    fun verify(
        callingAppInfo: CallingAppInfo,
        rpId: String,
        challenge: String,
        type: String,
        suppliedClientDataHash: ByteArray?,
    ): ClientData = verify(object : CallerIdentity {
        override val packageName = callingAppInfo.packageName
        override fun origin(): String? = verifiedOrigin(callingAppInfo)
        override fun currentSigners(): List<ByteArray> =
            callingAppInfo.signingInfo.apkContentsSigners.map { it.toByteArray() }
    }, rpId, challenge, type, suppliedClientDataHash)

    internal fun verify(
        caller: CallerIdentity,
        rpId: String,
        challenge: String,
        type: String,
        suppliedClientDataHash: ByteArray?,
    ): ClientData = try {
        validateRpId(rpId)
        decodeBase64Url(challenge, 1, 4096)
        if (type != "webauthn.create" && type != "webauthn.get") denyCaller()
        // A failed delegated-origin check must never become a native-app request.
        val delegatedOrigin = caller.origin()
        if (delegatedOrigin != null) {
            if (caller.packageName != CHROME_PACKAGE || delegatedOrigin.isEmpty() || suppliedClientDataHash?.size != 32) denyCaller()
            ClientData("{}".toByteArray(Charsets.UTF_8), suppliedClientDataHash.copyOf(), true)
        } else {
            if (suppliedClientDataHash != null) denyCaller()
            val signers = caller.currentSigners()
            if (signers.size != 1 || signers[0].isEmpty()) denyCaller()
            val certificateHash = digest(signers.single())
            val fingerprint = certificateHash.joinToString(":") { "%02X".format(it.toInt() and 255) }
            val document = assetLinks.fetch(rpId)
            if (!matchesAssetLinks(document, caller.packageName, fingerprint)) denyCaller()
            val origin = "android:apk-key-hash:" + Base64.getUrlEncoder().withoutPadding().encodeToString(certificateHash)
            val json = JsonObject().apply {
                addProperty("type", type)
                addProperty("challenge", challenge)
                addProperty("origin", origin)
                addProperty("crossOrigin", false)
            }.toString().toByteArray(Charsets.UTF_8)
            ClientData(json, digest(json), false)
        }
    } catch (failure: CallerVerificationException) {
        throw failure
    } catch (_: Exception) {
        denyCaller()
    }

    companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
        const val CHROME_FINGERPRINT = "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
        const val privilegedAllowlist = """{"apps":[{"type":"android","info":{"package_name":"com.android.chrome","signatures":[{"build":"release","cert_fingerprint_sha256":"F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"}]}}]}"""

        fun verifiedOrigin(callingAppInfo: CallingAppInfo): String? = verifiedOrigin(
            callingAppInfo.packageName,
            { callingAppInfo.getOrigin(privilegedAllowlist) },
            { callingAppInfo.signingInfo.apkContentsSigners.map { it.toByteArray() } },
        )

        internal fun verifiedOrigin(
            packageName: String,
            sdkOrigin: () -> String?,
            currentSigners: () -> List<ByteArray>,
        ): String? = try {
            sdkOrigin()?.also {
                val signers = currentSigners()
                if (packageName != CHROME_PACKAGE || it.isEmpty() || signers.isEmpty() || signers.any { signer ->
                    digest(signer).joinToString(":") { octet -> "%02X".format(octet.toInt() and 255) } != CHROME_FINGERPRINT
                }) denyCaller()
            }
        } catch (_: Exception) {
            denyCaller()
        }

        internal fun matchesAssetLinks(document: ByteArray, packageName: String, fingerprint: String): Boolean {
            if (document.size > StrictJson.MAX_BYTES) return false
            return try {
                val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(document)).toString()
                val statements = StrictJson.parse(json).arrayValue()
                if (statements.size() > 256) return false
                statements.any { value ->
                    if (!value.isJsonObject) return@any false
                    val statement = value.asJsonObject
                    if (statement.has("include")) return@any false
                    val relations = statement.get("relation")
                    val target = statement.get("target")
                    if (relations?.isJsonArray != true || target?.isJsonObject != true) return@any false
                    val app = target.asJsonObject
                    val fingerprints = app.get("sha256_cert_fingerprints")
                    relations.asJsonArray.any { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "delegate_permission/common.handle_all_urls" } &&
                        app.get("namespace").isStringEqual("android_app") &&
                        app.get("package_name").isStringEqual(packageName) &&
                        fingerprints?.isJsonArray == true && fingerprints.asJsonArray.any {
                            it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.equals(fingerprint, ignoreCase = true)
                        }
                }
            } catch (_: Exception) { false }
        }
    }
}

internal interface CallerIdentity {
    val packageName: String
    fun origin(): String?
    fun currentSigners(): List<ByteArray>
}

internal fun interface AssetLinksFetcher {
    fun fetch(rpId: String): ByteArray
}

internal class HttpsAssetLinksFetcher internal constructor(private val calls: Call.Factory) : AssetLinksFetcher {
    constructor() : this(secureClient())

    companion object {
        internal fun secureClient(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    }

    override fun fetch(rpId: String): ByteArray {
        validateRpId(rpId)
        val request = Request.Builder().url("https://$rpId/.well-known/assetlinks.json").get().build()
        return calls.newCall(request).execute().use { response ->
            if (response.code != 200) denyCaller()
            val body = response.body ?: denyCaller()
            val contentType = body.contentType()
            if (contentType?.type != "application" || contentType.subtype != "json" ||
                body.contentLength() > StrictJson.MAX_BYTES) denyCaller()
            val source = body.source()
            if (source.request(StrictJson.MAX_BYTES.toLong() + 1)) denyCaller()
            source.readByteArray()
        }
    }
}

private fun com.google.gson.JsonElement?.isStringEqual(expected: String): Boolean =
    this?.isJsonPrimitive == true && asJsonPrimitive.isString && asString == expected
private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun denyCaller(): Nothing = throw CallerVerificationException()
