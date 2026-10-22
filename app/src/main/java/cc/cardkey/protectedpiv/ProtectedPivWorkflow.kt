package cc.cardkey.protectedpiv

import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.UUID

// A document request, recognition receipt and execution share one foreground generation.
enum class ProtectedPivDocument { ISSUER, PROFILE, SIGNING_FILE }
enum class ProtectedPivAction(val privateOperation: Boolean) {
    STATUS(false), SLOT(false), SSH(false), VERIFY_PIN(true), CSR(true), RAW_SIGN(true), ECDH(true), RSA(true);
    val operation get() = when (this) {
        STATUS, VERIFY_PIN -> ManagerOperation.PIV_INFO
        SLOT -> ManagerOperation.PIV_SLOT
        SSH -> ManagerOperation.PIV_SSH_EXPORT
        CSR -> ManagerOperation.PIV_CSR
        RAW_SIGN -> ManagerOperation.PIV_SIGN
        ECDH -> ManagerOperation.PIV_ECDH
        RSA -> ManagerOperation.PIV_DECRYPT
    }
}

data class ProtectedPivState(
    val generation: Long = 0, val issuerSubject: String? = null, val issuerFingerprint: String? = null,
    val guid: String? = null, val profileReady: Boolean = false, val awaitingPin: Boolean = false,
    val action: ProtectedPivAction? = null, val slot: Int = 0x9a, val retries: Int? = null,
    val erasesKeysOnFailure: Boolean = false,
    val fileName: String? = null, val fileSize: Int? = null, val documentId: String? = null,
)

internal class ProtectedPivReceipt(val info: PivInfo, val pin: PivPinStatus, metadata: SlotMetadata?) {
    private val algorithm = metadata?.algorithm
    private val pinPolicy = metadata?.pinPolicy
    private val touchPolicy = metadata?.touchPolicy
    private val generated = metadata?.generated
    private val spki = metadata?.publicKey?.encoded?.copyOf()
    fun matches(other: ProtectedPivReceipt) = info == other.info && pin == other.pin &&
        algorithm == other.algorithm && pinPolicy == other.pinPolicy && touchPolicy == other.touchPolicy &&
        generated == other.generated && (spki?.contentEquals(other.spki) ?: (other.spki == null))
}

class ProtectedPivWorkflow {
    private val mutable = MutableStateFlow(ProtectedPivState())
    val state = mutable.asStateFlow()
    private var issuer: ProtectedPivIssuer? = null
    private var profile: ProtectedPivProfile? = null
    private var file: ByteArray? = null
    private var document: ProtectedPivDocument? = null
    private var scan: ProtectedPivScan? = null
    private var receipt: ProtectedPivReceipt? = null
    private var subject = ""

    @Synchronized fun requestDocument(kind: ProtectedPivDocument): String? {
        if (scan != null || kind == ProtectedPivDocument.PROFILE && issuer == null) return null
        retirePrivate()
        if (kind == ProtectedPivDocument.ISSUER) issuer = null
        if (kind == ProtectedPivDocument.SIGNING_FILE) { file?.fill(0); file = null }
        document = kind
        val id = UUID.randomUUID().toString()
        mutable.value = mutable.value.copy(issuerSubject = issuer?.subject, issuerFingerprint = issuer?.fingerprint,
            documentId = id, fileName = mutable.value.fileName.takeIf { file != null }, fileSize = file?.size)
        return id
    }

    @Synchronized fun documentKind(id: String): ProtectedPivDocument? = document.takeIf { mutable.value.documentId == id }

    @Synchronized fun receiveDocument(id: String, bytes: ByteArray?, name: String? = null): Boolean {
        val kind = documentKind(id) ?: return false
        document = null
        mutable.value = mutable.value.copy(documentId = null)
        if (bytes == null) return true
        when (kind) {
            ProtectedPivDocument.ISSUER -> {
                issuer = ProtectedPivProfileParser.issuer(bytes)
                mutable.value = mutable.value.copy(issuerSubject = issuer!!.subject, issuerFingerprint = issuer!!.fingerprint)
            }
            ProtectedPivDocument.PROFILE -> {
                profile = ProtectedPivProfileParser.profile(requireNotNull(issuer), bytes)
                mutable.value = mutable.value.copy(guid = profile!!.guid, profileReady = true)
            }
            ProtectedPivDocument.SIGNING_FILE -> {
                require(bytes.size in 1..PivClient.MAX_SIGNING_FILE_BYTES)
                file = bytes.copyOf()
                mutable.value = mutable.value.copy(fileName = name ?: "file", fileSize = bytes.size)
            }
        }
        return true
    }

    @Synchronized fun recognize(action: ProtectedPivAction, slot: Int, subject: String): OperationInput {
        require(scan == null && receipt == null && document == null)
        require(slot in PivClient.slots)
        require(subject.length <= 1024)
        if (action == ProtectedPivAction.CSR) org.bouncycastle.asn1.x500.X500Name(subject)
        if (action == ProtectedPivAction.RAW_SIGN) require(file != null)
        val trusted = requireNotNull(profile)
        this.subject = subject
        mutable.value = mutable.value.copy(action = action, slot = slot, awaitingPin = false, retries = null, erasesKeysOnFailure = false)
        return input(trusted, action, slot, charArrayOf(), null)
    }

    @Synchronized fun execute(pin: CharArray): OperationInput {
        try {
            val current = mutable.value
            require(scan == null && current.awaitingPin && pin.size in 6..8 && pin.all { it in '0'..'9' })
            return input(requireNotNull(profile), requireNotNull(current.action), current.slot, pin.copyOf(), requireNotNull(receipt))
                .also { mutable.value = current.copy(awaitingPin = false) }
        } finally { pin.fill('\u0000') }
    }

    private fun input(profile: ProtectedPivProfile, action: ProtectedPivAction, slot: Int, pin: CharArray,
                      expected: ProtectedPivReceipt?): OperationInput {
        val owner = mutable.value.generation
        val input = OperationInput(action.operation, pin = pin, slot = slot, text = subject,
            bytes = if (action == ProtectedPivAction.RAW_SIGN && expected != null) requireNotNull(file).copyOf() else byteArrayOf())
        val attempt = ProtectedPivScan(owner, action, profile.identity, profile.guid + ":" + profile.issuerFingerprint, profile.pairingCopy(), expected) { candidate ->
            synchronized(this) { mutable.value.generation == owner && scan === candidate }
        }
        scan = attempt
        input.protectedScan = attempt
        return input
    }

    @Synchronized internal fun settle(attempt: ProtectedPivScan, outcome: OperationResult<ManagerReport>): Boolean {
        if (scan !== attempt || mutable.value.generation != attempt.generation) return false
        scan = null
        if (outcome is OperationResult.Completed && attempt.action.privateOperation && attempt.expected == null) {
            receipt = requireNotNull(attempt.observed)
            mutable.value = mutable.value.copy(awaitingPin = true, retries = receipt!!.pin.retriesRemaining ?: receipt!!.info.pin.remaining,
                erasesKeysOnFailure = receipt!!.info.pin.remaining == 1 && receipt!!.info.puk.remaining == 0)
        } else {
            retirePrivate()
            file?.fill(0); file = null
            mutable.value = mutable.value.copy(fileName = null, fileSize = null)
        }
        return true
    }

    @Synchronized fun retire(preserveDocument: Boolean = false) {
        val request = mutable.value.documentId.takeIf { preserveDocument }
        retirePrivate()
        if (!preserveDocument) { document = null; file?.fill(0); file = null; issuer = null }
        mutable.value = mutable.value.copy(documentId = request, issuerSubject = issuer?.subject,
            issuerFingerprint = issuer?.fingerprint, fileName = mutable.value.fileName.takeIf { file != null }, fileSize = file?.size)
    }

    private fun retirePrivate() {
        scan?.close(); scan = null
        profile?.close(); profile = null; receipt = null; subject = ""
        mutable.value = mutable.value.copy(generation = mutable.value.generation + 1, profileReady = false,
            awaitingPin = false, guid = null, retries = null, action = null, erasesKeysOnFailure = false)
    }
}

internal class ProtectedPivScan(
    val generation: Long, val action: ProtectedPivAction, private val identity: EnrolledPivCard, val identityLabel: String,
    private val pairing: CharArray, val expected: ProtectedPivReceipt?, private val current: (ProtectedPivScan) -> Boolean,
) : Closeable {
    private var used = false
    @Volatile private var closed = false
    var observed: ProtectedPivReceipt? = null
        private set
    val recognition get() = action.privateOperation && expected == null
    fun requireCurrent() { if (closed || !current(this)) throw CardFailure(FailureCode.CANCELLED) }
    fun execute(session: CardSession, input: OperationInput, run: (PivClient) -> ManagerReport): ManagerReport {
        synchronized(this) { requireCurrent(); if (used) throw CardFailure(FailureCode.AUTHORIZATION_DENIED); used = true }
        require(input.operation == action.operation)
        if (recognition) require(input.pin.isEmpty())
        PivSecureContext.establish(session, identity, pairing.copyOf()).use { secure ->
            requireCurrent()
            val piv = PivClient(session, secure)
            val info = piv.readInfo()
            val pin = piv.pinStatus()
            val metadata = if (action.privateOperation && action != ProtectedPivAction.VERIFY_PIN)
                piv.readPublicKeyMetadata(input.slot) else null
            if (action.privateOperation && action != ProtectedPivAction.VERIFY_PIN && metadata?.publicKey == null)
                throw CardFailure(FailureCode.UNSUPPORTED)
            val facts = ProtectedPivReceipt(info, pin, metadata)
            if (action.privateOperation && (pin.contactRequired || pin.retriesRemaining == 0 || info.pin.remaining == 0))
                throw CardFailure(if (pin.contactRequired) FailureCode.CONTACT_REQUIRED else FailureCode.PIN_BLOCKED)
            if (expected != null && !expected.matches(facts)) throw CardFailure(FailureCode.RECOVERY_REQUIRED)
            requireCurrent()
            if (recognition) {
                require(input.pin.isEmpty())
                observed = facts
                return ManagerReport(emptyList(), protectedPivIdentity = identityLabel, protectedPivRecognition = true)
            }
            return if (action == ProtectedPivAction.VERIFY_PIN) {
                piv.verifyPin(input.pin)
                ManagerReport(emptyList(), protectedPivIdentity = identityLabel)
            } else run(piv).let { report ->
                // These exports use the existing generic document exporter, without contact-file ownership.
                val statusRows = if (action == ProtectedPivAction.STATUS) listOf(ResultRow("PIN 状态", when {
                    pin.contactRequired -> "需要接触式连接恢复"
                    pin.verified -> "当前会话已验证"
                    else -> "尚未验证"
                })) + listOfNotNull(pin.retriesRemaining?.let { ResultRow("本次认证读取的 PIN 剩余重试次数", "$it 次") }) else emptyList()
                report.copy(rows = report.rows + statusRows, signatureFormat = null, sshPublicKey = false,
                    protectedPivIdentity = identityLabel)
            }
        }
    }
    override fun close() { closed = true; pairing.fill('\u0000') }
}
