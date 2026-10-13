package cc.cardkey.enrollment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EnrollmentStage { EMPTY, CSR, ISSUING, VERIFIED, UNKNOWN, REJECTED, INVALID, IMPORTED }
data class IssuanceReceipt(val csr: EnrollmentCsr, val endpoint: String, val stage: EnrollmentStage)
data class EnrollmentState(
    val address: String = "", val cn: String = "", val hasRoot: Boolean = false,
    val csr: EnrollmentCsr? = null, val bundle: EnrollmentBundle? = null,
    val stage: EnrollmentStage = EnrollmentStage.EMPTY, val attempted: Boolean = false,
    val receipt: IssuanceReceipt? = null,
)
class EnrollmentImport(val csr: EnrollmentCsr, val ca: EnrollmentCA, val bundle: EnrollmentBundle) {
    fun validate() = EnrollmentProfile.validate(bundle.chain, ca.anchor, csr, issuedAt = bundle.verifiedAt)
}

class EnrollmentWorkflow {
    private val mutable = MutableStateFlow(EnrollmentState())
    val state = mutable.asStateFlow()
    private var root: ByteArray? = null
    private val attemptedBindings = mutableSetOf<String>()
    var generation = 0L
        private set
    private var settingsGeneration = 0L
    private var rootRequest = 0L
    fun settings(address: String, cn: String) {
        if (address == mutable.value.address && cn == mutable.value.cn) return
        retire()
        mutable.value = mutable.value.copy(address = address, cn = cn)
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
        require(csr.cn == mutable.value.cn)
        mutable.value = mutable.value.copy(csr = csr, stage = EnrollmentStage.CSR, attempted = csr.binding in attemptedBindings)
    }
    fun startIssuance(token: String): Pair<Long, EnrollmentCsr> {
        val state = mutable.value
        require(!state.attempted)
        val csr = requireNotNull(state.csr)
        require(csr.binding !in attemptedBindings)
        ca().checkToken(token, csr)
        val owner = ++generation
        attemptedBindings += csr.binding
        mutable.value = state.copy(attempted = true, stage = EnrollmentStage.ISSUING,
            receipt = IssuanceReceipt(csr, ca().endpoint.toString(), EnrollmentStage.UNKNOWN))
        return owner to csr
    }
    fun settleIssuance(owner: Long, bundle: EnrollmentBundle?, stage: EnrollmentStage) {
        if (owner != generation) return
        require(stage in setOf(EnrollmentStage.VERIFIED, EnrollmentStage.UNKNOWN, EnrollmentStage.REJECTED, EnrollmentStage.INVALID))
        mutable.value = mutable.value.copy(bundle = bundle, stage = stage,
            receipt = mutable.value.receipt?.copy(stage = stage))
    }
    fun importRequest(): EnrollmentImport {
        require(mutable.value.stage == EnrollmentStage.VERIFIED)
        return EnrollmentImport(requireNotNull(mutable.value.csr), ca(), requireNotNull(mutable.value.bundle)).also { it.validate() }
    }
    fun imported(owner: Long) {
        if (owner == generation) mutable.value = mutable.value.copy(stage = EnrollmentStage.IMPORTED)
    }
    fun pause() {
        generation++
        if (mutable.value.stage == EnrollmentStage.ISSUING)
            mutable.value = mutable.value.copy(stage = EnrollmentStage.UNKNOWN)
    }
    fun retire() {
        pause()
        settingsGeneration++
        mutable.value = mutable.value.copy(csr = null, bundle = null, stage = EnrollmentStage.EMPTY, attempted = false)
    }
}
