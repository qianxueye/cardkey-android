package cc.cardkey.ui

import android.app.Application
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.cardkey.protocol.*
import cc.cardkey.enrollment.*
import cc.cardkey.transport.*
import cc.cardkey.protocol.piv.PivClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

data class ManagerState(
    val readers: List<UsbReaderDescriptor> = emptyList(),
    val selectedReader: UsbReaderDescriptor? = null,
    val nfc: Boolean = true,
    val busy: Boolean = false,
    val awaitingNfc: Boolean = false,
    val result: OperationResult<ManagerReport>? = null,
    val lastRetry: Pair<String, String>? = null,
    val requiresInspection: Boolean = false,
    val operation: ManagerOperation? = null,
    val permissionPending: Boolean = false,
    val resultWire: CardWire = CardWire.APDU,
    val userPresenceRequired: Boolean = false,
    val ctap2Reader: String? = null,
) {
    val supportsPiv get() = nfc || selectedReader?.wire != CardWire.CTAP
    val supportsFidoReset get() = !nfc && selectedReader?.wire == CardWire.CTAP && ctap2Reader == selectedReader.routeKey
    val presenceMessage get() = ConfirmationPolicy.presenceMessage(userPresenceRequired, busy, resultWire)
}

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val usb = application.getSystemService(UsbManager::class.java)
    private val mutable = MutableStateFlow(ManagerState())
    val state = mutable.asStateFlow()
    val enrollment = EnrollmentWorkflow()
    private val networkCall = AtomicReference<okhttp3.Call?>(null)
    private val operations = ManagerOperations { cancelled }
    private var pending: OperationInput? = null
    private var inProgress = false
    @Volatile private var cancelled = false
    private var presenceBlockedReader: String? = null
    private val currentChannel = AtomicReference<CancellableCardChannel?>(null)
    private var retryApproval: CompletableDeferred<Boolean>? = null
    private var presenceWatch: Job? = null
    private var operationRoute = "nfc"
    private var operationWire = CardWire.APDU
    private var recoveryRoute: String? = null
    private var recoveryWire: CardWire? = null
    private var presenceGeneration = 0L
    private val presenceLock = Any()

    fun refreshReaders() {
        val readers = UsbAuthenticatorReader.discover(usb)
        val selected = mutable.value.selectedReader?.let { prior -> readers.firstOrNull {
            it.routeKey == prior.routeKey
        } }
        if (mutable.value.selectedReader != null && selected == null) { cancel(); enrollment.retire() }
        if (presenceBlockedReader != null && readers.none { it.routeKey == presenceBlockedReader }) {
            CardOperationGate.observedRemoval(requireNotNull(presenceBlockedReader))
            presenceBlockedReader = null
        }
        if (presenceBlockedReader == null) presenceBlockedReader = readers.firstOrNull {
            it.wire == CardWire.APDU && CardOperationGate.isRemovalRequired(it.routeKey)
        }?.routeKey
        mutable.value = mutable.value.copy(readers = readers, selectedReader = selected)
        if (presenceBlockedReader != null && presenceWatch?.isActive != true)
            readers.firstOrNull { it.wire == CardWire.APDU && it.routeKey == presenceBlockedReader }?.let(::observeCardRemoval)
    }

    fun selectReader(reader: UsbReaderDescriptor?) {
        if (mutable.value.busy) return
        retireResult()
        enrollment.retire()
        operations.clearSession()
        presenceGeneration++
        mutable.value = mutable.value.copy(userPresenceRequired = false, ctap2Reader = null, selectedReader = reader, nfc = reader == null, result = null)
    }

    fun enrollmentSettings(address: String, cn: String) {
        if (!mutable.value.busy) enrollment.settings(address, cn)
    }
    fun enrollmentRoot(request: Pair<Long, Long>, bytes: ByteArray) {
        if (!mutable.value.busy) runCatching { enrollment.acceptRoot(request, bytes) }.onFailure {
            mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) }
        }
    }
    fun submitEnrollment(input: OperationInput) {
        if (mutable.value.busy || mutable.value.requiresInspection || mutable.value.nfc ||
            mutable.value.selectedReader?.wire != CardWire.APDU) { input.close(); return }
        try {
            when (input.operation) {
                ManagerOperation.PIV_ENROLL_CSR -> {
                    require(input.text == enrollment.state.value.cn)
                    enrollment.startCsr()
                }
                ManagerOperation.PIV_ENROLL_IMPORT -> {
                    require(input.enrollmentImport === null)
                    val request = enrollment.importRequest()
                    submit(OperationInput(input.operation, adminKey = input.adminKey.copyOf(),
                        managementAlgorithm = input.managementAlgorithm, enrollmentImport = request))
                    input.close()
                    return
                }
                else -> throw IllegalArgumentException()
            }
            submit(input)
        } catch (_: Exception) {
            input.close()
            mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) }
        }
    }
    fun abandonEnrollment() { if (!mutable.value.busy) enrollment.retire() }

    fun issueEnrollment(token: CharArray) {
        val reader = mutable.value.selectedReader
        if (mutable.value.busy || mutable.value.requiresInspection || mutable.value.nfc ||
            reader?.wire != CardWire.APDU || !UsbAuthenticatorReader.isCurrent(usb, reader)) { token.fill('\u0000'); return }
        try {
            val ca = enrollment.ca()
            val (owner, csr) = enrollment.startIssuance(String(token))
            val call = ca.call(csr, String(token))
            token.fill('\u0000')
            operations.clearSession()
            retireResult()
            networkCall.set(call)
            mutable.update { it.copy(busy = true, result = null, operation = null) }
            viewModelScope.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching { ca.execute(call, csr) } }
                if (networkCall.compareAndSet(call, null) && owner == enrollment.generation) {
                    val stage = outcome.fold({ EnrollmentStage.VERIFIED }, { error ->
                        when ((error as? EnrollmentException)?.failure) {
                            IssuanceFailure.REJECTED -> EnrollmentStage.REJECTED
                            IssuanceFailure.INVALID -> EnrollmentStage.INVALID
                            else -> EnrollmentStage.UNKNOWN
                        }
                    })
                    enrollment.settleIssuance(owner, outcome.getOrNull(), stage)
                    mutable.update { it.copy(busy = false) }
                }
            }
        } catch (_: Exception) {
            if (enrollment.state.value.stage == EnrollmentStage.ISSUING) enrollment.pause()
            mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) }
        } finally { token.fill('\u0000') }
    }

    fun submit(input: OperationInput) {
        if (mutable.value.busy) { input.close(); return }
        if (mutable.value.requiresInspection && !ConfirmationPolicy.canInspect(input.operation)) {
            input.close(); return
        }
        cancelled = false
        operationRoute = mutable.value.selectedReader?.routeKey ?: "nfc"
        operationWire = mutable.value.selectedReader?.wire ?: CardWire.APDU
        retireResult()
        mutable.value = mutable.value.copy(busy = true, result = null, operation = input.operation, resultWire = operationWire)
        if ((input.operation.piv && !mutable.value.supportsPiv) ||
            (input.operation == ManagerOperation.FIDO_RESET && !mutable.value.supportsFidoReset)) {
            input.close()
            finish(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED))
            return
        }
        if (mutable.value.nfc) {
            pending = input
            mutable.value = mutable.value.copy(awaitingNfc = true)
            refreshNfc()
        } else {
            val reader = mutable.value.selectedReader
            if (reader == null || !usb.hasPermission(reader.device)) {
                input.close()
                finish(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE))
                return
            }
            if (reader.wire == CardWire.APDU && (reader.routeKey == presenceBlockedReader || CardOperationGate.isRemovalRequired(reader.routeKey))) {
                input.close()
                finish(OperationResult.Failed(FailureCode.PHYSICAL_ACTION_REQUIRED, Effect.NONE))
                return
            }
            run(input) { UsbAuthenticatorReader.connect(usb, reader, allowInitialPowerOn = true) }
        }
    }

    fun refreshNfc() {
        if (!mutable.value.awaitingNfc) return
        val adapter = NfcAdapter.getDefaultAdapter(getApplication())
        val failure = when {
            adapter == null -> FailureCode.NFC_UNAVAILABLE
            !adapter.isEnabled -> FailureCode.NFC_DISABLED
            else -> return
        }
        pending?.close()
        pending = null
        finish(OperationResult.Failed(failure, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED))
    }

    fun onTag(tag: Tag) {
        viewModelScope.launch {
            val input = pending ?: return@launch
            pending = null
            mutable.value = mutable.value.copy(awaitingNfc = false)
            run(input) { NfcCardChannel.connect(tag) }
        }
    }

    private fun run(input: OperationInput, connect: () -> CardChannel) {
        inProgress = true
        val generation = ++presenceGeneration
        val enrollmentOwner = enrollment.generation
        val reader = mutable.value.selectedReader
        viewModelScope.launch {
            var writeAccepted = false
            var negotiatedCtap2: Boolean? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    if (cancelled) OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE)
                    else {
                        val lease = CardOperationGate.tryAcquire()
                            ?: return@withContext OperationResult.Failed(FailureCode.BUSY, Effect.NONE)
                        lease.use {
                            val channel = CancellableCardChannel(connect()) { cancelled }
                            currentChannel.set(channel)
                            try {
                                negotiatedCtap2 = channel.wire == CardWire.CTAP && channel.supportsCtap2
                                channel.setUserPresenceListener { required ->
                                    viewModelScope.launch(Dispatchers.Main) {
                                        synchronized(presenceLock) {
                                            if (generation == presenceGeneration && currentChannel.get() === channel && !cancelled) {
                                                val sameRoute = mutable.value.selectedReader?.routeKey == reader?.routeKey &&
                                                    (reader == null || UsbAuthenticatorReader.isCurrent(usb, reader))
                                                mutable.value = mutable.value.copy(userPresenceRequired = required && sameRoute)
                                            }
                                        }
                                    }
                                }
                                if (cancelled) {
                                    channel.close()
                                    OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                                } else CardSession(channel).use sessionUse@ { session ->
                                    if (input.operation.piv && session.wire == CardWire.CTAP)
                                        return@sessionUse OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                                    if (ConfirmationPolicy.pivVerificationReference(input.operation) != null) {
                                        session.requireContact()
                                        val before = PivClient(session).readInfo()
                                        val warning = ConfirmationPolicy.lastRetry(input.operation, before)
                                        if (warning != null) {
                                            val approval = CompletableDeferred<Boolean>()
                                            withContext(Dispatchers.Main) {
                                                retryApproval = approval
                                                mutable.value = mutable.value.copy(lastRetry = warning)
                                                if (cancelled) approval.complete(false)
                                            }
                                            val approved = withTimeoutOrNull(120_000) { approval.await() } == true
                                            withContext(Dispatchers.Main) {
                                                retryApproval = null
                                                mutable.value = mutable.value.copy(lastRetry = null)
                                            }
                                            if (!approved || cancelled) return@sessionUse OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE)
                                            val current = PivClient(session).readInfo()
                                            if (!ConfirmationPolicy.retryFactsUnchanged(before, current))
                                                return@sessionUse OperationResult.Failed(FailureCode.RECOVERY_REQUIRED, Effect.NONE)
                                        }
                                    }
                                    if (cancelled) OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE)
                                    else session.run { operations.execute(it, input) }.also { outcome ->
                                        writeAccepted = session.hasAcceptedWrite
                                        if (reader?.wire == CardWire.APDU && outcome is OperationResult.Failed && outcome.code == FailureCode.PHYSICAL_ACTION_REQUIRED)
                                            CardOperationGate.requireRemoval(reader.routeKey)
                                    }
                                }
                            } finally {
                                channel.setUserPresenceListener(null)
                                synchronized(presenceLock) {
                                    if (currentChannel.compareAndSet(channel, null))
                                        mutable.update { it.copy(userPresenceRequired = false) }
                                }
                            }
                        }
                    }
                } catch (e: CardFailure) {
                    OperationResult.Failed(e.code, Effect.NONE, e.status)
                } catch (e: TransportException) {
                    OperationResult.Failed(if (e.cancelled) FailureCode.CANCELLED else FailureCode.TRANSPORT_FAILURE, Effect.NONE, dispatch = e.dispatch)
                } catch (_: SecurityException) {
                    OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE)
                } finally { input.close() }
            }
            inProgress = false
            if (!cancelled && reader != null && mutable.value.selectedReader?.routeKey == reader.routeKey &&
                UsbAuthenticatorReader.isCurrent(usb, reader) && negotiatedCtap2 != null) {
                mutable.update { it.copy(ctap2Reader = reader.routeKey.takeIf { negotiatedCtap2 == true }) }
            }
            if (reader?.wire == CardWire.APDU && result is OperationResult.Failed && result.code == FailureCode.PHYSICAL_ACTION_REQUIRED) {
                presenceBlockedReader = reader.routeKey
                presenceBlockedReader?.let(CardOperationGate::requireRemoval)
                observeCardRemoval(reader)
            }
            val retired = cancelled || (reader != null && (mutable.value.selectedReader?.routeKey != reader.routeKey ||
                !UsbAuthenticatorReader.isCurrent(usb, reader)))
            if (retired) {
                mutable.update { it.copy(ctap2Reader = null) }
                operations.clearSession()
                (result as? OperationResult.Completed)?.value?.let { report ->
                    report.secrets?.close()
                    if (report.protectedExport) report.export?.fill(0)
                }
            }
            if (!retired && result is OperationResult.Completed) {
                result.value.enrollmentCsr?.let { enrollment.settleCsr(enrollmentOwner, it) }
                if (input.operation == ManagerOperation.PIV_ENROLL_IMPORT) enrollment.imported(enrollmentOwner)
            }
            finish(if (retired && result is OperationResult.Completed)
                OperationResult.Failed(FailureCode.CANCELLED, result.effect, writeAccepted = writeAccepted) else result)
        }
    }

    private fun retireResult(next: OperationResult<ManagerReport>? = null) {
        val previous = (mutable.value.result as? OperationResult.Completed)?.value ?: return
        val incoming = (next as? OperationResult.Completed)?.value
        if (previous.secrets !== incoming?.secrets) previous.secrets?.close()
        if (previous.protectedExport && previous.export !== incoming?.export) previous.export?.fill(0)
    }

    private fun finish(result: OperationResult<ManagerReport>) {
        presenceGeneration++
        val previous = mutable.value
        if (result is OperationResult.Failed && (result.effect == Effect.UNKNOWN || result.writeAccepted)) {
            if (!previous.requiresInspection) {
                recoveryRoute = operationRoute
                recoveryWire = operationWire
            } else {
                if (recoveryRoute != operationRoute) recoveryRoute = null
                if (operationWire == CardWire.CTAP) recoveryWire = CardWire.CTAP
            }
        }
        val requiresInspection = when {
            result is OperationResult.Failed && (result.effect == Effect.UNKNOWN || result.writeAccepted) -> true
            result is OperationResult.Completed && recoveryWire != CardWire.CTAP &&
                recoveryRoute == operationRoute && previous.operation?.let(ConfirmationPolicy::canInspect) == true -> false
            else -> previous.requiresInspection
        }
        if (!requiresInspection) { recoveryRoute = null; recoveryWire = null }
        retireResult(result)
        mutable.value = previous.copy(busy = false, awaitingNfc = false, lastRetry = null, userPresenceRequired = false,
            result = result, requiresInspection = requiresInspection)
    }

    fun approveLastRetry(approved: Boolean) { retryApproval?.complete(approved) }
    fun permissionPending(pending: Boolean) { mutable.value = mutable.value.copy(permissionPending = pending) }

    private fun observeCardRemoval(reader: UsbReaderDescriptor) {
        if (reader.wire != CardWire.APDU) return
        presenceWatch?.cancel()
        presenceWatch = viewModelScope.launch {
            while (presenceBlockedReader == reader.routeKey) {
                val absent = withContext(Dispatchers.IO) {
                    CardOperationGate.tryAcquire()?.use { UsbCcidReader.isCardAbsent(usb, reader) } == true
                }
                if (absent) { CardOperationGate.observedRemoval(reader.routeKey); presenceBlockedReader = null; break }
                delay(750)
            }
        }
    }

    fun cancel() {
        enrollment.pause()
        networkCall.getAndSet(null)?.let { call ->
            call.cancel()
            mutable.update { it.copy(busy = false) }
        }
        cancelled = true
        presenceGeneration++
        operations.clearSession()
        retireResult()
        mutable.value = mutable.value.copy(result = null, userPresenceRequired = false)
        retryApproval?.complete(false)
        presenceWatch?.cancel()
        pending?.close()
        pending = null
        currentChannel.get()?.let { channel -> viewModelScope.launch(Dispatchers.IO) {
            channel.setUserPresenceListener(null)
            channel.cancel()
        } }
        if (!inProgress && mutable.value.busy) finish(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE))
    }

    override fun onCleared() { cancel(); super.onCleared() }
}
