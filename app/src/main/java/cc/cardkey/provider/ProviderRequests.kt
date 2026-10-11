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
    private enum class State { PENDING, ACTIVE, DISPATCHED }
    private data class Entry(val request: OfferedRequest, val expires: Long,
        var state: State = State.PENDING, var claim: Claim? = null)
    internal class Claim internal constructor(val id: String, val request: OfferedRequest,
        private val registry: RequestRegistry) {
        fun reserveTransport(): Boolean = registry.reserve(this)
        fun finish(cancelled: Boolean) = registry.finish(this, cancelled)
    }
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized fun offer(request: OfferedRequest): String {
        entries.entries.removeAll { it.value.state == State.PENDING && it.value.expires <= now() }
        if (entries.size >= 64) {
            val oldest = entries.entries.firstOrNull { it.value.state == State.PENDING }
            check(oldest != null)
            entries.remove(oldest.key)
        }
        return UUID.randomUUID().toString().also {
            entries[it] = Entry(request.copy(hash = request.hash?.copyOf()), now() + 300_000_000_000L)
        }
    }

    @Synchronized fun inspect(id: String): OfferedRequest {
        val entry = entries[id] ?: throw IllegalArgumentException()
        require(entry.state == State.PENDING && entry.expires > now())
        return entry.request.copy(hash = entry.request.hash?.copyOf())
    }

    @Synchronized fun claim(id: String): Claim {
        val request = inspect(id)
        return Claim(id, request, this).also {
            entries.getValue(id).apply { state = State.ACTIVE; claim = it }
        }
    }

    @Synchronized private fun reserve(claim: Claim): Boolean {
        val entry = entries[claim.id] ?: return false
        if (entry.claim !== claim || entry.state != State.ACTIVE) return false
        entry.state = State.DISPATCHED
        return true
    }

    @Synchronized private fun finish(claim: Claim, cancelled: Boolean): Boolean {
        val entry = entries[claim.id] ?: return false
        if (entry.claim !== claim) return false
        if (cancelled && entry.state == State.ACTIVE && entry.expires > now()) {
            entry.state = State.PENDING
            entry.claim = null
            return true
        }
        entries.remove(claim.id)
        return false
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
        val id = uri.pathSegments.single()
        val offered = registry.inspect(id)
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
        return ExtractedRequest(offered.kind, json, hash, caller, registry.claim(id))
    }

    private fun sameHash(first: ByteArray?, second: ByteArray?): Boolean =
        if (first == null || second == null) first == null && second == null else first.contentEquals(second)
}

internal data class ExtractedRequest(
    val kind: ProviderRequestKind,
    val json: String,
    val hash: ByteArray?,
    val caller: CallingAppInfo,
    val claim: RequestRegistry.Claim,
)
