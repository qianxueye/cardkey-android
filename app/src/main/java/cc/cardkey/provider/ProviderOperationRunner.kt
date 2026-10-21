package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.FidoClient
import kotlinx.coroutines.CompletableDeferred
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
    private var confirmation: CompletableDeferred<Unit>? = null
    private var recognition: ProviderRecognition? = null
    private var ownedCreateInputs: CreateExtensionInputs? = null
    private var ownedGetInputs: GetExtensionInputs? = null
    private val channel = AtomicReference<CardChannel?>(null)
    private val presenceLock = Any()
    private var presenceCallback: ((Boolean) -> Unit)? = null
    val isActive get() = channel.get() != null && !cancelled.get()

    fun confirm(pin: CharArray?): Boolean = synchronized(inputLock) {
        val waiting = confirmation
        val observed = recognition
        if (cancelled.get() || waiting == null || waiting.isCompleted || observed == null ||
            observed.pinRequired != (pin != null && pin.isNotEmpty())) {
            if (pin !== ownedPin) pin?.fill('\u0000')
            return@synchronized false
        }
        ownedPin = pin
        waiting.complete(Unit)
    }

    suspend fun recognizeAndExecute(
        create: ParsedCreate?,
        get: ParsedGet?,
        clientData: ClientData,
        readerId: String? = null,
        claim: RequestRegistry.Claim,
        onUserPresenceRequired: ((Boolean) -> Unit)? = null,
        onRecognized: (ProviderRecognition) -> Unit,
        connect: () -> CardChannel,
    ): OperationResult<ProviderOutput> {
        synchronized(inputLock) {
            if (!started.compareAndSet(false, true)) {
                if (create?.extensions !== ownedCreateInputs) create?.extensions?.close()
                if (get?.extensions !== ownedGetInputs) get?.extensions?.close()
                return OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            }
            ownedCreateInputs = create?.extensions
            ownedGetInputs = get?.extensions
        }
        try {
            if (cancelled.get()) return OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            val lease = CardOperationGate.tryAcquire()
                ?: return OperationResult.Failed(FailureCode.BUSY, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
            return lease.use {
                try {
                    if (cancelled.get()) return@use OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                    if ((create == null) == (get == null))
                        return@use OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                    try { validateClientData(clientData) } catch (_: IllegalArgumentException) {
                        return@use OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                    }
                    // Connection can power or initialize the card, so reserve before entering transport code.
                    if (!claim.reserveTransport())
                        return@use OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                    val connected = connect()
                    channel.set(connected)
                    synchronized(presenceLock) {
                        if (!cancelled.get()) presenceCallback = onUserPresenceRequired
                    }
                    connected.setUserPresenceListener { required ->
                        synchronized(presenceLock) {
                            if (!cancelled.get() && channel.get() === connected) presenceCallback?.invoke(required)
                        }
                    }
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
                        session.runSuspending {
                            if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                            val client = FidoClient(it)
                            client.select()
                            val info = client.getInfo()
                            val observed = ProviderRecognition.from(info, create, get).let { policy ->
                                if (!policy.pinRequired) policy else {
                                    val retries = client.pinRetries(info)
                                    if (retries.pin == 0) throw CardFailure(FailureCode.PIN_BLOCKED)
                                    if (retries.powerCycleState == true) throw CardFailure(
                                        if (session.wire == CardWire.CTAP) FailureCode.PIN_BLOCKED else FailureCode.PHYSICAL_ACTION_REQUIRED, 0x34)
                                    policy.copy(remainingAttempts = retries.pin)
                                }
                            }
                            run {
                                val waiting = CompletableDeferred<Unit>()
                                synchronized(inputLock) {
                                    if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                                    recognition = observed
                                    confirmation = waiting
                                }
                                onRecognized(observed)
                                waiting.await()
                                synchronized(inputLock) { confirmation = null; recognition = null }
                            }
                            if (cancelled.get()) throw CardFailure(FailureCode.CANCELLED)
                            if (observed.pinRequired) {
                                val retries = client.pinRetries(info)
                                if (retries.pin == 0) throw CardFailure(FailureCode.PIN_BLOCKED)
                                if (retries.powerCycleState == true) throw CardFailure(
                                    if (session.wire == CardWire.CTAP) FailureCode.PIN_BLOCKED else FailureCode.PHYSICAL_ACTION_REQUIRED, 0x34)
                                if (retries.pin != observed.remainingAttempts) throw CardFailure(FailureCode.RECOVERY_REQUIRED)
                            }
                            val secret = synchronized(inputLock) { ownedPin }
                            val result = if (create != null) ProviderOutput.Created(CardCredentialOperation.create(client, info, create, clientData, secret))
                                else ProviderOutput.Assertions(CardCredentialOperation.get(client, info, requireNotNull(get), clientData, secret))
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
                } finally {
                    val retired = synchronized(presenceLock) {
                        channel.getAndSet(null).also {
                            presenceCallback?.invoke(false)
                            presenceCallback = null
                        }
                    }
                    retired?.setUserPresenceListener(null)
                }
            }
        } catch (error: CardFailure) {
            return OperationResult.Failed(error.code, Effect.NONE, error.status, dispatch = Dispatch.NOT_DISPATCHED)
        } catch (error: TransportException) {
            return OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE, dispatch = error.dispatch)
        } catch (_: SecurityException) {
            return OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
        } finally {
            synchronized(inputLock) {
                ownedPin?.fill('\u0000')
                confirmation?.cancel()
                confirmation = null
                recognition = null
                create?.extensions?.close()
                get?.extensions?.close()
                ownedPin = null
                ownedCreateInputs = null
                ownedGetInputs = null
            }
        }
    }

    fun cancel() {
        synchronized(inputLock) {
            cancelled.set(true)
            if (confirmation != null) { ownedPin?.fill('\u0000'); ownedPin = null }
            recognition = null
            confirmation?.complete(Unit)
        }
        synchronized(presenceLock) {
            cancelled.set(true)
            presenceCallback?.invoke(false)
            presenceCallback = null
        }
        channel.get()?.let { connected ->
            connected.setUserPresenceListener(null)
            runCatching { connected.cancel() }
        }
    }
}
