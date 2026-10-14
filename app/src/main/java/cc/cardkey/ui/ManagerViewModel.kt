package cc.cardkey.ui

import android.app.Application
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.cardkey.protocol.*
import cc.cardkey.enrollment.*
import cc.cardkey.tls.*
import cc.cardkey.signing.*
import android.provider.OpenableColumns
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

data class TlsQualificationState(
    val address: String = "", val serverRootSubject: String? = null, val result: PivTlsResult? = null,
    val revision: Long = 0, val running: Boolean = false,
)
class TlsQualificationInput(val revision: Long, val bundle: EnrollmentBundle, val address: String,
                            val pin: CharArray) : java.io.Closeable {
    override fun close() { pin.fill('\u0000') }
}

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val usb = application.getSystemService(UsbManager::class.java)
    private val mutable = MutableStateFlow(ManagerState())
    val state = mutable.asStateFlow()
    val enrollment = EnrollmentWorkflow()
    val signingFiles = PivSigningFiles()
    val inventory = FidoInventory()

    fun inventoryAction(operation: ManagerOperation?) { if (!mutable.value.busy) inventory.action(operation) }
    fun selectInventoryRp(generation: Long, rp: InventoryRp) {
        if (mutable.value.busy || generation != inventory.state.value.generation) return
        retireResult(); retireInventoryExport(); inventory.selectRp(rp)
        mutable.update { it.copy(result = null) }
    }
    fun selectInventoryCredential(generation: Long, credential: InventoryCredential) {
        if (mutable.value.busy || generation != inventory.state.value.generation) return
        retireResult(); retireInventoryExport(); inventory.selectCredential(credential)
        mutable.update { it.copy(result = null) }
    }
    private var signingAction: ManagerOperation? = null
    private val signingInput = AtomicReference<OperationInput?>()
    private val inventoryInput = AtomicReference<OperationInput?>()
    private var pendingEnrollmentRoot: Pair<Long, Long>? = null
    private val tlsMutable = MutableStateFlow(TlsQualificationState())
    val tls = tlsMutable.asStateFlow()
    private var tlsRoot: ByteArray? = null
    @Volatile private var tlsRevision = 0L
    private var tlsRootRequestId = 0L
    private data class TlsRootRequest(val request: Long, val revision: Long, val bundle: EnrollmentBundle, val route: String)
    private var pendingTlsRoot: TlsRootRequest? = null
    private val tlsAttempt = AtomicReference<PivTlsAttempt?>()
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
        if (mutable.value.selectedReader != null && selected == null) { cancel(); retireTls(); enrollment.retire() }
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
        retireInventoryExport()
        signingFiles.retire()
        retireTls()
        enrollment.retire()
        operations.clearSession()
        inventory.retire()
        presenceGeneration++
        mutable.value = mutable.value.copy(userPresenceRequired = false, ctap2Reader = null, selectedReader = reader, nfc = reader == null, result = null)
    }

    fun enrollmentSettings(address: String, cn: String) {
        if (!mutable.value.busy) {
            if (address != enrollment.state.value.address || cn != enrollment.state.value.cn) retireTls()
            enrollment.settings(address, cn)
        }
    }
    fun requestEnrollmentRoot() { pendingEnrollmentRoot = enrollment.rootRequest() }
    fun receiveEnrollmentRoot(uri: Uri?) {
        val request = pendingEnrollmentRoot
        pendingEnrollmentRoot = null
        if (uri != null && request != null) viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readNBytes(32769) }
                }.getOrNull()
            }
            if (bytes != null) enrollmentRoot(request, bytes)
        }
    }
    fun enrollmentRoot(request: Pair<Long, Long>, bytes: ByteArray) {
        if (!mutable.value.busy) runCatching { if (enrollment.acceptRoot(request, bytes)) retireTls() }.onFailure {
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
                    retireTls()
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
    fun abandonEnrollment() { if (!mutable.value.busy) { retireTls(); enrollment.retire() } }

    private fun retireTls() {
        tlsRevision++
        pendingTlsRoot = null
        tlsAttempt.get()?.close()
        tlsRoot = null
        tlsMutable.value = TlsQualificationState(revision = tlsRevision)
    }

    fun tlsSettings(address: String) {
        if (mutable.value.busy || address == tlsMutable.value.address) return
        tlsRevision++
        pendingTlsRoot = null
        tlsMutable.value = tlsMutable.value.copy(address = address, result = null, revision = tlsRevision)
    }

    fun requestTlsRoot() {
        val source = enrollment.state.value
        val reader = mutable.value.selectedReader
        if (mutable.value.busy || source.stage != EnrollmentStage.IMPORTED || reader?.wire != CardWire.APDU) return
        pendingTlsRoot = TlsRootRequest(++tlsRootRequestId, tlsRevision, requireNotNull(source.bundle), reader.routeKey)
    }

    fun receiveTlsRoot(uri: Uri?) {
        val request = pendingTlsRoot
        pendingTlsRoot = null
        if (uri != null && request != null) viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readNBytes(32769) } }.getOrNull()
            }
            if (bytes != null) acceptTlsRoot(request, bytes)
        }
    }

    private fun acceptTlsRoot(request: TlsRootRequest, bytes: ByteArray) {
        if (request.request != tlsRootRequestId || request.revision != tlsRevision || mutable.value.busy ||
            enrollment.state.value.stage != EnrollmentStage.IMPORTED || enrollment.state.value.bundle !== request.bundle ||
            mutable.value.selectedReader?.routeKey != request.route ||
            mutable.value.selectedReader?.let { UsbAuthenticatorReader.isCurrent(usb, it) } != true) return
        runCatching {
            val root = EnrollmentProfile.root(bytes)
            tlsRoot = root
            tlsRevision++
            tlsMutable.value = tlsMutable.value.copy(serverRootSubject = EnrollmentProfile.certificate(root).subjectX500Principal.name, result = null, revision = tlsRevision)
        }.onFailure { mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) } }
    }

    fun submitTls(input: TlsQualificationInput) {
        try {
            val reader = mutable.value.selectedReader ?: return
            if (input.revision != tlsRevision || input.address != tlsMutable.value.address ||
                input.bundle !== enrollment.state.value.bundle) return
            startTls(input.pin) { UsbAuthenticatorReader.connect(usb, reader, allowInitialPowerOn = true) }
        } finally { input.close() }
    }

    private fun startTls(pin: CharArray, connect: () -> CardChannel) {
        val reader = mutable.value.selectedReader
        if (mutable.value.busy || mutable.value.requiresInspection || mutable.value.nfc || reader?.wire != CardWire.APDU ||
            !UsbAuthenticatorReader.isCurrent(usb, reader) || !usb.hasPermission(reader.device)) { pin.fill('\u0000'); return }
        if (CardOperationGate.isRemovalRequired(reader.routeKey)) {
            pin.fill('\u0000')
            mutable.update { it.copy(result = OperationResult.Failed(FailureCode.PHYSICAL_ACTION_REQUIRED, Effect.NONE)) }
            return
        }
        val source = enrollment.state.value
        val revision = tlsRevision
        val attempt = try {
            require(pin.size in 6..8 && pin.all { it in '0'..'9' })
            val identity = PivTlsIdentity(enrollment.importedIdentity())
            val endpoint = PivTlsEndpoint(tlsMutable.value.address, requireNotNull(tlsRoot))
            PivTlsAttempt(identity, endpoint, pin, {
                mutable.value.selectedReader?.routeKey == reader.routeKey && UsbAuthenticatorReader.isCurrent(usb, reader) &&
                    enrollment.state.value.stage == EnrollmentStage.IMPORTED && enrollment.state.value.bundle === source.bundle &&
                    tlsRevision == revision
            }, connect)
        } catch (_: Exception) {
            pin.fill('\u0000')
            mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) }
            return
        }
        signingFiles.retireSelection()
        tlsAttempt.set(attempt)
        cancelled = false
        inProgress = true
        operationRoute = reader.routeKey
        operationWire = CardWire.APDU
        operations.clearSession()
        inventory.retire()
        retireInventoryExport()
        retireResult()
        tlsMutable.value = tlsMutable.value.copy(result = null, running = true)
        mutable.update { it.copy(busy = true, operation = null, result = null, resultWire = CardWire.APDU) }
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) { attempt.preflight() }
            val result = if (before is OperationResult.Failed) PivTlsResult(PivTlsOutcome.FAILED, before)
            else {
                val facts = (before as OperationResult.Completed).value
                var approved = false
                if (facts.pin.remaining == 1 && facts.puk.remaining == 0 && !cancelled) {
                    val approval = CompletableDeferred<Boolean>()
                    retryApproval = approval
                    mutable.update { it.copy(lastRetry = "PIN" to "PUK") }
                    approved = withTimeoutOrNull(120000) { approval.await() } == true
                    retryApproval = null
                    mutable.update { it.copy(lastRetry = null) }
                    if (!approved) attempt.close()
                }
                withContext(Dispatchers.IO) { attempt.execute(approved) }
            }
            attempt.close()
            if (tlsAttempt.compareAndSet(attempt, null)) {
                inProgress = false
                val current = tlsRevision == revision && enrollment.state.value.bundle === source.bundle &&
                    mutable.value.selectedReader?.routeKey == reader.routeKey && UsbAuthenticatorReader.isCurrent(usb, reader)
                val settled = if ((!current || cancelled) && result.outcome != PivTlsOutcome.TIMED_OUT)
                    result.copy(outcome = PivTlsOutcome.CANCELLED) else result
                tlsMutable.value = tlsMutable.value.copy(result = settled.takeIf { current }, running = false)
                val report: OperationResult<ManagerReport> = when (val card = settled.card) {
                    is OperationResult.Failed -> card
                    is OperationResult.Completed -> if (settled.outcome == PivTlsOutcome.QUALIFIED)
                        OperationResult.Completed(ManagerReport(emptyList()), card.effect)
                    else OperationResult.Failed(if (settled.outcome in setOf(PivTlsOutcome.CANCELLED, PivTlsOutcome.TIMED_OUT))
                        FailureCode.CANCELLED else FailureCode.TRANSPORT_FAILURE, card.effect)
                }
                if (report is OperationResult.Failed && report.code == FailureCode.PHYSICAL_ACTION_REQUIRED) {
                    presenceBlockedReader = reader.routeKey
                    CardOperationGate.requireRemoval(reader.routeKey)
                    observeCardRemoval(reader)
                }
                finish(report)
            }
        }
    }

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
            inventory.retire()
            retireInventoryExport()
            retireResult()
            signingFiles.retireSelection()
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

    fun fileSigningAction(operation: ManagerOperation?, slot: Int = 0x9a) {
        if (operation != signingAction) {
            signingAction = operation
            signingFiles.retire()
        }
        signingFiles.action(operation == ManagerOperation.PIV_SIGN, slot)
    }

    fun requestSigningFile(): Boolean {
        val reader = mutable.value.selectedReader
        if (mutable.value.busy || signingFiles.hasSaveOwner || signingAction != ManagerOperation.PIV_SIGN ||
            mutable.value.nfc || reader?.wire != CardWire.APDU || !UsbAuthenticatorReader.isCurrent(usb, reader)) return false
        signingFiles.request(reader.routeKey)
        return true
    }

    internal val signingFileRequestId get() = signingFiles.fileRequestId
    internal val signatureSaveRequestId get() = signingFiles.saveRequestId

    fun receiveSigningFile(requestId: String, uri: Uri?) {
        val request = signingFiles.takeRequest(requestId) ?: return
        if (uri == null) { signingFiles.retireSelection(); return }
        viewModelScope.launch {
            var bytes: ByteArray? = null
            try {
                val name = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment.orEmpty()
                    require(name.isNotBlank())
                    bytes = PivSigningFiles.readRaw(resolver.openInputStream(uri) ?: throw java.io.IOException())
                    name.take(255).filterNot { it.isISOControl() }
                }
                signingFiles.accept(request, mutable.value.selectedReader?.takeIf { UsbAuthenticatorReader.isCurrent(usb, it) }?.routeKey,
                    name, requireNotNull(bytes))
            } catch (_: Exception) {
                if (signingFiles.current(request, mutable.value.selectedReader?.routeKey))
                    mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) }
            } finally { bytes?.fill(0) }
        }
    }

    fun requestSignatureSave(report: ManagerReport): Boolean {
        val result = mutable.value.result as? OperationResult.Completed ?: return false
        if (mutable.value.busy || mutable.value.operation != ManagerOperation.PIV_SIGN || result.value !== report) return false
        return signingFiles.requestSave(report, signingAction)
    }

    fun receiveSignatureSave(requestId: String, uri: Uri?) {
        val snapshot = signingFiles.takeSave(requestId) ?: return
        if (uri == null) { signingFiles.finishSave(snapshot, SignatureExportStatus.CANCELLED); return }
        signingFiles.writing(snapshot)
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                PivSigningFiles.writeSignature(snapshot) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt") }
            }
            signingFiles.finishSave(snapshot, status)
        }
    }

    private class InventoryExport(val id: String, private var bytes: ByteArray?) : AutoCloseable {
        @Synchronized fun take(): ByteArray? = bytes.also { bytes = null }
        @Synchronized override fun close() { bytes?.fill(0); bytes = null }
    }
    private var inventoryExport: InventoryExport? = null
    private val inventoryExportWrite = AtomicReference<InventoryExport?>()
    val inventoryExportId get() = inventoryExport?.id
    fun requestInventoryExport(report: ManagerReport): String? {
        if (mutable.value.busy || report !== (mutable.value.result as? OperationResult.Completed)?.value ||
            report.inventoryGeneration != inventory.state.value.generation || !report.protectedExport || report.export == null) return null
        inventoryExport?.close()
        return java.util.UUID.randomUUID().toString().also { inventoryExport = InventoryExport(it, report.export.copyOf()) }
    }
    fun receiveInventoryExport(id: String, uri: Uri?) {
        val request = inventoryExport?.takeIf { it.id == id } ?: return
        inventoryExport = null
        if (uri == null) { request.close(); return }
        inventoryExportWrite.getAndSet(request)?.close()
        viewModelScope.launch(Dispatchers.IO) {
            if (!inventoryExportWrite.compareAndSet(request, null)) return@launch
            val bytes = request.take() ?: return@launch
            try { runCatching { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } } }
            finally { bytes.fill(0) }
        }
    }
    private fun retireInventoryExport() { inventoryExport?.close(); inventoryExport = null; inventoryExportWrite.getAndSet(null)?.close() }

    fun requestInventoryFile(generation: Long): String? = if (!mutable.value.busy && generation == inventory.state.value.generation) inventory.requestFile() else null
    fun receiveInventoryFile(id: String, uri: Uri?) {
        val generation = inventory.receiveFile(id) ?: return
        if (uri == null) { inventory.finishFile(id, generation, null); return }
        viewModelScope.launch {
            var bytes: ByteArray? = null
            try {
                withContext(Dispatchers.IO) {
                    val buffer = ByteArray(65537)
                    try { bytes = runCatching {
                        var length = 0
                        val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@runCatching null
                        stream.use {
                            while (length < buffer.size) {
                                val read = it.read(buffer, length, buffer.size - length)
                                if (read < 0) break
                                if (read == 0) throw java.io.IOException()
                                length += read
                            }
                            require(length <= 65536)
                        }
                        buffer.copyOf(length)
                    }.getOrNull() } finally { buffer.fill(0) }
                }
                if (inventory.finishFile(id, generation, bytes)) {
                    if (bytes == null) mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) }
                    bytes = null
                }
            } finally { bytes?.fill(0) }
        }
    }

    fun submit(input: OperationInput) {
        if (input.operation != ManagerOperation.PIV_SIGN) { submitOwned(input); return }
        if (mutable.value.busy || mutable.value.requiresInspection) { input.close(); return }
        if (!mutable.value.supportsPiv) { submitOwned(input); return }
        if (mutable.value.nfc) {
            input.close()
            mutable.update { it.copy(result = OperationResult.Failed(FailureCode.CONTACT_REQUIRED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)) }
            return
        }
        val owned = try {
            val reader = requireNotNull(mutable.value.selectedReader)
            require(signingAction == ManagerOperation.PIV_SIGN && !mutable.value.nfc && reader.wire == CardWire.APDU &&
                UsbAuthenticatorReader.isCurrent(usb, reader) && usb.hasPermission(reader.device) && input.bytes.isEmpty())
            val bytes = signingFiles.take(input.signingFileGeneration, reader.routeKey)
            OperationInput(ManagerOperation.PIV_SIGN, pin = input.pin.copyOf(), slot = input.slot, bytes = bytes)
        } catch (_: Exception) {
            input.close()
            mutable.update { it.copy(result = OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE)) }
            return
        }
        input.close()
        submitOwned(owned)
    }

    private fun submitOwned(input: OperationInput) {
        if (mutable.value.busy) { input.close(); return }
        if (mutable.value.requiresInspection && !ConfirmationPolicy.canInspect(input.operation)) {
            input.close(); return
        }
        try {
            input.inventoryRequest = inventory.begin(input)
            if (input.operation == ManagerOperation.FIDO_BLOB_WRITE && input.inventoryRequest?.credential != null && !input.deleteBlob) {
                input.inventoryBytes = inventory.takeFile()
            }
        } catch (failure: CardFailure) {
            input.close()
            retireResult()
            mutable.update { it.copy(result = OperationResult.Failed(failure.code, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)) }
            return
        }
        retireInventoryExport()
        signingFiles.retireSelection()
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
        if (input.operation == ManagerOperation.PIV_SIGN) signingInput.set(input)
        if (input.inventoryRequest?.credential != null) inventoryInput.set(input)
        inProgress = true
        val generation = ++presenceGeneration
        val enrollmentOwner = enrollment.generation
        val reader = mutable.value.selectedReader
        viewModelScope.launch {
            var writeAccepted = false
            var negotiatedCtap2: Boolean? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    if (input.inventoryRequest?.credential != null && !inventoryInput.compareAndSet(input, null))
                        return@withContext OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                    if (input.operation == ManagerOperation.PIV_SIGN && !signingInput.compareAndSet(input, null))
                        return@withContext OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
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
            val retired = cancelled || input.inventoryRequest?.let { !inventory.current(it) } == true || (reader != null && (mutable.value.selectedReader?.routeKey != reader.routeKey ||
                !UsbAuthenticatorReader.isCurrent(usb, reader)))
            if (retired) {
                mutable.update { it.copy(ctap2Reader = null) }
                operations.clearSession()
                inventory.retire()
                (result as? OperationResult.Completed)?.value?.let { report ->
                    report.secrets?.close()
                    if (report.protectedExport) report.export?.fill(0)
                }
            }
            if (!retired && result is OperationResult.Completed) {
                input.inventoryRequest?.let { inventory.settle(it, result.value) }
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

    fun pauseForActivity(changingConfigurations: Boolean) {
        cancel(changingConfigurations || signingFiles.hasDocumentRequest, !mutable.value.busy && inventory.fileRequestId != null,
            !mutable.value.busy && inventoryExport != null)
    }

    fun cancel() { cancel(false) }

    private fun cancel(preserveSigningDocuments: Boolean, preserveInventoryDocument: Boolean = false, preserveInventoryExport: Boolean = false) {
        if (!preserveInventoryExport) retireInventoryExport()
        signingInput.getAndSet(null)?.close()
        inventoryInput.getAndSet(null)?.close()
        if (!preserveSigningDocuments) signingFiles.retire()
        val preserveSignatureResult = preserveSigningDocuments && mutable.value.operation == ManagerOperation.PIV_SIGN &&
            (mutable.value.result as? OperationResult.Completed)?.value?.fileName == "signature.bin"
        tlsAttempt.get()?.close()
        enrollment.pause()
        networkCall.getAndSet(null)?.let { call ->
            call.cancel()
            mutable.update { it.copy(busy = false) }
        }
        cancelled = true
        presenceGeneration++
        operations.clearSession()
        if (!preserveInventoryDocument) inventory.retire()
        if (!preserveSignatureResult) retireResult()
        mutable.value = mutable.value.copy(result = mutable.value.result.takeIf { preserveSignatureResult }, userPresenceRequired = false)
        retryApproval?.complete(false)
        presenceWatch?.cancel()
        pending?.close()
        pending = null
        currentChannel.get()?.let { channel -> viewModelScope.launch(Dispatchers.IO) {
            synchronized(presenceLock) {
                if (currentChannel.get() === channel) {
                    channel.setUserPresenceListener(null)
                    channel.cancel()
                }
            }
        } }
        if (!inProgress && mutable.value.busy) finish(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE))
    }

    override fun onCleared() { cancel(); super.onCleared() }
}
