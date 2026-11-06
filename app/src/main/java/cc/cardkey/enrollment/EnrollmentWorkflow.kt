package cc.cardkey.enrollment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EnrollmentStage { EMPTY, CSR, ISSUING, VERIFIED, UNKNOWN, REJECTED, INVALID, IMPORTED }
data class EnrollmentTarget(val issuerOrigin: String, val personId: String, val cardId: String,
                            val caId: String, val identity: EnrollmentIdentity) {
    init {
        require(issuerOrigin == enrollmentOrigin(issuerOrigin))
        require(listOf(personId, cardId, caId).all { it.isNotBlank() })
    }
}
data class IssuanceReceipt(val csr: EnrollmentCsr, val ca: EnrollmentCA, val stage: EnrollmentStage,
                           val target: EnrollmentTarget, val issuanceId: String,
                           val registrationKey: String, val mintKey: String,
                           val resultKey: String, val certificateId: String,
                           val bundle: EnrollmentBundle? = null, val reportPending: Boolean = false) {
    val endpoint get() = ca.endpoint.toString()
}
data class EnrollmentState(
    val address: String = "", val target: EnrollmentTarget? = null, val hasRoot: Boolean = false,
    val csr: EnrollmentCsr? = null, val bundle: EnrollmentBundle? = null,
    val stage: EnrollmentStage = EnrollmentStage.EMPTY, val attempted: Boolean = false,
    val receipt: IssuanceReceipt? = null,
    val unknownReceipts: List<IssuanceReceipt> = emptyList(),
    val selection: EnrollmentSelection = EnrollmentSelection(), val revision: Long = 0,
    val savedAddress: String = "",
    val restored: Boolean = false,
) {
    val cn get() = target?.identity?.displayName.orEmpty()
}
class EnrollmentImport(val csr: EnrollmentCsr, val ca: EnrollmentCA, val bundle: EnrollmentBundle) {
    fun validate() = EnrollmentProfile.validate(bundle.chain, ca.anchor, csr, issuedAt = bundle.verifiedAt)
}

class EnrollmentWorkflow(private val settingsStore: EnrollmentSettings? = null) {
    private val savedAddress = settingsStore?.load().orEmpty()
    private var receipts = settingsStore?.loadReceipts().orEmpty()
    private val recovered = receipts.lastOrNull()
    private val mutable = MutableStateFlow(EnrollmentState(
        address = recovered?.ca?.endpoint?.newBuilder()?.encodedPath("/")?.build()?.toString()?.let(::enrollmentOrigin) ?: savedAddress,
        target = recovered?.target, hasRoot = recovered != null, csr = recovered?.csr,
        stage = recovered?.stage ?: EnrollmentStage.EMPTY, bundle = recovered?.bundle,
        attempted = recovered != null, receipt = recovered ?: receipts.lastOrNull(),
        unknownReceipts = receipts.filter { it.stage == EnrollmentStage.UNKNOWN },
        selection = recovered?.csr?.selection ?: EnrollmentSelection(), savedAddress = savedAddress, restored = recovered != null))
    val state = mutable.asStateFlow()
    private var root: ByteArray? = recovered?.ca?.anchor
    private val attemptedBindings = receipts.map { it.csr.binding }.toMutableSet()
    private data class Intent(val selection: EnrollmentSelection, val spki: String, val identity: EnrollmentIdentity,
                              val target: EnrollmentTarget, val endpoint: String, val root: String)
    private fun intent(csr: EnrollmentCsr, ca: EnrollmentCA = ca(), target: EnrollmentTarget = requireNotNull(mutable.value.target)): Intent {
        val encoder = java.util.Base64.getEncoder()
        return Intent(csr.selection, encoder.encodeToString(csr.spki), csr.identity, target,
            ca.endpoint.toString(), encoder.encodeToString(ca.anchor))
    }
    private fun previouslyAttempted(csr: EnrollmentCsr) = csr.binding in attemptedBindings ||
        receipts.any { it.stage == EnrollmentStage.UNKNOWN && intent(it.csr, it.ca, it.target) == intent(csr) }
    private var preparedOwner: Long? = null
    private fun persist(next: List<IssuanceReceipt>) {
        settingsStore?.saveReceipts(next)
        receipts = next
    }
    fun choosePerson(target: EnrollmentTarget?) {
        if (target == mutable.value.target) return
        retire()
        mutable.value = mutable.value.copy(target = target)
    }
    fun selection(value: EnrollmentSelection) {
        if (mutable.value.selection == value) return
        retire()
        mutable.value = mutable.value.copy(selection = value)
    }
    var generation = 0L
        private set
    private var settingsGeneration = 0L
    private var rootRequest = 0L
    fun settings(address: String) {
        if (address == mutable.value.address) return
        val addressChanged = address != mutable.value.address
        retire()
        if (addressChanged) root = null
        val validAddress = if (address.isEmpty()) "" else runCatching { enrollmentOrigin(address) }.getOrNull()
        if (addressChanged && validAddress != null) settingsStore?.save(validAddress)
        mutable.value = mutable.value.copy(address = address,
            hasRoot = root != null, savedAddress = validAddress ?: mutable.value.savedAddress)
    }
    fun rootRequest(): Pair<Long, Long> = ++rootRequest to settingsGeneration
    fun acceptRoot(request: Pair<Long, Long>, bytes: ByteArray): Boolean {
        if (request != rootRequest to settingsGeneration) return false
        val verified = EnrollmentProfile.root(bytes)
        retire()
        root = verified.copyOf()
        mutable.value = mutable.value.copy(hasRoot = true)
        return true
    }
    fun ca() = EnrollmentCA(mutable.value.address, requireNotNull(root))
    fun startCsr(): Long {
        val target = requireNotNull(mutable.value.target)
        require(mutable.value.selection.purpose != EnrollmentPurpose.TLS || target.identity.upn.isNotBlank())
        ca()
        retire()
        return generation
    }
    fun settleCsr(owner: Long, csr: EnrollmentCsr) {
        if (owner != generation) return
        require(csr.identity == requireNotNull(mutable.value.target).identity && csr.selection == mutable.value.selection)
        mutable.value = mutable.value.copy(csr = csr, stage = EnrollmentStage.CSR, attempted = previouslyAttempted(csr))
    }
    fun prepareIssuerRequest(): Pair<Long, IssuanceReceipt> {
        val state = mutable.value
        require(!state.attempted)
        val csr = requireNotNull(state.csr)
        require(!previouslyAttempted(csr))
        require(csr.identity == requireNotNull(state.target).identity && csr.selection == state.selection)
        val receipt = IssuanceReceipt(csr, ca(), EnrollmentStage.UNKNOWN, requireNotNull(state.target),
            java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
            java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        persist(receipts + receipt)
        val owner = ++generation
        preparedOwner = owner
        attemptedBindings += csr.binding
        mutable.value = state.copy(attempted = true, stage = EnrollmentStage.ISSUING, revision = owner,
            receipt = receipt, unknownReceipts = receipts.filter { it.stage == EnrollmentStage.UNKNOWN })
        return owner to receipt
    }
    fun startIssuance(token: String): Pair<Long, EnrollmentCsr> {
        val csr = requireNotNull(mutable.value.csr)
        ca().checkToken(token, csr)
        val owner = preparedOwner ?: prepareIssuerRequest().first
        require(owner == generation && mutable.value.stage == EnrollmentStage.ISSUING)
        preparedOwner = null
        return owner to csr
    }
    fun unsubmitted(owner: Long) {
        if (owner == generation && mutable.value.stage == EnrollmentStage.ISSUING) pause()
    }
    fun settleIssuance(owner: Long, bundle: EnrollmentBundle?, stage: EnrollmentStage) {
        if (owner != generation) return
        require(stage in setOf(EnrollmentStage.VERIFIED, EnrollmentStage.UNKNOWN, EnrollmentStage.REJECTED, EnrollmentStage.INVALID))
        val receipt = requireNotNull(mutable.value.receipt).copy(stage = stage, bundle = bundle, reportPending = true)
        persist(receipts.map { if (it.issuanceId == receipt.issuanceId) receipt else it })
        preparedOwner = null
        mutable.value = mutable.value.copy(bundle = bundle, stage = stage, receipt = receipt,
            unknownReceipts = receipts.filter { it.stage == EnrollmentStage.UNKNOWN })
    }
    fun markResultReported(issuanceId: String) {
        val receipt = receipts.single { it.issuanceId == issuanceId }.copy(reportPending = false)
        persist(receipts.map { if (it.issuanceId == issuanceId) receipt else it })
        if (mutable.value.receipt?.issuanceId == issuanceId) mutable.value = mutable.value.copy(receipt = receipt)
        mutable.value = mutable.value.copy(unknownReceipts = receipts.filter { it.stage == EnrollmentStage.UNKNOWN })
    }
    fun importRequest(): EnrollmentImport {
        require(mutable.value.stage == EnrollmentStage.VERIFIED)
        return EnrollmentImport(requireNotNull(mutable.value.csr), ca(), requireNotNull(mutable.value.bundle)).also { it.validate() }
    }
    fun importedIdentity(): EnrollmentImport {
        require(mutable.value.stage == EnrollmentStage.IMPORTED)
        return EnrollmentImport(requireNotNull(mutable.value.csr), ca(), requireNotNull(mutable.value.bundle)).also { it.validate() }
    }
    fun imported(owner: Long) {
        if (owner != generation) return
        val receipt = requireNotNull(mutable.value.receipt).copy(stage = EnrollmentStage.IMPORTED)
        persist(receipts.map { if (it.issuanceId == receipt.issuanceId) receipt else it })
        mutable.value = mutable.value.copy(stage = EnrollmentStage.IMPORTED, receipt = receipt)
    }
    fun pause() {
        preparedOwner = null
        generation++
        mutable.value = mutable.value.copy(revision = generation)
        if (mutable.value.stage == EnrollmentStage.ISSUING)
            mutable.value = mutable.value.copy(stage = EnrollmentStage.UNKNOWN)
    }
    fun retire() {
        pause()
        settingsGeneration++
        mutable.value = mutable.value.copy(csr = null, bundle = null, stage = EnrollmentStage.EMPTY, attempted = false, restored = false)
    }
}
