package cc.cardkey.provider

import cc.cardkey.R
import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.*
import java.util.concurrent.atomic.AtomicInteger

class CardKeyProviderService : CredentialProviderService() {
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) return
        val tokens = mutableListOf<String>()
        val response = try {
            val create = request as? BeginCreatePublicKeyCredentialRequest ?: throw IllegalArgumentException()
            RequestParser.parseCreate(create.requestJson)
            val caller = CallerSnapshot.from(requireNotNull(create.callingAppInfo))
            require(if (caller.origin == null) create.clientDataHash == null else create.clientDataHash?.size == 32)
            val entry = CreateEntry(getString(R.string.app_name), pending(OfferedRequest(ProviderRequestKind.CREATE,
                create.requestJson, create.clientDataHash, caller), tokens), isAutoSelectAllowed = false)
            BeginCreateCredentialResponse(listOf(entry))
        } catch (_: Exception) {
            tokens.forEach(ProviderRequests.registry::discard)
            if (!cancellationSignal.isCanceled) callback.onError(CreateCredentialUnknownException())
            return
        }
        if (cancellationSignal.isCanceled) tokens.forEach(ProviderRequests.registry::discard)
        else callback.onResult(response)
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) return
        val tokens = mutableListOf<String>()
        val response = try {
            val caller = CallerSnapshot.from(requireNotNull(request.callingAppInfo))
            val options = request.beginGetCredentialOptions
            require(options.size in 1..16 && options.map { it.id }.distinct().size == options.size)
            val actions = options.filterIsInstance<BeginGetPublicKeyCredentialOption>().map { option ->
                val parsed = RequestParser.parseGet(option.requestJson)
                require(if (caller.origin == null) option.clientDataHash == null else option.clientDataHash?.size == 32)
                Action(getString(R.string.app_name), pending(OfferedRequest(ProviderRequestKind.GET, option.requestJson,
                    option.clientDataHash, caller, option.id), tokens), subtitle = parsed.rpId)
            }
            BeginGetCredentialResponse(actions = actions)
        } catch (_: Exception) {
            tokens.forEach(ProviderRequests.registry::discard)
            if (!cancellationSignal.isCanceled) callback.onError(GetCredentialUnknownException())
            return
        }
        if (cancellationSignal.isCanceled) tokens.forEach(ProviderRequests.registry::discard)
        else callback.onResult(response)
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) return
        ProviderRequests.registry.clear(request.callingAppInfo.packageName)
        callback.onResult(null)
    }

    private fun pending(request: OfferedRequest, tokens: MutableList<String>): PendingIntent {
        val token = ProviderRequests.registry.offer(request)
        tokens += token
        val action = if (request.kind == ProviderRequestKind.CREATE) ProviderRequests.ACTION_CREATE else ProviderRequests.ACTION_GET
        val intent = Intent(this, ProviderActivity::class.java).setAction(action)
            .setData("cardkey-provider://request/$token".toUri())
        return PendingIntent.getActivity(this, requestCodes.incrementAndGet(), intent, PendingIntent.FLAG_MUTABLE)
    }

    companion object { private val requestCodes = AtomicInteger() }
}
