package cc.cardkey.provider

import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.OperationResult
import cc.cardkey.protocol.fido.*
import kotlinx.coroutines.runBlocking

internal fun ProviderOperationRunner.executeAndConfirm(
    create: ParsedCreate?, get: ParsedGet?, clientData: ClientData, pin: CharArray?, readerId: String? = null,
    claim: RequestRegistry.Claim? = null, onUserPresenceRequired: ((Boolean) -> Unit)? = null,
    connect: () -> CardChannel,
): OperationResult<ProviderOutput> {
    val owned = claim ?: RequestRegistry().let { registry -> registry.claim(registry.offer(OfferedRequest(
        if (create != null) ProviderRequestKind.CREATE else ProviderRequestKind.GET, "{}", clientData.hash,
        CallerSnapshot("synthetic.provider.test", setOf("synthetic-certificate"), null), "fixture"))) }
    return try {
        runBlocking {
            recognizeAndExecute(create, get, clientData, readerId, owned, onUserPresenceRequired,
                onRecognized = { observed ->
                    check(confirm(if (observed.pinRequired) requireNotNull(pin) else null))
                }, connect = connect)
        }
    } finally { pin?.fill('\u0000'); if (claim == null) owned.finish(cancelled = false) }
}

internal fun FidoClientTest.PinServer.providerResponse(map: Cbor.Map, permission: Int, rp: String?): ByteArray =
    if (map[2].number() == 1L) success(cborMap(3 to 8.cbor(), 4 to false.cbor())) else respond(map, permission, rp)
