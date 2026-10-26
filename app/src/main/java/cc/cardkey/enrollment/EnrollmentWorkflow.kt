package cc.cardkey.enrollment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EnrollmentStage { EMPTY, CSR, ISSUING, VERIFIED, UNKNOWN, REJECTED, INVALID, IMPORTED }
data class IssuanceReceipt(val csr: EnrollmentCsr, val ca: EnrollmentCA, val stage: EnrollmentStage) {
    val endpoint get() = ca.endpoint.toString()
}
data class EnrollmentState(
    val address: String = "", val cn: String = "", val hasRoot: Boolean = false,
    val csr: EnrollmentCsr? = null, val bundle: EnrollmentBundle? = null,
    val stage: EnrollmentStage = EnrollmentStage.EMPTY, val attempted: Boolean = false,
    val receipt: IssuanceReceipt? = null,
    val unknownReceipts: List<IssuanceReceipt> = emptyList(),
    val selection: EnrollmentSelection = EnrollmentSelection(), val revision: Long = 0,
    val savedAddress: String = "",
)
class EnrollmentImport(val csr: EnrollmentCsr, val ca: EnrollmentCA, val bundle: EnrollmentBundle) {
    fun validate() = EnrollmentProfile.validate(bundle.chain, ca.anchor, csr, issuedAt = bundle.verifiedAt)
}

class EnrollmentWorkflow(private val settingsStore: EnrollmentSettings? = null) {
    private val savedAddress = settingsStore?.load().orEmpty()
    private val mutable = MutableStateFlow(EnrollmentState(address = savedAddress, savedAddress = savedAddress))
    val state = mutable.asStateFlow()
    private var root: ByteArray? = null
    private val attemptedBindings = mutableSetOf<String>()
    private data class Intent(val selection: EnrollmentSelection, val spki: String, val cn: String,
                              val endpoint: String, val root: String)
    private val unknownIntents = mutableSetOf<Intent>()
    private var activeIntent: Intent? = null
    private var unsubmittedState: EnrollmentState? = null
    private fun intent(csr: EnrollmentCsr): Intent {
        val ca = ca()
        val encoder = java.util.Base64.getEncoder()
        return Intent(csr.selection, encoder.encodeToString(csr.spki), csr.cn,
            ca.endpoint.toString(), encoder.encodeToString(ca.anchor))
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
    fun settings(address: String, cn: String) {
        if (address == mutable.value.address && cn == mutable.value.cn) return
        val addressChanged = address != mutable.value.address
        retire()
        if (addressChanged) root = null
        val validAddress = if (address.isEmpty()) "" else runCatching { enrollmentOrigin(address) }.getOrNull()
        if (addressChanged && validAddress != null) settingsStore?.save(validAddress)
        mutable.value = mutable.value.copy(address = address, cn = cn,
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
        EnrollmentProfile.name(mutable.value.cn)
        ca()
        retire()
        return generation
    }
    fun settleCsr(owner: Long, csr: EnrollmentCsr) {
        if (owner != generation) return
        require(csr.cn == mutable.value.cn && csr.selection == mutable.value.selection)
        mutable.value = mutable.value.copy(csr = csr, stage = EnrollmentStage.CSR, attempted = csr.binding in attemptedBindings || intent(csr) in unknownIntents)
    }
    fun startIssuance(token: String): Pair<Long, EnrollmentCsr> {
        val state = mutable.value
        require(!state.attempted)
        val csr = requireNotNull(state.csr)
        require(csr.binding !in attemptedBindings && intent(csr) !in unknownIntents)
        ca().checkToken(token, csr)
        val owner = ++generation
        unsubmittedState = state
        attemptedBindings += csr.binding
        activeIntent = intent(csr)
        unknownIntents += requireNotNull(activeIntent)
        val receipt = IssuanceReceipt(csr, ca(), EnrollmentStage.UNKNOWN)
        mutable.value = state.copy(attempted = true, stage = EnrollmentStage.ISSUING, revision = owner,
            receipt = receipt, unknownReceipts = state.unknownReceipts + receipt)
        return owner to csr
    }
    fun unsubmitted(owner: Long) {
        if (owner != generation || mutable.value.stage != EnrollmentStage.ISSUING) return
        val previous = requireNotNull(unsubmittedState)
        attemptedBindings.remove(requireNotNull(mutable.value.csr).binding)
        activeIntent?.let { unknownIntents.remove(it) }
        activeIntent = null
        unsubmittedState = null
        mutable.value = previous.copy(revision = ++generation)
    }
    fun settleIssuance(owner: Long, bundle: EnrollmentBundle?, stage: EnrollmentStage) {
        if (owner != generation) return
        require(stage in setOf(EnrollmentStage.VERIFIED, EnrollmentStage.UNKNOWN, EnrollmentStage.REJECTED, EnrollmentStage.INVALID))
        if (stage != EnrollmentStage.UNKNOWN) {
            activeIntent?.let { unknownIntents.remove(it) }
            mutable.value = mutable.value.copy(unknownReceipts = mutable.value.unknownReceipts.filterNot { it === mutable.value.receipt })
        }
        activeIntent = null
        unsubmittedState = null
        mutable.value = mutable.value.copy(bundle = bundle, stage = stage,
            receipt = mutable.value.receipt?.copy(stage = stage))
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
        if (owner == generation) mutable.value = mutable.value.copy(stage = EnrollmentStage.IMPORTED)
    }
    fun pause() {
        unsubmittedState = null
        generation++
        mutable.value = mutable.value.copy(revision = generation)
        if (mutable.value.stage == EnrollmentStage.ISSUING)
            mutable.value = mutable.value.copy(stage = EnrollmentStage.UNKNOWN)
    }
    fun retire() {
        pause()
        settingsGeneration++
        mutable.value = mutable.value.copy(csr = null, bundle = null, stage = EnrollmentStage.EMPTY, attempted = false)
    }
}
