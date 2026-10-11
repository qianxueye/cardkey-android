package cc.cardkey.ui

import android.app.Application
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.cardkey.protocol.*
import cc.cardkey.transport.*
import cc.cardkey.protocol.piv.PivClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val usb = application.getSystemService(UsbManager::class.java)
    private val mutable = MutableStateFlow(ManagerState())
    val state = mutable.asStateFlow()
    private val operations = ManagerOperations { cancelled }
    private var pending: OperationInput? = null
    private var inProgress = false
    @Volatile private var cancelled = false
    private var presenceBlockedReader: String? = null
    private val currentChannel = AtomicReference<CancellableCardChannel?>(null)
    private var retryApproval: CompletableDeferred<Boolean>? = null
    private var presenceWatch: Job? = null

    fun refreshReaders() {
        val readers = UsbCcidReader.discover(usb)
        val selected = mutable.value.selectedReader?.let { prior -> readers.firstOrNull {
            it.device.deviceName == prior.device.deviceName && it.interfaceId == prior.interfaceId
        } }
        if (mutable.value.selectedReader != null && selected == null) cancel()
        if (presenceBlockedReader != null && readers.none { it.device.deviceName == presenceBlockedReader }) {
            CardOperationGate.observedRemoval(requireNotNull(presenceBlockedReader))
            presenceBlockedReader = null
        }
        if (presenceBlockedReader == null) presenceBlockedReader = readers.firstOrNull {
            CardOperationGate.isRemovalRequired(it.device.deviceName)
        }?.device?.deviceName
        mutable.value = mutable.value.copy(readers = readers, selectedReader = selected)
        if (presenceBlockedReader != null && presenceWatch?.isActive != true)
            readers.firstOrNull { it.device.deviceName == presenceBlockedReader }?.let(::observeCardRemoval)
    }

    fun selectReader(reader: UsbReaderDescriptor?) {
        if (mutable.value.busy) return
        operations.clearSession()
        mutable.value = mutable.value.copy(selectedReader = reader, nfc = reader == null, result = null)
    }

    fun submit(input: OperationInput) {
        if (mutable.value.busy) { input.close(); return }
        if (mutable.value.requiresInspection && !ConfirmationPolicy.canInspect(input.operation)) {
            input.close(); return
        }
        cancelled = false
        mutable.value = mutable.value.copy(busy = true, result = null, operation = input.operation)
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
            if (reader.device.deviceName == presenceBlockedReader || CardOperationGate.isRemovalRequired(reader.device.deviceName)) {
                input.close()
                finish(OperationResult.Failed(FailureCode.PHYSICAL_ACTION_REQUIRED, Effect.NONE))
                return
            }
            run(input) { UsbCcidReader.connect(usb, reader, allowInitialPowerOn = true) }
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
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (cancelled) OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE)
                    else {
                        val lease = CardOperationGate.tryAcquire()
                            ?: return@withContext OperationResult.Failed(FailureCode.BUSY, Effect.NONE)
                        lease.use {
                            val channel = CancellableCardChannel(connect()) { cancelled }
                            currentChannel.set(channel)
                            if (cancelled) {
                                channel.close()
                                OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                            } else CardSession(channel).use sessionUse@ { session ->
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
                                    if (outcome is OperationResult.Failed && outcome.code == FailureCode.PHYSICAL_ACTION_REQUIRED)
                                        mutable.value.selectedReader?.device?.deviceName?.let(CardOperationGate::requireRemoval)
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
                } finally { currentChannel.set(null); input.close() }
            }
            inProgress = false
            if (result is OperationResult.Failed && result.code == FailureCode.PHYSICAL_ACTION_REQUIRED) {
                presenceBlockedReader = mutable.value.selectedReader?.device?.deviceName
                presenceBlockedReader?.let(CardOperationGate::requireRemoval)
                mutable.value.selectedReader?.let(::observeCardRemoval)
            }
            if (cancelled) {
                operations.clearSession()
                (result as? OperationResult.Completed)?.value?.let { report ->
                    report.secrets?.close()
                    if (report.protectedExport) report.export?.fill(0)
                }
            }
            finish(if (cancelled && result is OperationResult.Completed) result.copy(value = result.value.copy(
                secrets = null, export = result.value.export.takeUnless { result.value.protectedExport })) else result)
        }
    }

    private fun finish(result: OperationResult<ManagerReport>) {
        val previous = mutable.value
        val requiresInspection = when {
            result is OperationResult.Failed && (result.effect == Effect.UNKNOWN || result.writeAccepted) -> true
            result is OperationResult.Completed && previous.operation?.let(ConfirmationPolicy::canInspect) == true -> false
            else -> previous.requiresInspection
        }
        mutable.value = previous.copy(busy = false, awaitingNfc = false, lastRetry = null,
            result = result, requiresInspection = requiresInspection)
    }

    fun approveLastRetry(approved: Boolean) { retryApproval?.complete(approved) }
    fun permissionPending(pending: Boolean) { mutable.value = mutable.value.copy(permissionPending = pending) }

    private fun observeCardRemoval(reader: UsbReaderDescriptor) {
        presenceWatch?.cancel()
        presenceWatch = viewModelScope.launch {
            while (presenceBlockedReader == reader.device.deviceName) {
                val absent = withContext(Dispatchers.IO) {
                    CardOperationGate.tryAcquire()?.use { UsbCcidReader.isCardAbsent(usb, reader) } == true
                }
                if (absent) { CardOperationGate.observedRemoval(reader.device.deviceName); presenceBlockedReader = null; break }
                delay(750)
            }
        }
    }

    fun cancel() {
        cancelled = true
        operations.clearSession()
        (mutable.value.result as? OperationResult.Completed)?.value?.export?.takeIf {
            (mutable.value.result as? OperationResult.Completed)?.value?.protectedExport == true
        }?.fill(0)
        mutable.value = mutable.value.copy(result = null)
        retryApproval?.complete(false)
        presenceWatch?.cancel()
        pending?.close()
        pending = null
        currentChannel.get()?.let { channel -> viewModelScope.launch(Dispatchers.IO) { channel.cancel() } }
        if (!inProgress && mutable.value.busy) finish(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE))
    }

    override fun onCleared() { cancel(); super.onCleared() }
}
