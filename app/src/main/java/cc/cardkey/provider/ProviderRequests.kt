package cc.cardkey.provider

import android.content.Intent
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import java.util.Base64
import java.util.UUID

internal data class CallerSnapshot(val packageName: String, val signatures: Set<String>, val origin: String?) {
    companion object {
        fun from(info: CallingAppInfo): CallerSnapshot {
            val signatures = info.signingInfo.apkContentsSigners?.map {
                Base64.getEncoder().encodeToString(it.toByteArray())
            }?.toSet().orEmpty()
            require(signatures.isNotEmpty())
            return CallerSnapshot(info.packageName, signatures, CallerVerifier.verifiedOrigin(info))
        }
    }
}

internal enum class ProviderRequestKind { CREATE, GET }
internal data class OfferedRequest(
    val kind: ProviderRequestKind,
    val json: String,
    val hash: ByteArray?,
    val caller: CallerSnapshot,
    val optionId: String? = null,
)

internal class RequestRegistry(private val now: () -> Long = System::nanoTime) {
    private data class Entry(val request: OfferedRequest, val expires: Long)
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized fun offer(request: OfferedRequest): String {
        entries.entries.removeAll { it.value.expires <= now() }
        check(entries.size < 64)
        return UUID.randomUUID().toString().also {
            entries[it] = Entry(request.copy(hash = request.hash?.copyOf()), now() + 300_000_000_000L)
        }
    }

    @Synchronized fun claim(id: String): OfferedRequest {
        val entry = entries.remove(id) ?: throw IllegalArgumentException()
        require(entry.expires > now())
        return entry.request
    }

    @Synchronized fun discard(id: String) { entries.remove(id) }
    @Synchronized fun clear(packageName: String) { entries.entries.removeAll { it.value.request.caller.packageName == packageName } }
}

internal object ProviderRequests {
    val registry = RequestRegistry()
    const val ACTION_CREATE = "cc.cardkey.provider.CREATE"
    const val ACTION_GET = "cc.cardkey.provider.GET"

    fun extract(intent: Intent): ExtractedRequest {
        val uri = requireNotNull(intent.data)
        require(uri.scheme == "cardkey-provider" && uri.host == "request" && uri.pathSegments.size == 1)
        val offered = registry.claim(uri.pathSegments.single())
        val expectedAction = if (offered.kind == ProviderRequestKind.CREATE) ACTION_CREATE else ACTION_GET
        require(intent.action == expectedAction)
        val json: String
        val hash: ByteArray?
        val caller: CallingAppInfo
        if (offered.kind == ProviderRequestKind.CREATE) {
            val request = requireNotNull(PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent))
            val create = request.callingRequest as? CreatePublicKeyCredentialRequest ?: throw IllegalArgumentException()
            json = create.requestJson
            hash = create.clientDataHash
            caller = request.callingAppInfo
        } else {
            val request = requireNotNull(PendingIntentHandler.retrieveBeginGetCredentialRequest(intent))
            caller = requireNotNull(request.callingAppInfo)
            val options = request.beginGetCredentialOptions
            require(options.size in 1..16 && options.map { it.id }.distinct().size == options.size)
            val selected = options.singleOrNull { it.id == offered.optionId } as? BeginGetPublicKeyCredentialOption
                ?: throw IllegalArgumentException()
            json = selected.requestJson
            hash = selected.clientDataHash
        }
        require(json == offered.json && sameHash(hash, offered.hash) && CallerSnapshot.from(caller) == offered.caller)
        return ExtractedRequest(offered.kind, json, hash, caller)
    }

    private fun sameHash(first: ByteArray?, second: ByteArray?): Boolean =
        if (first == null || second == null) first == null && second == null else first.contentEquals(second)
}

internal data class ExtractedRequest(
    val kind: ProviderRequestKind,
    val json: String,
    val hash: ByteArray?,
    val caller: CallingAppInfo,
)
