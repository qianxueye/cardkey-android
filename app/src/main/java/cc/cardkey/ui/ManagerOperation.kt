package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.protocol.piv.*
import java.security.SecureRandom
import java.util.Base64

enum class ManagerOperation(val title: String, val piv: Boolean = false) {
    FIDO_INFO("FIDO2 · 读取"), FIDO_RETRIES("PIN · 重试次数"),
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
    val makeExtensions: MakeExtensions = MakeExtensions(),
    val assertionExtensions: AssertionExtensions = AssertionExtensions(),
) : AutoCloseable {
    override fun close() {
        pin.fill('\u0000'); newPin.fill('\u0000'); adminKey.fill(0); newAdminKey.fill(0)
        makeExtensions.credentialBlob?.fill(0); assertionExtensions.hmacSalts?.fill(0)
    }
}

data class ResultRow(val label: String, val value: String)
data class ManagerReport(val rows: List<ResultRow>, val export: ByteArray? = null, val fileName: String? = null)

class ManagerOperations {
    private var diagnosticCredential: AttestationDiagnostic? = null
    private var u2fCredential: U2fRegistration? = null
    private var u2fCounter: Long? = null

    fun execute(session: CardSession, input: OperationInput): ManagerReport {
        val fido = FidoClient(session)
        val piv = PivClient(session)
        if (!input.operation.piv) fido.select()
        fun empty() = ManagerReport(emptyList())
        return when (input.operation) {
            ManagerOperation.FIDO_INFO -> info(fido.getInfo())
            ManagerOperation.FIDO_RETRIES -> fido.retries().let {
                ManagerReport(listOf(ResultRow("PIN", it.pin.toString())) +
                    listOfNotNull(it.uv?.let { count -> ResultRow("UV", count.toString()) }))
            }
            ManagerOperation.FIDO_SET_PIN -> { fido.setPin(input.newPin); empty() }
            ManagerOperation.FIDO_CHANGE_PIN -> { fido.changePin(input.pin, input.newPin); empty() }
            ManagerOperation.FIDO_CAPACITY -> fido.capacity(input.pin).let {
                ManagerReport(listOf(ResultRow("已用", it.existing.toString()), ResultRow("剩余", it.remaining.toString())))
            }
            ManagerOperation.FIDO_RPS -> ManagerReport(fido.enumerateRelyingParties(input.pin).flatMap {
                listOf(ResultRow("RP", it.id), ResultRow("RP SHA-256", it.idHash.hex()))
            })
            ManagerOperation.FIDO_CREDENTIALS -> ManagerReport(fido.enumerateCredentials(input.pin, input.text.hexBytes()).flatMap {
                listOf(ResultRow("凭据 ID", it.id.hex()), ResultRow("用户 ID", it.user.id.hex()),
                    ResultRow("名称", it.user.name.orEmpty()), ResultRow("显示名称", it.user.displayName.orEmpty()))
            })
            ManagerOperation.FIDO_DELETE -> { fido.deleteCredential(input.pin, input.text.hexBytes()); empty() }
            ManagerOperation.FIDO_UPDATE -> {
                fido.updateUser(input.pin, input.text.hexBytes(), input.secondary, input.secondary); empty()
            }
            ManagerOperation.FIDO_ALWAYS_UV -> info(fido.setAlwaysUv(input.pin.takeIf { it.isNotEmpty() }, input.enabled))
            ManagerOperation.FIDO_MIN_PIN -> info(fido.setMinPinLength(input.pin.takeIf { it.isNotEmpty() }, input.text.toInt(), forceChange = input.enabled))
            ManagerOperation.FIDO_ENTERPRISE -> info(fido.enableEnterpriseAttestation(input.pin.takeIf { it.isNotEmpty() }))
            ManagerOperation.FIDO_REGISTER, ManagerOperation.FIDO_ASSERT -> diagnostic(fido, input)
            ManagerOperation.U2F_INFO -> {
                val client = U2fClient(session); client.select()
                ManagerReport(listOf(ResultRow("版本", client.version())))
            }
            ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT -> u2fDiagnostic(session, input)
            ManagerOperation.FIDO_BLOB_READ -> {
                val blob = fido.readLargeBlob(input.adminKey)
                ManagerReport(listOf(ResultRow("bytes", (blob?.size ?: 0).toString())), blob, "cardkey-blob.bin")
            }
            ManagerOperation.FIDO_BLOB_WRITE -> {
                fido.writeLargeBlob(input.pin.takeIf { it.isNotEmpty() }, input.adminKey, input.bytes.copyOf()); empty()
            }
            ManagerOperation.PIV_INFO -> piv.readInfo().let {
                ManagerReport(listOf(ResultRow("版本", it.version), ResultRow("序列号", it.serial.toString()),
                    ResultRow("PIN · 重试次数", "${it.pin.remaining} / ${it.pin.total}"),
                    ResultRow("PUK · 重试次数", "${it.puk.remaining} / ${it.puk.total}"),
                    ResultRow("管理密钥", it.managementAlgorithm.name)))
            }
            ManagerOperation.PIV_SLOT -> piv.readSlot(input.slot).let {
                ManagerReport(listOfNotNull(it.metadata?.let { metadata -> ResultRow("算法", metadata.algorithm.name) },
                    it.certificate?.let { cert -> ResultRow("证书", cert.subjectX500Principal.name) },
                    it.metadata?.let { metadata -> ResultRow("公钥", Base64.getEncoder().encodeToString(metadata.publicKey.encoded)) }),
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
                ManagerReport(listOf(ResultRow("算法", it.algorithm.name), ResultRow("公钥", Base64.getEncoder().encodeToString(it.publicKey.encoded))))
            }
            ManagerOperation.PIV_CSR -> piv.createCsr(input.slot, input.pin, input.text).let {
                ManagerReport(listOf(ResultRow("CSR", input.text)), pem("CERTIFICATE REQUEST", it), "piv-${input.slot.toString(16)}.csr")
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
        listOf(ResultRow("版本", info.versions.joinToString()), ResultRow("扩展", info.extensions.joinToString()),
            ResultRow("PIN · 最短长度", info.minPinLength.toString()),
            ResultRow("PIN · 协议", info.pinProtocols.joinToString())) +
            info.options.map { ResultRow(it.key, it.value.toString()) })

    private fun diagnostic(fido: FidoClient, input: OperationInput): ManagerReport {
        val rpId = "cardkey.example"
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(challenge)
        val pin = input.pin.takeIf { it.isNotEmpty() }
        if (input.operation == ManagerOperation.FIDO_REGISTER) {
            val user = FidoUser(ByteArray(32).also(SecureRandom()::nextBytes), "CardKey", "CardKey")
            val result = fido.makeCredential(MakeCredentialRequest(rpId, "CardKey", user, hash,
                residentKey = input.enabled, requireUv = pin != null, extensions = input.makeExtensions), pin)
            val proof = FidoDiagnostics.verifyRegistration(result, rpId, hash, requireUv = pin != null)
            if (proof.signatureValid == false) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
            diagnosticCredential = proof
            return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("凭据 ID", proof.credentialId.hex()),
                ResultRow("证明", proof.kind.name), ResultRow("计数", proof.counter.toString())) +
                listOfNotNull(result.largeBlobKey?.let { ResultRow("largeBlobKey", it.hex()) }))
        }
        val proof = diagnosticCredential ?: throw CardFailure(FailureCode.INVALID_INPUT)
        val results = fido.getAssertion(GetAssertionRequest(rpId, hash, listOf(proof.credentialId), requireUv = pin != null,
            extensions = input.assertionExtensions), pin)
        require(results.size == 1 && results.single().credentialId.contentEquals(proof.credentialId))
        val verified = FidoDiagnostics.verifyAssertion(results.single(), proof.publicKey, rpId, hash, proof.counter, requireUv = pin != null)
        if (!verified.signatureValid) throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        if (verified.counterAdvanced == false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        diagnosticCredential = proof.copy(counter = verified.counter)
        return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("计数", verified.counter.toString())) +
            listOfNotNull(results.single().hmacSecret?.let { ResultRow("hmac-secret", it.hex()) },
                results.single().largeBlobKey?.let { ResultRow("largeBlobKey", it.hex()) }))
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
