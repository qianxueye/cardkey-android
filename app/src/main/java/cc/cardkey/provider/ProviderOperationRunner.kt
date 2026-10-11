package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.Assertion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed interface ProviderOutput {
    data class Created(val credential: CreatedCredential) : ProviderOutput
    data class Assertions(val values: List<Assertion>) : ProviderOutput
}

internal class ProviderOperationRunner {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val channel = AtomicReference<CardChannel?>(null)

    fun execute(
        create: ParsedCreate?,
        get: ParsedGet?,
        clientData: ClientData,
        pin: CharArray?,
        readerId: String? = null,
        connect: () -> CardChannel,
    ): OperationResult<ProviderOutput> {
        if (!started.compareAndSet(false, true)) {
            pin?.fill('\u0000')
            return OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
        }
        try {
            if (cancelled.get()) return OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            val lease = CardOperationGate.tryAcquire()
                ?: return OperationResult.Failed(FailureCode.BUSY, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            return lease.use {
                if (cancelled.get()) return@use OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                val connected = connect()
                channel.set(connected)
                val result = CardSession(connected).use { session ->
                    session.run {
                        if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                        val result = if (create != null) ProviderOutput.Created(CardCredentialOperation.create(it, create, clientData, pin))
                            else ProviderOutput.Assertions(CardCredentialOperation.get(it, requireNotNull(get), clientData, pin))
                        if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                        result
                    }
                }
                if (result is OperationResult.Failed && result.code == FailureCode.PHYSICAL_ACTION_REQUIRED && readerId != null)
                    CardOperationGate.requireRemoval(readerId)
                result
            }
        } catch (error: CardFailure) {
            return OperationResult.Failed(error.code, Effect.NONE, error.status, dispatch = Dispatch.NOT_DISPATCHED)
        } catch (error: TransportException) {
            return OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE, dispatch = error.dispatch)
        } catch (_: SecurityException) {
            return OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
        } finally {
            channel.set(null)
            pin?.fill('\u0000')
        }
    }

    fun cancel() {
        cancelled.set(true)
        channel.get()?.let { runCatching { it.close() } }
    }
}
