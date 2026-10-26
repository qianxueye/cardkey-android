package cc.cardkey.ui

import cc.cardkey.R
import cc.cardkey.signing.SignatureFormat
import cc.cardkey.enrollment.*
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.protocol.piv.*
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Locale

enum class ManagerOperation(val title: String, val piv: Boolean = false) {
    FIDO_INVENTORY("查看通行密钥"),
    FIDO_INFO("FIDO2 · 读取"), FIDO_RESET("FIDO2 · 重置"), FIDO_RETRIES("PIN · 重试次数"),
    FIDO_SET_PIN("设置 FIDO PIN"), FIDO_CHANGE_PIN("更改 FIDO PIN"),
    FIDO_CAPACITY("凭据 · 容量"), FIDO_RPS("RP · 读取"),
    FIDO_CREDENTIALS("凭据 · 读取"), FIDO_DELETE("删除通行密钥"),
    FIDO_UPDATE("修改用户名"), FIDO_ALWAYS_UV("始终验证用户"),
    FIDO_MIN_PIN("PIN · 最短长度"), FIDO_ENTERPRISE("企业证明 · 启用"),
    FIDO_REGISTER("注册 · 验证"), FIDO_ASSERT("签名 · 验证"), FIDO_VERIFY_CREDENTIAL("验证签名"),
    U2F_INFO("U2F · 读取"), U2F_REGISTER("U2F · 注册"), U2F_ASSERT("U2F · 验证"),
    FIDO_BLOB_READ("扩展数据 · 读取"), FIDO_BLOB_WRITE("扩展数据 · 设置"),
    PIV_INFO("查看 PIV 状态", true), PIV_SLOT("查看证书", true), PIV_SSH_EXPORT("导出 SSH 公钥", true),
    PIV_CHANGE_PIN("更改 PIV PIN", true), PIV_CHANGE_PUK("更改 PUK", true),
    PIV_UNBLOCK("解锁 PIV PIN", true), PIV_ADMIN("管理密钥 · 修改", true),
    PIV_GENERATE("生成密钥", true), PIV_CSR("导出 CSR", true),
    PIV_IMPORT("导入证书", true), PIV_SIGN("文件签名", true), PIV_CMS_SIGN("CMS 文件签名", true),
    PIV_ECDH("ECDH · 验证", true), PIV_DECRYPT("RSA · 验证", true),
    PIV_ENROLL_CSR("生成 CSR", true), PIV_ENROLL_IMPORT("导入证书", true),
    PIV_RESET("PIV · 重置", true);

    val isOwnedExport get() = isFileSigning || this == PIV_SSH_EXPORT
    val isFileSigning get() = this == PIV_SIGN || this == PIV_CMS_SIGN
}

class OperationInput(
    val operation: ManagerOperation,
    val pin: CharArray = charArrayOf(), val newPin: CharArray = charArrayOf(),
    val adminKey: ByteArray = byteArrayOf(), val newAdminKey: ByteArray = byteArrayOf(),
    val managementAlgorithm: ManagementAlgorithm = ManagementAlgorithm.TDES,
    val newManagementAlgorithm: ManagementAlgorithm = ManagementAlgorithm.AES256,
    val slot: Int = 0x9a, val algorithm: KeyAlgorithm = KeyAlgorithm.P256,
    val pinPolicy: PinPolicy = PinPolicy.ONCE,
    val text: String = "", val secondary: String = "", val enabled: Boolean = false,
    val bytes: ByteArray = byteArrayOf(),
    makeExtensions: MakeExtensions = MakeExtensions(),
    val assertionExtensions: AssertionExtensions = AssertionExtensions(),
    val rpIds: List<String> = emptyList(), val enterpriseMode: Int? = null,
    val deleteBlob: Boolean = false, val displayName: String = "diagnostic",
    val enrollmentImport: EnrollmentImport? = null,
    val enrollmentSelection: EnrollmentSelection = EnrollmentSelection(),
    val enrollmentRevision: Long = 0,
    val signingFileGeneration: Long? = null,
    val signatureFormat: SignatureFormat = if (operation == ManagerOperation.PIV_CMS_SIGN) SignatureFormat.CMS else SignatureFormat.RAW,
    val inventoryGeneration: Long? = null,
    val inventorySelection: InventoryCredential? = null,
) : AutoCloseable {
    internal var protectedScan: cc.cardkey.protectedpiv.ProtectedPivScan? = null
    internal var inventoryRequest: InventoryRequest? = null
    internal var inventoryBytes: ByteArray? = null
    val makeExtensions = if (operation == ManagerOperation.FIDO_REGISTER)
        makeExtensions.copy(largeBlobKey = enabled && makeExtensions.largeBlobKey) else makeExtensions

    override fun close() {
        protectedScan?.close()
        inventoryRequest?.close()
        inventoryBytes?.fill(0)
        pin.fill('\u0000'); newPin.fill('\u0000'); adminKey.fill(0); newAdminKey.fill(0)
        makeExtensions.credentialBlob?.fill(0); assertionExtensions.hmacSalts?.fill(0)
        if (operation == ManagerOperation.FIDO_BLOB_WRITE || operation.isFileSigning) bytes.fill(0)
    }
}

data class ResultRow(val label: String, val value: String, val labelResource: Int? = null,
                     val valueResource: Int? = null, val valueArguments: List<Any> = emptyList(), val fidoModel: Boolean = false)
class SecretOutputs(val hmac: ByteArray?, val blobKey: ByteArray?, val credBlob: ByteArray? = null) : AutoCloseable {
    override fun close() { hmac?.fill(0); blobKey?.fill(0); credBlob?.fill(0) }
}
data class ManagerReport(val rows: List<ResultRow>, val export: ByteArray? = null, val fileName: String? = null,
    val secrets: SecretOutputs? = null, val protectedExport: Boolean = false, val messages: List<Int> = emptyList(), val enrollmentCsr: EnrollmentCsr? = null,
    val relyingParties: List<RelyingParty>? = null, val credentials: List<Credential>? = null,
    val inventoryGeneration: Long? = null, val protectedPivIdentity: String? = null, val signatureFormat: SignatureFormat? = null, val sshPublicKey: Boolean = false,
    val inventory: CredentialInventory? = null, val protectedPivRecognition: Boolean = false, val protectedPivSlot: Int? = null) {
    override fun toString() = "ManagerReport(rows=$rows, hasExport=${export != null}, fileName=$fileName)"
}

class ManagerOperations(private val now: () -> Long = System::currentTimeMillis,
                        private val cancelled: () -> Boolean = { false }) {
    private var diagnosticCredential: AttestationDiagnostic? = null
    private var u2fCredential: U2fRegistration? = null
    private var u2fCounter: Long? = null
    private var blobKey: ByteArray? = null
    private var secrets: SecretOutputs? = null

    fun clearSecrets() { blobKey?.fill(0); blobKey = null; secrets?.close(); secrets = null }
    fun clearSession() { clearSecrets(); diagnosticCredential = null; u2fCredential = null; u2fCounter = null }
    private fun retainSecrets(hmac: ByteArray?, key: ByteArray?, credBlob: ByteArray? = null): SecretOutputs? {
        clearSecrets()
        blobKey = key?.copyOf()
        return if (hmac != null || key != null || credBlob != null) SecretOutputs(hmac?.copyOf(), key?.copyOf(), credBlob?.copyOf()).also { secrets = it } else null
    }
    private fun verifiedBlobKey(fido: FidoClient, input: OperationInput): ByteArray {
        val proof = diagnosticCredential ?: throw CardFailure(FailureCode.INVALID_INPUT)
        diagnosticCredential = null
        val expected = blobKey?.copyOf()
        clearSecrets()
        val hash = ByteArray(32).also(SecureRandom()::nextBytes)
        val results = try {
            fido.getAssertion(GetAssertionRequest("cardkey.example", hash, listOf(proof.credentialId), requireUv = true,
                extensions = AssertionExtensions(largeBlobKey = true), userPresence = false),
                input.pin.takeIf { it.isNotEmpty() }?.copyOf())
        } catch (failure: Exception) { expected?.fill(0); throw failure }
        try {
            if (results.size != 1 || !results.single().credentialId.contentEquals(proof.credentialId))
                throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            val result = results.single()
            val verified = FidoDiagnostics.verifyAssertion(result, proof.publicKey, "cardkey.example", hash,
                proof.counter, requireUv = true, requireUp = false)
            if (!verified.signatureValid) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            if (verified.counterAdvanced == false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            val fresh = result.largeBlobKey ?: throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            if (expected != null && !java.security.MessageDigest.isEqual(expected, fresh))
                throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            if (cancelled()) throw CardFailure(FailureCode.CANCELLED)
            diagnosticCredential = proof.copy(counter = verified.counter)
            return fresh.copyOf()
        } finally {
            expected?.fill(0)
            results.forEach { it.hmacSecret?.fill(0); it.largeBlobKey?.fill(0) }
        }
    }

    fun execute(session: CardSession, input: OperationInput, approveFinalAttempt: () -> Boolean = { false }): ManagerReport {
        input.protectedScan?.let { scan -> return scan.execute(session, input) { piv -> executeWithPiv(session, input, piv, approveFinalAttempt) } }
        return executeWithPiv(session, input, PivClient(session), approveFinalAttempt)
    }

    private fun executeWithPiv(session: CardSession, input: OperationInput, piv: PivClient, approveFinalAttempt: () -> Boolean): ManagerReport {
        input.inventoryRequest?.requireCurrent()
        if (input.operation == ManagerOperation.FIDO_RESET && (session.wire != CardWire.CTAP || !session.supportsCtap2))
            throw CardFailure(FailureCode.UNSUPPORTED)
        if (input.operation !in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)) clearSecrets()
        if (input.operation.piv && session.wire == CardWire.CTAP) throw CardFailure(FailureCode.UNSUPPORTED)
        val fido = FidoClient(session)
        if (!input.operation.piv && input.operation !in setOf(ManagerOperation.U2F_INFO, ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT)) fido.select()
        fun empty() = ManagerReport(emptyList())
        fun pivRow(label: String, value: String, labelResource: Int, valueResource: Int? = null, vararg arguments: Any) =
            ResultRow(label, value, labelResource, valueResource, arguments.toList())
        return when (input.operation) {
            ManagerOperation.FIDO_INVENTORY -> ManagerReport(emptyList(), inventory = fido.inventory(input.pin, approveFinalAttempt))
            ManagerOperation.FIDO_INFO -> info(fido.getInfo())
            ManagerOperation.FIDO_RESET -> {
                clearSession()
                info(fido.reset())
            }
            ManagerOperation.FIDO_RETRIES -> fido.retries().let {
                ManagerReport(listOf(ResultRow("PIN 剩余重试次数", "${it.pin} 次")) +
                    listOfNotNull(it.uv?.let { count -> ResultRow("UV 剩余重试次数", "$count 次") },
                        it.powerCycleState?.takeIf { required -> required && session.wire == CardWire.APDU }?.let {
                            ResultRow("", "请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。")
                        }), messages = if (it.powerCycleState == true && session.wire == CardWire.CTAP)
                    listOf(R.string.error_hidPowerCycleRequired) else emptyList())
            }
            ManagerOperation.FIDO_SET_PIN -> { fido.setPin(input.newPin); empty() }
            ManagerOperation.FIDO_CHANGE_PIN -> { fido.changePin(input.pin, input.newPin); empty() }
            ManagerOperation.FIDO_CAPACITY -> fido.capacity(input.pin).let {
                ManagerReport(listOf(ResultRow("已存储凭据", "${it.existing} 个"),
                    ResultRow("剩余凭据容量（估算）", "${it.remaining} 个")))
            }
            ManagerOperation.FIDO_RPS -> fido.enumerateRelyingParties(input.pin).let { rps ->
                ManagerReport(rps.flatMap { listOf(ResultRow("RP", it.id), ResultRow("RP SHA-256", it.idHash.hex())) }, relyingParties = rps)
            }
            ManagerOperation.FIDO_CREDENTIALS -> {
                val rp = input.inventoryRequest?.rp?.value()
                val hash = rp?.idHash ?: input.text.hexBytes()
                if (rp != null && !sha256(rp.id.toByteArray(Charsets.UTF_8)).contentEquals(hash))
                    throw CardFailure(FailureCode.MALFORMED_RESPONSE)
                val credentials = fido.enumerateCredentials(input.pin, hash)
                ManagerReport(credentials.flatMap {
                    listOf(ResultRow("凭据 ID", it.id.hex()), ResultRow("用户 ID", it.user.id.hex()),
                        ResultRow("名称", it.user.name.orEmpty()), ResultRow("显示名称", it.user.displayName.orEmpty()))
                }, credentials = credentials)
            }
            ManagerOperation.FIDO_DELETE -> {
                val target = input.inventoryRequest?.credential ?: throw CardFailure(FailureCode.INVALID_INPUT)
                input.inventoryRequest?.requireCurrent()
                val id = target.value().id
                if (diagnosticCredential?.credentialId?.contentEquals(id) == true) diagnosticCredential = null
                fido.deleteCredential(input.pin, id, target.rp.value() to target.value()); empty()
            }
            ManagerOperation.FIDO_UPDATE -> {
                val target = input.inventoryRequest?.credential ?: throw CardFailure(FailureCode.INVALID_INPUT)
                input.inventoryRequest?.requireCurrent()
                val id = target.value().id
                if (diagnosticCredential?.credentialId?.contentEquals(id) == true) diagnosticCredential = null
                fido.updateUser(input.pin, id, input.secondary, target.displayName, target.rp.value() to target.value()); empty()
            }
            ManagerOperation.FIDO_ALWAYS_UV -> info(fido.setAlwaysUv(input.pin.takeIf { it.isNotEmpty() }, input.enabled))
            ManagerOperation.FIDO_MIN_PIN -> info(fido.setMinPinLength(input.pin.takeIf { it.isNotEmpty() }, input.text.toInt(), rpIds = input.rpIds, forceChange = input.enabled))
            ManagerOperation.FIDO_ENTERPRISE -> info(fido.enableEnterpriseAttestation(input.pin.takeIf { it.isNotEmpty() }))
            ManagerOperation.FIDO_REGISTER, ManagerOperation.FIDO_ASSERT -> diagnostic(fido, input)
            ManagerOperation.FIDO_VERIFY_CREDENTIAL -> {
                val request = input.inventoryRequest ?: throw CardFailure(FailureCode.INVALID_INPUT)
                selectedProof(fido, input, request, retainKey = true)
                ManagerReport(listOf(ResultRow("RP", requireNotNull(request.credential).rp.id),
                    ResultRow("凭据 ID", request.credential.idHex)))
            }
            ManagerOperation.U2F_INFO -> {
                val client = U2fClient(session); client.select()
                ManagerReport(listOf(ResultRow("支持的协议", client.version())))
            }
            ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT -> u2fDiagnostic(session, input)
            ManagerOperation.FIDO_BLOB_READ -> {
                val request = input.inventoryRequest?.takeIf { it.credential != null }
                val key = if (request != null) selectedProof(fido, input, request, retainKey = false) else verifiedBlobKey(fido, input)
                val blob = try { request?.requireCurrent(); fido.readLargeBlob(key) } finally { key.fill(0) }
                if (cancelled()) { blob?.fill(0); throw CardFailure(FailureCode.CANCELLED) }
                ManagerReport(listOfNotNull(request?.credential?.let { ResultRow("RP", it.rp.id) },
                    request?.credential?.let { ResultRow("凭据 ID", it.idHex) },
                    ResultRow("bytes", (blob?.size ?: 0).toString())), blob, "credential-blob.bin", protectedExport = true, inventoryGeneration = request?.generation)
            }
            ManagerOperation.FIDO_BLOB_WRITE -> {
                val request = input.inventoryRequest?.takeIf { it.credential != null }
                val key = if (request != null) selectedProof(fido, input, request, retainKey = false) else verifiedBlobKey(fido, input)
                try {
                    request?.requireCurrent()
                    fido.writeLargeBlob(input.pin.takeIf { it.isNotEmpty() }, key, if (input.deleteBlob) null else (input.inventoryBytes ?: input.bytes).copyOf())
                } finally { key.fill(0) }
                empty()
            }
            ManagerOperation.PIV_INFO -> piv.readInfo().let {
                ManagerReport(listOf(pivRow("piv_version", it.version, R.string.protectedPiv_rowVersion), pivRow("piv_serial", it.serial.toString(), R.string.protectedPiv_rowSerial),
                    pivRow("piv_pin_retries", "${it.pin.remaining}/${it.pin.total}", R.string.protectedPiv_rowPinRetries, R.string.protectedPiv_rowRetries, it.pin.remaining, it.pin.total),
                    pivRow("piv_puk_retries", "${it.puk.remaining}/${it.puk.total}", R.string.protectedPiv_rowPukRetries, R.string.protectedPiv_rowRetries, it.puk.remaining, it.puk.total),
                    pivRow("piv_management_key", it.managementAlgorithm.name, R.string.protectedPiv_rowManagementKey)))
            }
            ManagerOperation.PIV_SLOT -> {
                val assessedAt = now()
                val slot = piv.readSlot(input.slot)
                val cert = slot.certificate
                val rows = mutableListOf(pivRow("piv_slot", "%02X".format(slot.slot), R.string.protectedPiv_rowSlot),
                    pivRow("piv_certificate", cert?.subjectX500Principal?.name ?: "absent", R.string.protectedPiv_rowCertificate, R.string.protectedPiv_rowNoCertificate.takeIf { cert == null }))
                if (cert != null) {
                    val start = cert.notBefore.time
                    val end = cert.notAfter.time
                    val status = when {
                        start > end -> "invalid_dates" to R.string.protectedPiv_rowInvalidDates
                        assessedAt < start -> "not_yet_valid" to R.string.protectedPiv_rowNotYetValid
                        assessedAt > end -> "expired" to R.string.protectedPiv_rowExpired
                        else -> "valid" to R.string.protectedPiv_rowValid
                    }
                    rows += listOf(pivRow("piv_date_status", status.first, R.string.protectedPiv_rowDateStatus, status.second),
                        pivRow("piv_issuer", cert.issuerX500Principal.name, R.string.protectedPiv_rowIssuer),
                        pivRow("piv_certificate_serial", cert.serialNumber.toString(16).uppercase(Locale.ROOT), R.string.protectedPiv_rowCertificateSerial),
                        pivRow("piv_not_before", Instant.ofEpochMilli(start).toString(), R.string.protectedPiv_rowNotBefore),
                        pivRow("piv_not_after", Instant.ofEpochMilli(end).toString(), R.string.protectedPiv_rowNotAfter),
                        pivRow("piv_assessed_at", Instant.ofEpochMilli(assessedAt).toString(), R.string.protectedPiv_rowAssessedAt))
                }
                rows += listOfNotNull(slot.metadata?.algorithm?.let { pivRow("piv_algorithm", it.name, R.string.protectedPiv_rowAlgorithm) },
                    slot.metadata?.let { pivRow("piv_pin_policy",
                        listOf("none", "once", "always")[it.pinPolicy - 1], R.string.protectedPiv_rowPinPolicy,
                        listOf(R.string.protectedPiv_rowNoPin, R.string.protectedPiv_rowPinOnce, R.string.protectedPiv_rowPinAlways)[it.pinPolicy - 1]) },
                    slot.metadata?.let { pivRow("piv_touch_policy",
                        listOf("none", "always", "cached")[it.touchPolicy - 1], R.string.protectedPiv_rowTouchPolicy,
                        listOf(R.string.protectedPiv_rowNoTouch, R.string.protectedPiv_rowTouchAlways, R.string.protectedPiv_rowTouchCached)[it.touchPolicy - 1]) },
                    slot.metadata?.publicKey?.let { pivRow("piv_public_key", Base64.getEncoder().encodeToString(it.encoded), R.string.protectedPiv_rowPublicKey) })
                ManagerReport(rows, cert?.encoded, "piv-${input.slot.toString(16)}.cer")
            }
            ManagerOperation.PIV_SSH_EXPORT -> {
                val metadata = piv.readPublicKeyMetadata(input.slot)
                val publicKey = cc.cardkey.ssh.PivSshPublicKey.export(metadata)
                if (cancelled()) throw CardFailure(FailureCode.CANCELLED)
                ManagerReport(emptyList(), publicKey, "piv-${input.slot.toString(16)}-ssh.pub",
                    messages = listOf(R.string.ssh_publicKeyScope), sshPublicKey = true)
            }
            ManagerOperation.PIV_CHANGE_PIN -> { piv.changePin(input.pin, input.newPin); empty() }
            ManagerOperation.PIV_CHANGE_PUK -> { piv.changePuk(input.pin, input.newPin); empty() }
            ManagerOperation.PIV_UNBLOCK -> { piv.unblockPin(input.pin, input.newPin); empty() }
            ManagerOperation.PIV_ADMIN -> {
                piv.changeManagementKey(input.managementAlgorithm, input.adminKey, input.newManagementAlgorithm, input.newAdminKey); empty()
            }
            ManagerOperation.PIV_GENERATE -> piv.generateKey(input.slot, input.algorithm, input.pinPolicy,
                input.managementAlgorithm, input.adminKey).let {
                ManagerReport(listOfNotNull(it.algorithm?.let { algorithm -> ResultRow("算法", algorithm.name) },
                    ResultRow("PIN 验证策略", listOf("无需 PIN", "会话内验证一次", "每次操作验证")[it.pinPolicy - 1]),
                    ResultRow("触摸确认策略", listOf("无需触摸", "每次操作触摸", "允许缓存触摸确认")[it.touchPolicy - 1]),
                    it.publicKey?.let { key -> ResultRow("公钥", Base64.getEncoder().encodeToString(key.encoded)) }))
            }
            ManagerOperation.PIV_CSR -> piv.createCsr(input.slot, input.pin, input.text).let {
                ManagerReport(listOf(pivRow("CSR", input.text, R.string.protectedPiv_rowCsr)), pem("CERTIFICATE REQUEST", it), "piv-${input.slot.toString(16)}.csr")
            }
            ManagerOperation.PIV_ENROLL_CSR -> {
                val selection = input.enrollmentSelection
                val csr = EnrollmentCsr(piv.createCsr(selection.slot, input.pin, EnrollmentProfile.name(input.text), selection.algorithm), input.text, selection)
                ManagerReport(emptyList(), enrollmentCsr = csr)
            }
            ManagerOperation.PIV_ENROLL_IMPORT -> {
                val request = requireNotNull(input.enrollmentImport)
                request.validate()
                piv.importCertificate(request.csr.selection.slot, request.bundle.leaf, input.managementAlgorithm, input.adminKey)
                ManagerReport(emptyList(), messages = listOf(R.string.enrollment_imported))
            }
            ManagerOperation.PIV_IMPORT -> piv.importCertificate(input.slot, input.bytes, input.managementAlgorithm, input.adminKey).let {
                ManagerReport(listOf(ResultRow("证书", it.subjectX500Principal.name)))
            }
            ManagerOperation.PIV_SIGN, ManagerOperation.PIV_CMS_SIGN -> {
                val signature = when (input.signatureFormat) {
                    SignatureFormat.RAW -> piv.signFile(input.slot, input.pin, input.bytes)
                    SignatureFormat.CMS -> piv.signCmsFile(input.slot, input.pin, input.bytes)
                }
                if (cancelled()) { signature.fill(0); throw CardFailure(FailureCode.CANCELLED) }
                ManagerReport(emptyList(), signature, input.signatureFormat.fileName,
                    messages = if (input.signatureFormat == SignatureFormat.RAW) listOf(R.string.signing_verified, R.string.signing_exportScope) else
                        listOf(R.string.signing_cmsVerified, R.string.signing_cmsScope),
                    signatureFormat = input.signatureFormat)
            }
            ManagerOperation.PIV_ECDH -> { piv.verifyKeyAgreement(input.slot, input.pin); empty() }
            ManagerOperation.PIV_DECRYPT -> { piv.verifyRsaDecryption(input.slot, input.pin); empty() }
            ManagerOperation.PIV_RESET -> { piv.reset(); empty() }
        }
    }

    private fun selectedProof(fido: FidoClient, input: OperationInput, request: InventoryRequest, retainKey: Boolean): ByteArray {
        request.requireCurrent()
        val selected = request.credential ?: throw CardFailure(FailureCode.INVALID_INPUT)
        val credential = selected.value()
        require(input.pin.isNotEmpty())
        if (!sha256(selected.rp.id.toByteArray(Charsets.UTF_8)).contentEquals(selected.rp.value().idHash))
            throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        FidoDiagnostics.publicKey(credential.publicKey)
        if (!retainKey && request.expectedKey == null) throw CardFailure(FailureCode.INVALID_INPUT)
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val results = fido.getAssertion(GetAssertionRequest(selected.rp.id, challenge, listOf(credential.id),
            requireUv = true, extensions = AssertionExtensions(largeBlobKey = !retainKey || input.assertionExtensions.largeBlobKey), userPresence = true), input.pin.copyOf())
        try {
            if (results.size != 1 || !results.single().credentialId.contentEquals(credential.id))
                throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            val result = results.single()
            val verified = FidoDiagnostics.verifyAssertion(result, credential.publicKey, selected.rp.id,
                challenge, request.previousCounter, requireUv = true, requireUp = true)
            if (!verified.signatureValid) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            if (verified.counterAdvanced == false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            if (cancelled()) throw CardFailure(FailureCode.CANCELLED)
            request.requireCurrent()
            request.verified(verified.counter, null)
            val fresh = result.largeBlobKey
            if (!retainKey && (fresh == null || !java.security.MessageDigest.isEqual(request.expectedKey, fresh)))
                throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            request.verified(verified.counter, fresh.takeIf { retainKey })
            return if (retainKey) byteArrayOf() else requireNotNull(fresh).copyOf()
        } finally {
            challenge.fill(0)
            results.forEach { it.hmacSecret?.fill(0); it.largeBlobKey?.fill(0) }
        }
    }

    private fun info(info: FidoInfo) = ManagerReport(
        listOf(ResultRow("AAGUID", aaguid(info)),
            ResultRow("关联型号", aaguid(info), labelResource = R.string.fido_associatedModel, fidoModel = true),
            ResultRow("支持的协议", info.versions.joinToString()),
            ResultRow("支持的扩展", info.extensions.joinToString().ifEmpty { "无" }),
            ResultRow("PIN · 协议", info.pinProtocols.joinToString())) +
            listOfNotNull(
                info.algorithms?.let { parameters -> ResultRow("算法", parameters.joinToString { parameter ->
                    if (parameter.type == "public-key") parameter.algorithm.toString()
                    else "${parameter.type}: ${parameter.algorithm}"
                }) },
                info.forcePinChange?.let { ResultRow("需要修改 PIN", if (it) "是" else "否") },
                info.options["clientPin"]?.let { ResultRow("PIN", if (it) "已设置" else "未设置") },
                info.options["alwaysUv"]?.let { ResultRow("始终验证", if (it) "开启" else "关闭") },
                info.options["ep"]?.let { ResultRow("企业证明", if (it) "开启" else "关闭") },
                info.raw[13]?.let { ResultRow("最短 PIN 长度", "${it.number()} 个字符") }))

    private fun aaguid(info: FidoInfo): String {
        val bytes = requireNotNull(info.raw[3]).bytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return java.util.UUID(buffer.long, buffer.long).toString()
    }

    private fun diagnostic(fido: FidoClient, input: OperationInput): ManagerReport {
        val rpId = "cardkey.example"
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(challenge)
        val pin = input.pin.takeIf { it.isNotEmpty() }
        if (input.operation == ManagerOperation.FIDO_REGISTER) {
            val user = FidoUser(ByteArray(32).also(SecureRandom()::nextBytes), input.displayName, input.displayName)
            val result = fido.makeCredential(MakeCredentialRequest(rpId, input.displayName, user, hash,
                residentKey = input.enabled, requireUv = pin != null, extensions = input.makeExtensions, enterpriseAttestation = input.enterpriseMode), pin)
            try {
                val proof = FidoDiagnostics.verifyRegistration(result, rpId, hash, requireUv = pin != null)
                if (proof.signatureValid == false) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
                val extensionRows = publicExtensions(AuthenticatorData.parse(result.authData), input.makeExtensions.uvm)
                diagnosticCredential = proof
                return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("凭据 ID", proof.credentialId.hex()),
                    ResultRow("证明", proof.kind.name), ResultRow("计数", proof.counter.toString())) +
                    extensionRows, secrets = retainSecrets(null, result.largeBlobKey))
            } finally { result.largeBlobKey?.fill(0) }
        }
        val proof = diagnosticCredential ?: throw CardFailure(FailureCode.INVALID_INPUT)
        val results = fido.getAssertion(GetAssertionRequest(rpId, hash, listOf(proof.credentialId), requireUv = pin != null,
            extensions = input.assertionExtensions), pin)
        var credentialBlob: ByteArray? = null
        try {
            require(results.size == 1 && results.single().credentialId.contentEquals(proof.credentialId))
            val verified = FidoDiagnostics.verifyAssertion(results.single(), proof.publicKey, rpId, hash, proof.counter, requireUv = pin != null)
            if (!verified.signatureValid) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            if (verified.counterAdvanced == false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            val auth = AuthenticatorData.parse(results.single().authData)
            credentialBlob = (auth.extensions?.get("credBlob") as? Cbor.Bytes)?.value
            val extensionRows = publicExtensions(auth, input.assertionExtensions.uvm)
            diagnosticCredential = proof.copy(counter = verified.counter)
            return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("计数", verified.counter.toString())) +
                extensionRows, secrets = retainSecrets(results.single().hmacSecret, results.single().largeBlobKey, credentialBlob))
        } finally {
            credentialBlob?.fill(0)
            results.forEach {
                it.hmacSecret?.fill(0); it.largeBlobKey?.fill(0)
                if (input.assertionExtensions.credentialBlob) it.authData.fill(0)
            }
        }
    }

    private fun publicExtensions(auth: AuthenticatorData, requestedUvm: Boolean): List<ResultRow> {
        val uvm = cc.cardkey.provider.uvmOutput(auth, requestedUvm)
        val extensions = auth.extensions ?: return emptyList()
        return listOfNotNull(extensions["credProtect"]?.let { ResultRow("credProtect", it.number().toString()) },
            extensions["minPinLength"]?.let { ResultRow("最短 PIN 长度", "${it.number()} 个字符") },
            (extensions["credBlob"] as? Cbor.Bool)?.let { ResultRow("credBlob", it.value.toString()) }, uvm?.let { rows -> ResultRow("用户验证方法", rows.joinToString("; ") { it.joinToString() }) })
    }

    private fun u2fDiagnostic(session: CardSession, input: OperationInput): ManagerReport {
        val client = U2fClient(session); client.select()
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val application = java.security.MessageDigest.getInstance("SHA-256").digest("https://cardkey.example".toByteArray())
        if (input.operation == ManagerOperation.U2F_REGISTER) {
            val result = client.register(challenge, application)
            if (!result.signatureValid) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            u2fCredential = result; u2fCounter = null
            return ManagerReport(listOf(ResultRow("RP", "cardkey.example"), ResultRow("凭据 ID", result.keyHandle.hex())))
        }
        val registration = u2fCredential ?: throw CardFailure(FailureCode.INVALID_INPUT)
        val result = client.authenticate(challenge, application, registration.keyHandle, registration.publicKey, u2fCounter)
        if (!result.signatureValid) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        if (result.counterAdvanced == false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        u2fCounter = result.counter
        return ManagerReport(listOf(ResultRow("RP", "cardkey.example"), ResultRow("计数", result.counter.toString())))
    }

    companion object {
        fun pem(type: String, der: ByteArray): ByteArray =
            ("-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der) + "\n-----END $type-----\n").toByteArray()
    }
}

internal fun parseDisclosureRps(text: String): List<String> = text.lineSequence().map { it.trim() }
    .filter { it.isNotEmpty() }.toList().also { require(it.size <= 2 && it.distinct().size == it.size) }
