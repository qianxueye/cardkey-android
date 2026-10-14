package cc.cardkey.tls

import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.PivClient
import cc.cardkey.protocol.piv.PivInfo
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class PivTlsOutcome { QUALIFIED, FAILED, CANCELLED }
data class PivTlsResult(val outcome: PivTlsOutcome, val card: OperationResult<Unit>)

class PivTlsAttempt(
    private val identity: PivTlsIdentity,
    private val endpoint: PivTlsEndpoint,
    pin: CharArray,
    private val current: () -> Boolean,
    private val connect: () -> CardChannel,
) : Closeable {
    private val lock = Any()
    private var secret: CharArray? = pin.copyOf().also { pin.fill('\u0000') }
    private val retired = AtomicBoolean()
    private val used = AtomicBoolean()
    private val channel = AtomicReference<CancellableCardChannel?>()
    private val connection = PivTlsConnection()
    private var facts: PivInfo? = null
    @Volatile private var cardResult: OperationResult<Unit> = OperationResult.Completed(Unit, Effect.NONE)

    private fun cancelled(): Boolean {
        if (!current()) close()
        return retired.get()
    }

    fun preflight(): OperationResult<PivInfo> {
        check(facts == null && !used.get())
        val result = card { client ->
            identity.validate()
            client.checkTlsIdentity(identity.leaf, identity.spki).also {
                if (it.pin.remaining == 0) throw CardFailure(FailureCode.PIN_BLOCKED)
            }
        }
        if (result is OperationResult.Completed) facts = result.value
        else close()
        return result
    }

    fun execute(lastRetryApproved: Boolean): PivTlsResult {
        check(used.compareAndSet(false, true))
        val expected = requireNotNull(facts)
        try {
            if (cancelled()) throw CardFailure(FailureCode.CANCELLED)
            if (expected.pin.remaining == 1 && expected.puk.remaining == 0 && !lastRetryApproved) {
                cardResult = OperationResult.Failed(FailureCode.RECOVERY_REQUIRED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                throw CardFailure(FailureCode.RECOVERY_REQUIRED)
            }
            connection.execute(identity, endpoint, { digest ->
                val result = card { client ->
                    // Fresh public facts are checked before taking the only PIN copy.
                    val fresh = client.checkTlsIdentity(identity.leaf, identity.spki)
                    if (fresh != expected) throw CardFailure(FailureCode.RECOVERY_REQUIRED)
                    val ownedPin = synchronized(lock) {
                        if (cancelled()) throw CardFailure(FailureCode.CANCELLED)
                        requireNotNull(secret).also { secret = null }
                    }
                    try { client.signTlsDigest(ownedPin, digest, identity.leaf, identity.spki, expected, lastRetryApproved) }
                    finally { ownedPin.fill('\u0000') }
                }
                cardResult = when (result) {
                    is OperationResult.Completed -> OperationResult.Completed(Unit, result.effect)
                    is OperationResult.Failed -> result
                }
                when (result) {
                    is OperationResult.Completed -> result.value
                    is OperationResult.Failed -> throw IOException("card signature failed")
                }
            }, ::close)
            return PivTlsResult(if (cancelled()) PivTlsOutcome.CANCELLED else PivTlsOutcome.QUALIFIED, cardResult)
        } catch (_: Exception) {
            return PivTlsResult(if (cancelled()) PivTlsOutcome.CANCELLED else PivTlsOutcome.FAILED, cardResult)
        } finally { close() }
    }

    private fun <T> card(operation: (PivClient) -> T): OperationResult<T> {
        if (cancelled()) return OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
        val lease = CardOperationGate.tryAcquire()
            ?: return OperationResult.Failed(FailureCode.BUSY, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
        return lease.use {
            try {
                if (cancelled()) throw CardFailure(FailureCode.CANCELLED)
                val connected = CancellableCardChannel(connect(), ::cancelled)
                channel.set(connected)
                try {
                    if (cancelled()) { connected.cancel(); throw CardFailure(FailureCode.CANCELLED) }
                    CardSession(connected).run { operation(PivClient(it)) }
                } finally { channel.compareAndSet(connected, null); connected.close() }
            } catch (error: CardFailure) {
                OperationResult.Failed(error.code, Effect.NONE, error.status, dispatch = Dispatch.NOT_DISPATCHED)
            } catch (error: TransportException) {
                OperationResult.Failed(if (error.cancelled) FailureCode.CANCELLED else FailureCode.TRANSPORT_FAILURE,
                    Effect.NONE, dispatch = error.dispatch)
            } catch (_: SecurityException) {
                OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            }
        }
    }

    override fun close() {
        retired.set(true)
        synchronized(lock) { secret?.fill('\u0000'); secret = null }
        connection.close()
        channel.getAndSet(null)?.let { runCatching { it.cancel() } }
    }
}
