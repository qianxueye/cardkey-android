package cc.cardkey.ui

import cc.cardkey.R
import cc.cardkey.enrollment.*
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.protocol.piv.*
import java.security.SecureRandom
import java.util.Base64

enum class ManagerOperation(val title: String, val piv: Boolean = false) {
    FIDO_INFO("FIDO2 · 读取"), FIDO_RESET("FIDO2 · 重置"), FIDO_RETRIES("PIN · 重试次数"),
    FIDO_SET_PIN("PIN · 设置"), FIDO_CHANGE_PIN("PIN · 修改"),
    FIDO_CAPACITY("凭据 · 容量"), FIDO_RPS("RP · 读取"),
    FIDO_CREDENTIALS("凭据 · 读取"), FIDO_DELETE("凭据 · 删除"),
    FIDO_UPDATE("凭据 · 修改"), FIDO_ALWAYS_UV("始终验证用户"),
    FIDO_MIN_PIN("PIN · 最短长度"), FIDO_ENTERPRISE("企业证明 · 启用"),
    FIDO_REGISTER("注册 · 验证"), FIDO_ASSERT("签名 · 验证"),
    U2F_INFO("U2F · 读取"), U2F_REGISTER("U2F · 注册"), U2F_ASSERT("U2F · 验证"),
    FIDO_BLOB_READ("扩展数据 · 读取"), FIDO_BLOB_WRITE("扩展数据 · 设置"),
    PIV_INFO("PIV · 读取", true), PIV_SLOT("证书 · 读取", true),
    PIV_CHANGE_PIN("PIN · 修改", true), PIV_CHANGE_PUK("PUK · 修改", true),
    PIV_UNBLOCK("PIN · 解锁", true), PIV_ADMIN("管理密钥 · 修改", true),
    PIV_GENERATE("生成密钥", true), PIV_CSR("导出 CSR", true),
    PIV_IMPORT("导入证书", true), PIV_SIGN("验证签名", true),
    PIV_ECDH("ECDH · 验证", true), PIV_DECRYPT("RSA · 验证", true),
    PIV_ENROLL_CSR("生成 CSR", true), PIV_ENROLL_IMPORT("导入证书", true),
    PIV_RESET("PIV · 重置", true)
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
) : AutoCloseable {
    val makeExtensions = if (operation == ManagerOperation.FIDO_REGISTER)
        makeExtensions.copy(largeBlobKey = enabled && makeExtensions.largeBlobKey) else makeExtensions

    override fun close() {
        pin.fill('\u0000'); newPin.fill('\u0000'); adminKey.fill(0); newAdminKey.fill(0)
        makeExtensions.credentialBlob?.fill(0); assertionExtensions.hmacSalts?.fill(0)
        if (operation == ManagerOperation.FIDO_BLOB_WRITE) bytes.fill(0)
    }
}

data class ResultRow(val label: String, val value: String)
class SecretOutputs(val hmac: ByteArray?, val blobKey: ByteArray?, val credBlob: ByteArray? = null) : AutoCloseable {
    override fun close() { hmac?.fill(0); blobKey?.fill(0); credBlob?.fill(0) }
}
data class ManagerReport(val rows: List<ResultRow>, val export: ByteArray? = null, val fileName: String? = null,
    val secrets: SecretOutputs? = null, val protectedExport: Boolean = false, val messages: List<Int> = emptyList(), val enrollmentCsr: EnrollmentCsr? = null) {
    override fun toString() = "ManagerReport(rows=$rows, hasExport=${export != null}, fileName=$fileName)"
}

class ManagerOperations(private val cancelled: () -> Boolean = { false }) {
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

    fun execute(session: CardSession, input: OperationInput): ManagerReport {
        if (input.operation == ManagerOperation.FIDO_RESET && (session.wire != CardWire.CTAP || !session.supportsCtap2))
            throw CardFailure(FailureCode.UNSUPPORTED)
        if (input.operation !in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)) clearSecrets()
        if (input.operation.piv && session.wire == CardWire.CTAP) throw CardFailure(FailureCode.UNSUPPORTED)
        val fido = FidoClient(session)
        val piv = PivClient(session)
        if (!input.operation.piv && input.operation !in setOf(ManagerOperation.U2F_INFO, ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT)) fido.select()
        fun empty() = ManagerReport(emptyList())
        return when (input.operation) {
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
            ManagerOperation.FIDO_RPS -> ManagerReport(fido.enumerateRelyingParties(input.pin).flatMap {
                listOf(ResultRow("RP", it.id), ResultRow("RP SHA-256", it.idHash.hex()))
            })
            ManagerOperation.FIDO_CREDENTIALS -> ManagerReport(fido.enumerateCredentials(input.pin, input.text.hexBytes()).flatMap {
                listOf(ResultRow("凭据 ID", it.id.hex()), ResultRow("用户 ID", it.user.id.hex()),
                    ResultRow("名称", it.user.name.orEmpty()), ResultRow("显示名称", it.user.displayName.orEmpty()))
            })
            ManagerOperation.FIDO_DELETE -> {
                val id = input.text.hexBytes()
                if (diagnosticCredential?.credentialId?.contentEquals(id) == true) diagnosticCredential = null
                fido.deleteCredential(input.pin, id); empty()
            }
            ManagerOperation.FIDO_UPDATE -> {
                val id = input.text.hexBytes()
                if (diagnosticCredential?.credentialId?.contentEquals(id) == true) diagnosticCredential = null
                fido.updateUser(input.pin, id, input.secondary, null); empty()
            }
            ManagerOperation.FIDO_ALWAYS_UV -> info(fido.setAlwaysUv(input.pin.takeIf { it.isNotEmpty() }, input.enabled))
            ManagerOperation.FIDO_MIN_PIN -> info(fido.setMinPinLength(input.pin.takeIf { it.isNotEmpty() }, input.text.toInt(), rpIds = input.rpIds, forceChange = input.enabled))
            ManagerOperation.FIDO_ENTERPRISE -> info(fido.enableEnterpriseAttestation(input.pin.takeIf { it.isNotEmpty() }))
            ManagerOperation.FIDO_REGISTER, ManagerOperation.FIDO_ASSERT -> diagnostic(fido, input)
            ManagerOperation.U2F_INFO -> {
                val client = U2fClient(session); client.select()
                ManagerReport(listOf(ResultRow("支持的协议", client.version())))
            }
            ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT -> u2fDiagnostic(session, input)
            ManagerOperation.FIDO_BLOB_READ -> {
                val blob = fido.readLargeBlob(verifiedBlobKey(fido, input))
                ManagerReport(listOf(ResultRow("bytes", (blob?.size ?: 0).toString())), blob, "credential-blob.bin", protectedExport = true)
            }
            ManagerOperation.FIDO_BLOB_WRITE -> {
                fido.writeLargeBlob(input.pin.takeIf { it.isNotEmpty() }, verifiedBlobKey(fido, input), if (input.deleteBlob) null else input.bytes.copyOf()); empty()
            }
            ManagerOperation.PIV_INFO -> piv.readInfo().let {
                ManagerReport(listOf(ResultRow("版本", it.version), ResultRow("序列号", it.serial.toString()),
                    ResultRow("PIN 剩余重试次数", "剩余 ${it.pin.remaining} 次（共 ${it.pin.total} 次）"),
                    ResultRow("PUK 剩余重试次数", "剩余 ${it.puk.remaining} 次（共 ${it.puk.total} 次）"),
                    ResultRow("管理密钥", it.managementAlgorithm.name)))
            }
            ManagerOperation.PIV_SLOT -> piv.readSlot(input.slot).let {
                ManagerReport(listOfNotNull(it.metadata?.algorithm?.let { algorithm -> ResultRow("算法", algorithm.name) },
                    it.certificate?.let { cert -> ResultRow("证书", cert.subjectX500Principal.name) },
                    it.metadata?.publicKey?.let { key -> ResultRow("公钥", Base64.getEncoder().encodeToString(key.encoded)) }),
                    it.certificate?.encoded, "piv-${input.slot.toString(16)}.cer")
            }
            ManagerOperation.PIV_CHANGE_PIN -> { piv.changePin(input.pin, input.newPin); empty() }
            ManagerOperation.PIV_CHANGE_PUK -> { piv.changePuk(input.pin, input.newPin); empty() }
            ManagerOperation.PIV_UNBLOCK -> { piv.unblockPin(input.pin, input.newPin); empty() }
            ManagerOperation.PIV_ADMIN -> {
                piv.changeManagementKey(input.managementAlgorithm, input.adminKey, input.newManagementAlgorithm, input.newAdminKey); empty()
            }
            ManagerOperation.PIV_GENERATE -> piv.generateKey(input.slot, input.algorithm, input.pinPolicy,
                input.managementAlgorithm, input.adminKey).let {
                ManagerReport(listOfNotNull(it.algorithm?.let { algorithm -> ResultRow("算法", algorithm.name) }, it.publicKey?.let { key -> ResultRow("公钥", Base64.getEncoder().encodeToString(key.encoded)) }))
            }
            ManagerOperation.PIV_CSR -> piv.createCsr(input.slot, input.pin, input.text).let {
                ManagerReport(listOf(ResultRow("CSR", input.text)), pem("CERTIFICATE REQUEST", it), "piv-${input.slot.toString(16)}.csr")
            }
            ManagerOperation.PIV_ENROLL_CSR -> {
                val csr = EnrollmentCsr(piv.createCsr(0x9a, input.pin, EnrollmentProfile.name(input.text), KeyAlgorithm.P256), input.text)
                ManagerReport(emptyList(), enrollmentCsr = csr)
            }
            ManagerOperation.PIV_ENROLL_IMPORT -> {
                val request = requireNotNull(input.enrollmentImport)
                request.validate()
                piv.importCertificate(0x9a, request.bundle.leaf, input.managementAlgorithm, input.adminKey)
                ManagerReport(emptyList(), messages = listOf(R.string.enrollment_imported))
            }
            ManagerOperation.PIV_IMPORT -> piv.importCertificate(input.slot, input.bytes, input.managementAlgorithm, input.adminKey).let {
                ManagerReport(listOf(ResultRow("证书", it.subjectX500Principal.name)))
            }
            ManagerOperation.PIV_SIGN -> { piv.verifySignature(input.slot, input.pin); empty() }
            ManagerOperation.PIV_ECDH -> { piv.verifyKeyAgreement(input.slot, input.pin); empty() }
            ManagerOperation.PIV_DECRYPT -> { piv.verifyRsaDecryption(input.slot, input.pin); empty() }
            ManagerOperation.PIV_RESET -> { piv.reset(); empty() }
        }
    }

    private fun info(info: FidoInfo) = ManagerReport(
        listOf(ResultRow("支持的协议", info.versions.joinToString()),
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
