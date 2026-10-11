package cc.cardkey.provider

import cc.cardkey.protocol.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed interface ProviderOutput {
    data class Created(val credential: CreatedCredential) : ProviderOutput
    data class Assertions(val values: List<ProvidedAssertion>) : ProviderOutput
}

internal class ProviderOperationRunner {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val inputLock = Any()
    private var ownedPin: CharArray? = null
    private var ownedCreateInputs: CreateExtensionInputs? = null
    private var ownedGetInputs: GetExtensionInputs? = null
    private val channel = AtomicReference<CardChannel?>(null)

    fun execute(
        create: ParsedCreate?,
        get: ParsedGet?,
        clientData: ClientData,
        pin: CharArray?,
        readerId: String? = null,
        claim: RequestRegistry.Claim? = null,
        connect: () -> CardChannel,
    ): OperationResult<ProviderOutput> {
        synchronized(inputLock) {
            if (!started.compareAndSet(false, true)) {
                if (pin !== ownedPin) pin?.fill('\u0000')
                if (create?.extensions !== ownedCreateInputs) create?.extensions?.close()
                if (get?.extensions !== ownedGetInputs) get?.extensions?.close()
                return OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            }
            ownedPin = pin
            ownedCreateInputs = create?.extensions
            ownedGetInputs = get?.extensions
        }
        try {
            if (cancelled.get()) return OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            val lease = CardOperationGate.tryAcquire()
                ?: return OperationResult.Failed(FailureCode.BUSY, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            return lease.use {
                if (cancelled.get()) return@use OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                if ((create == null) == (get == null))
                    return@use OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                try { validateClientData(clientData) } catch (_: IllegalArgumentException) {
                    return@use OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                }
                // Connection can power or initialize the card, so reserve before entering transport code.
                if (claim != null && !claim.reserveTransport())
                    return@use OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                val connected = connect()
                channel.set(connected)
                val guarded = object : CardChannel by connected {
                    override fun transmit(command: ByteArray): ByteArray {
                        if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                        return connected.transmit(command)
                    }
                    override fun exchangeCtap2(command: ByteArray): ByteArray {
                        if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                        return connected.exchangeCtap2(command)
                    }
                    override fun exchangeCtap1(command: ByteArray): ByteArray {
                        if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                        return connected.exchangeCtap1(command)
                    }
                }
                val result = CardSession(guarded).use { session ->
                    session.run {
                        if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                        val result = if (create != null) ProviderOutput.Created(CardCredentialOperation.create(it, create, clientData, pin))
                            else ProviderOutput.Assertions(CardCredentialOperation.get(it, requireNotNull(get), clientData, pin))
                        if (cancelled.get()) {
                            when (result) {
                                is ProviderOutput.Created -> result.credential.extensions.close()
                                is ProviderOutput.Assertions -> result.values.forEach { it.close() }
                            }
                            throw CardFailure(FailureCode.CANCELLED)
                        }
                        result
                    }
                }
                if (connected.wire == CardWire.APDU && result is OperationResult.Failed && result.code == FailureCode.PHYSICAL_ACTION_REQUIRED && readerId != null)
                    CardOperationGate.requireRemoval(readerId)
                if (cancelled.get() && result is OperationResult.Completed) {
                    val writeAccepted = when (val output = result.value) {
                        is ProviderOutput.Created -> { output.credential.extensions.close(); true }
                        is ProviderOutput.Assertions -> {
                            val written = output.values.any { it.extensions.largeBlobWritten == true }
                            output.values.forEach { it.close() }
                            written
                        }
                    }
                    OperationResult.Failed(FailureCode.CANCELLED, result.effect, writeAccepted = writeAccepted)
                } else result
            }
        } catch (error: CardFailure) {
            return OperationResult.Failed(error.code, Effect.NONE, error.status, dispatch = Dispatch.NOT_DISPATCHED)
        } catch (error: TransportException) {
            return OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE, dispatch = error.dispatch)
        } catch (_: SecurityException) {
            return OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
        } finally {
            channel.set(null)
            synchronized(inputLock) {
                pin?.fill('\u0000')
                create?.extensions?.close()
                get?.extensions?.close()
                ownedPin = null
                ownedCreateInputs = null
                ownedGetInputs = null
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        channel.get()?.let { runCatching { it.cancel() } }
    }
}
