package cc.cardkey.protocol.piv

import cc.cardkey.protocol.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder

public enum class PinPolicy(val code: Int) { NEVER(1), ONCE(2), ALWAYS(3) }

public enum class KeyAlgorithm(val code: Int, val curve: String?, val hash: String, val signature: String) {
    RSA2048(0x07, null, "SHA-256", "SHA256withRSA"),
    P256(0x11, "secp256r1", "SHA-256", "SHA256withECDSA"),
    P384(0x14, "secp384r1", "SHA-384", "SHA384withECDSA");
    companion object { fun from(reference: Int) = entries.firstOrNull { it.code == reference }
        ?: throw CardFailure(FailureCode.UNSUPPORTED) }
}

public enum class ManagementAlgorithm(val code: Int, val keySize: Int, val blockSize: Int, val cipher: String) {
    TDES(0x03, 24, 8, "DESede"), AES128(0x08, 16, 16, "AES"),
    AES192(0x0a, 24, 16, "AES"), AES256(0x0c, 32, 16, "AES")
}

public data class RetryMetadata(val defaultValue: Boolean, val total: Int, val remaining: Int)
public data class PivInfo(val version: String, val serial: Long, val pin: RetryMetadata, val puk: RetryMetadata,
                         val managementAlgorithm: ManagementAlgorithm, val managementDefault: Boolean)
public data class SlotMetadata(val algorithm: KeyAlgorithm, val pinPolicy: Int, val touchPolicy: Int,
                              val generated: Boolean, val publicKey: PublicKey?)
public data class PivPinStatus(val verified: Boolean, val retriesRemaining: Int?, val contactRequired: Boolean)
public data class PivSlot(val slot: Int, val metadata: SlotMetadata?, val certificate: X509Certificate?)

public class PivClient(private val session: CardSession, private val secure: PivSecureContext? = null) {
    private val random = SecureRandom()

    fun readInfo(): PivInfo = validateResponse {
        select()
        val version = send(Apdu(ins = 0xfd)).also { if (it.size != 3) malformed() }
        val serial = send(Apdu(ins = 0xf8)).also { if (it.size != 4) malformed() }
        val admin = metadata(0x9b)
        val algorithm = ManagementAlgorithm.entries.firstOrNull { it.code == byte(admin, 1) }
            ?: throw CardFailure(FailureCode.UNSUPPORTED)
        PivInfo(version.joinToString(".") { (it.toInt() and 255).toString() },
            BigInteger(1, serial).toLong(), retries(0x80), retries(0x81), algorithm, flag(admin, 5))
    }

    fun readSlot(slot: Int): PivSlot = validateResponse {
        requireSlot(slot)
        select()
        PivSlot(slot, slotMetadataOrNull(slot), certificate(slot))
    }

    fun verifyPin(pin: CharArray) {
        return withPin(pin) { encoded ->
            select()
            verifyEncoded(encoded)
        }
    }

    fun pinStatus(): PivPinStatus = validateResponse {
        if (secure == null) session.requireContact() else secure.requireCurrent(session)
        select()
        val response = exchange(Apdu(ins = 0x20, p2 = 0x80, le = null))
        if (response.data.isNotEmpty()) malformed()
        when {
            response.status == 0x9000 -> PivPinStatus(true, null, false)
            response.status and 0xfff0 == 0x63c0 -> PivPinStatus(false, response.status and 15, false)
            response.status == 0x6983 -> PivPinStatus(false, null, session.medium == Medium.NFC)
            else -> { response.requireSuccess(); malformed() }
        }
    }

    fun changePin(oldPin: CharArray, newPin: CharArray) = changeReference(0x24, 0x80, oldPin, newPin)
    fun changePuk(oldPuk: CharArray, newPuk: CharArray) = changeReference(0x24, 0x81, oldPuk, newPuk)
    fun unblockPin(puk: CharArray, newPin: CharArray) = changeReference(0x2c, 0x80, puk, newPin)

    private fun changeReference(ins: Int, reference: Int, old: CharArray, new: CharArray) {
        var encodedOld = byteArrayOf()
        var encodedNew = byteArrayOf()
        var data = byteArrayOf()
        try {
            session.requireContact()
            encodedOld = secret(old)
            encodedNew = secret(new)
            data = encodedOld + encodedNew
            select()
            send(Apdu(ins = ins, p2 = reference, data = data, le = null), true)
            session.applied()
            if (reference == 0x80) verifyEncoded(encodedNew)
            else if (retries(0x81).remaining == 0) malformed()
        } finally {
            old.fill('\u0000'); new.fill('\u0000')
            encodedOld.fill(0); encodedNew.fill(0); data.fill(0)
        }
    }

    fun authenticateManagement(algorithm: ManagementAlgorithm, key: ByteArray) {
        try {
            session.requireContact()
            require(key.size == algorithm.keySize)
            select()
            authenticate(algorithm, key)
        } finally { key.fill(0) }
    }

    fun changeManagementKey(currentAlgorithm: ManagementAlgorithm, currentKey: ByteArray,
                            newAlgorithm: ManagementAlgorithm, newKey: ByteArray) {
        try {
            session.requireContact()
            require(currentKey.size == currentAlgorithm.keySize && newKey.size == newAlgorithm.keySize)
            select()
            authenticate(currentAlgorithm, currentKey)
            send(Apdu(ins = 0xff, p1 = 0xff, p2 = 0xff,
                data = byteArrayOf(newAlgorithm.code.toByte(), 0x9b.toByte(), newKey.size.toByte()) + newKey,
                le = null), true)
            session.applied()
            select()
            authenticate(newAlgorithm, newKey)
            if (byte(metadata(0x9b), 1) != newAlgorithm.code) malformed()
        } finally { currentKey.fill(0); newKey.fill(0) }
    }

    fun generateKey(slot: Int, algorithm: KeyAlgorithm, pinPolicy: PinPolicy,
                    managementAlgorithm: ManagementAlgorithm, managementKey: ByteArray): SlotMetadata {
        try {
            session.requireContact()
            requireSlot(slot)
            require(managementKey.size == managementAlgorithm.keySize)
            select()
            authenticate(managementAlgorithm, managementKey)
            val result = send(Apdu(ins = 0x47, p2 = slot, data = PivTlv.encode(0xac,
                PivTlv.encode(0x80, byteArrayOf(algorithm.code.toByte())) +
                    PivTlv.encode(0xaa, byteArrayOf(pinPolicy.code.toByte())) + PivTlv.encode(0xab, byteArrayOf(1)))), true)
            session.applied()
            val generated = publicKey(algorithm, PivTlv.one(result, 0x7f49))
            val readback = slotMetadata(slot)
            val readbackKey = readback.publicKey ?: malformed()
            if (!generated.encoded.contentEquals(readbackKey.encoded) || readback.algorithm != algorithm || !readback.generated ||
                readback.pinPolicy != pinPolicy.code || readback.touchPolicy != 1) malformed()
            return readback
        } finally { managementKey.fill(0) }
    }

    fun createCsr(slot: Int, pin: CharArray, subject: String): ByteArray =
        try { createCsr(slot, pin, X500Name(subject)) } finally { pin.fill('\u0000') }

    fun createCsr(slot: Int, pin: CharArray, name: X500Name, requiredAlgorithm: KeyAlgorithm? = null): ByteArray {
        return withPin(pin) { encodedPin ->
            requireSlot(slot)
            require(name.rdNs.isNotEmpty())
            select()
            val info = slotMetadata(slot)
            val verifiedKey = info.publicKey ?: throw CardFailure(FailureCode.UNSUPPORTED)
            if (requiredAlgorithm != null && info.algorithm != requiredAlgorithm) throw CardFailure(FailureCode.UNSUPPORTED)
            verifyEncoded(encodedPin)
            val buffer = ByteArrayOutputStream()
            val signer = object : ContentSigner {
                override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
                    DefaultSignatureAlgorithmIdentifierFinder().find(info.algorithm.signature)
                override fun getOutputStream() = buffer
                override fun getSignature(): ByteArray = sign(slot, info.algorithm, buffer.toByteArray())
            }
            val request = PKCS10CertificationRequestBuilder(name, SubjectPublicKeyInfo.getInstance(verifiedKey.encoded)).build(signer)
            try {
                if (!request.isSignatureValid(JcaContentVerifierProviderBuilder().build(verifiedKey))) malformed()
            } catch (_: org.bouncycastle.pkcs.PKCSException) { malformed() }
            request.encoded
        }
    }

    fun importCertificate(slot: Int, certificateDer: ByteArray, managementAlgorithm: ManagementAlgorithm,
                          managementKey: ByteArray): X509Certificate {
        try {
            session.requireContact()
            requireSlot(slot)
            require(managementKey.size == managementAlgorithm.keySize)
            val cert = parseCertificate(certificateDer)
            select()
            val info = slotMetadata(slot)
            val verifiedKey = info.publicKey ?: throw CardFailure(FailureCode.UNSUPPORTED)
            if (!cert.publicKey.encoded.contentEquals(verifiedKey.encoded))
                throw CardFailure(FailureCode.CERTIFICATE_KEY_MISMATCH)
            authenticate(managementAlgorithm, managementKey)
            val container = PivTlv.encode(0x70, cert.encoded) + PivTlv.encode(0x71, byteArrayOf(0)) + PivTlv.encode(0xfe, byteArrayOf())
            send(Apdu(ins = 0xdb, p1 = 0x3f, p2 = 0xff,
                data = objectTag(slot) + PivTlv.encode(0x53, container), le = null), true)
            session.applied()
            val readback = certificate(slot) ?: malformed()
            if (!readback.encoded.contentEquals(cert.encoded)) malformed()
            return readback
        } finally { managementKey.fill(0) }
    }

    fun verifySignature(slot: Int, pin: CharArray): Boolean {
        return withPin(pin) { encodedPin ->
            requireSlot(slot)
            select()
            val info = slotMetadata(slot)
            val verifiedKey = info.publicKey ?: throw CardFailure(FailureCode.UNSUPPORTED)
            val challenge = ByteArray(32).also(random::nextBytes)
            verifyEncoded(encodedPin)
            val signature = sign(slot, info.algorithm, challenge)
            val verifier = Signature.getInstance(info.algorithm.signature)
            verifier.initVerify(verifiedKey)
            verifier.update(challenge)
            try { verifier.verify(signature).also { if (!it) malformed() } }
            catch (_: SignatureException) { malformed() }
        }
    }

    fun checkTlsIdentity(expectedLeaf: ByteArray, expectedSpki: ByteArray): PivInfo {
        session.requireContact()
        val info = readInfo()
        val slot = readSlot(0x9a)
        if (slot.metadata?.algorithm != KeyAlgorithm.P256 || slot.metadata.publicKey == null)
            throw CardFailure(FailureCode.UNSUPPORTED)
        if (slot.certificate?.encoded?.contentEquals(expectedLeaf) != true ||
            !slot.metadata.publicKey.encoded.contentEquals(expectedSpki) ||
            !slot.certificate.publicKey.encoded.contentEquals(expectedSpki))
            throw CardFailure(FailureCode.CERTIFICATE_KEY_MISMATCH)
        return info
    }

    fun signTlsDigest(pin: CharArray, digest: ByteArray, expectedLeaf: ByteArray,
                      expectedSpki: ByteArray, expectedInfo: PivInfo, lastRetryApproved: Boolean): ByteArray =
        withPin(pin) { encodedPin ->
            require(digest.size == 32)
            val current = checkTlsIdentity(expectedLeaf, expectedSpki)
            if (current != expectedInfo) throw CardFailure(FailureCode.RECOVERY_REQUIRED)
            if (current.pin.remaining == 0) throw CardFailure(FailureCode.PIN_BLOCKED)
            if (current.pin.remaining == 1 && current.puk.remaining == 0 && !lastRetryApproved)
                throw CardFailure(FailureCode.RECOVERY_REQUIRED)
            verifyEncoded(encodedPin)
            // TLS has already hashed CertificateVerify; GA takes that digest unchanged.
            val signature = privateOperation(0x9a, KeyAlgorithm.P256, 0x81, digest)
            val verifier = org.bouncycastle.crypto.signers.ECDSASigner()
            verifier.init(false, org.bouncycastle.crypto.util.PublicKeyFactory.createKey(expectedSpki))
            try {
                val values = org.bouncycastle.crypto.signers.StandardDSAEncoding.INSTANCE.decode(
                    (org.bouncycastle.crypto.util.PublicKeyFactory.createKey(expectedSpki) as
                        org.bouncycastle.crypto.params.ECPublicKeyParameters).parameters.n, signature)
                if (!verifier.verifySignature(digest, values[0], values[1])) malformed()
            } catch (_: java.io.IOException) { malformed() }
            catch (_: IllegalArgumentException) { malformed() }
            signature
        }

    fun verifyKeyAgreement(slot: Int, pin: CharArray): Boolean {
        return withPin(pin) { encodedPin ->
            requireSlot(slot)
            select()
            val info = slotMetadata(slot)
            val verifiedKey = info.publicKey ?: throw CardFailure(FailureCode.UNSUPPORTED)
            val curve = info.algorithm.curve ?: throw CardFailure(FailureCode.UNSUPPORTED)
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(curve), random)
            val ephemeral = generator.generateKeyPair()
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(ephemeral.private)
            agreement.doPhase(verifiedKey, true)
            val expected = agreement.generateSecret()
            verifyEncoded(encodedPin)
            val actual = privateOperation(slot, info.algorithm, 0x85, point(ephemeral.public as ECPublicKey))
            MessageDigest.isEqual(expected, actual).also { if (!it) malformed() }
        }
    }

    fun verifyRsaDecryption(slot: Int, pin: CharArray): Boolean {
        return withPin(pin) { encodedPin ->
            requireSlot(slot)
            select()
            val info = slotMetadata(slot)
            val verifiedKey = info.publicKey ?: throw CardFailure(FailureCode.UNSUPPORTED)
            if (info.algorithm != KeyAlgorithm.RSA2048) throw CardFailure(FailureCode.UNSUPPORTED)
            val expected = ByteArray(256).also(random::nextBytes).also { it[0] = 0 }
            val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, verifiedKey)
            val encrypted = cipher.doFinal(expected)
            verifyEncoded(encodedPin)
            val actual = privateOperation(slot, info.algorithm, 0x81, encrypted)
            MessageDigest.isEqual(expected, actual).also { if (!it) malformed() }
        }
    }

    fun reset() {
        session.requireContact()
        select()
        if (retries(0x80).remaining != 0 || retries(0x81).remaining != 0)
            throw CardFailure(FailureCode.RECOVERY_REQUIRED)
        send(Apdu(ins = 0xfb, le = null), true)
        session.applied()
        select()
        val pin = retries(0x80)
        val puk = retries(0x81)
        if (!pin.defaultValue || !puk.defaultValue || pin.remaining == 0 || puk.remaining == 0) malformed()
    }

    private inline fun <T> withPin(pin: CharArray, operation: (ByteArray) -> T): T {
        var encoded = byteArrayOf()
        try {
            if (secure == null) session.requireContact() else secure.requireCurrent(session)
            encoded = secret(pin)
            return validateResponse { operation(encoded) }
        } finally { pin.fill('\u0000'); encoded.fill(0) }
    }

    private inline fun <T> validateResponse(operation: () -> T): T = try {
        operation()
    } catch (e: CardFailure) {
        if (e.code == FailureCode.MALFORMED_RESPONSE) secure?.close()
        throw e
    } catch (e: GeneralSecurityException) {
        secure?.close()
        throw e
    }

    private fun select() {
        if (secure != null) secure.requireCurrent(session)
        else send(Apdu(ins = 0xa4, p1 = 4, data = "a000000308000010000100".hexBytes()))
    }
    private fun exchange(apdu: Apdu, effectful: Boolean = false): ApduResponse =
        secure?.exchange(apdu, effectful) ?: session.exchange(apdu, effectful)
    private fun send(apdu: Apdu, effectful: Boolean = false): ByteArray = try {
        val response = exchange(apdu, effectful)
        if (effectful && response.status and 0xfff0 == 0x63c0) session.securityStateChanged()
        response.requireSuccess()
    } finally { if (effectful) apdu.data.fill(0) }
    private fun verifyEncoded(pin: ByteArray) {
        try { send(Apdu(ins = 0x20, p2 = 0x80, data = pin, le = null), true); session.securityStateChanged() }
        finally { pin.fill(0) }
    }
    private fun metadata(reference: Int) = PivTlv.parse(send(Apdu(ins = 0xf7, p2 = reference)))
    private fun retries(reference: Int): RetryMetadata {
        val fields = metadata(reference)
        val counters = fields[6] ?: malformed()
        if (counters.size != 2) malformed()
        val total = counters[0].toInt() and 255
        val remaining = counters[1].toInt() and 255
        if (remaining > total) malformed()
        return RetryMetadata(flag(fields, 5), total, remaining)
    }
    private fun slotMetadata(slot: Int) = slotMetadataOrNull(slot) ?: throw CardFailure(FailureCode.CARD_REJECTED, 0x6a88)
    private fun slotMetadataOrNull(slot: Int): SlotMetadata? {
        val response = exchange(Apdu(ins = 0xf7, p2 = slot))
        if (response.status == 0x6a88) return null
        val fields = PivTlv.parse(response.requireSuccess())
        val policy = fields[2] ?: malformed()
        if (policy.size != 2) malformed()
        val pinPolicy = policy[0].toInt() and 255
        val touchPolicy = policy[1].toInt() and 255
        if (pinPolicy !in 1..3 || touchPolicy !in 1..3) malformed()
        val generated = !flag(fields, 3)
        if (!fields.containsKey(1) && !fields.containsKey(4)) {
            if (fields.keys != setOf(2, 3)) malformed()
            return null
        }
        if (!fields.containsKey(1)) malformed()
        val algorithm = KeyAlgorithm.from(byte(fields, 1))
        val key = if (fields.containsKey(4)) {
            if (fields.keys != setOf(1, 2, 3, 4)) malformed()
            publicKey(algorithm, fields.getValue(4))
        } else {
            if (generated || fields.keys != setOf(1, 2, 3)) malformed()
            null
        }
        return SlotMetadata(algorithm, pinPolicy, touchPolicy, generated, key)
    }
    private fun certificate(slot: Int): X509Certificate? {
        val response = exchange(Apdu(ins = 0xcb, p1 = 0x3f, p2 = 0xff, data = objectTag(slot)))
        if (response.status == 0x6a82 || response.status == 0x6a88) return null
        val fields = PivTlv.parse(PivTlv.one(response.requireSuccess(), 0x53))
        if (byte(fields, 0x71) != 0) throw CardFailure(FailureCode.UNSUPPORTED)
        return try { parseCertificate(fields[0x70] ?: malformed()) } catch (_: IllegalArgumentException) { malformed() }
    }
    private fun authenticate(algorithm: ManagementAlgorithm, key: ByteArray) {
        val witness = PivTlv.one(send(Apdu(ins = 0x87, p1 = algorithm.code, p2 = 0x9b,
            data = PivTlv.encode(0x7c, PivTlv.encode(0x80, byteArrayOf())), le = null), true), 0x7c)
        val encrypted = PivTlv.one(witness, 0x80)
        if (encrypted.size != algorithm.blockSize) malformed()
        val cipher = Cipher.getInstance("${algorithm.cipher}/ECB/NoPadding")
        val secret = SecretKeySpec(key, algorithm.cipher)
        cipher.init(Cipher.DECRYPT_MODE, secret)
        val plain = cipher.doFinal(encrypted)
        val challenge = ByteArray(algorithm.blockSize).also(random::nextBytes)
        val authentication = PivTlv.encode(0x7c, PivTlv.encode(0x80, plain) + PivTlv.encode(0x81, challenge) +
            PivTlv.encode(0x82, byteArrayOf()))
        plain.fill(0)
        val reply = send(Apdu(ins = 0x87, p1 = algorithm.code, p2 = 0x9b, data = authentication, le = null), true)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        if (!MessageDigest.isEqual(cipher.doFinal(challenge), PivTlv.one(PivTlv.one(reply, 0x7c), 0x82)))
            throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
    }
    private fun sign(slot: Int, algorithm: KeyAlgorithm, message: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(algorithm.hash).digest(message)
        val input = if (algorithm == KeyAlgorithm.RSA2048) {
            val digestInfo = "3031300d060960864801650304020105000420".hexBytes() + digest
            byteArrayOf(0, 1) + ByteArray(256 - digestInfo.size - 3) { 0xff.toByte() } + byteArrayOf(0) + digestInfo
        } else digest
        return privateOperation(slot, algorithm, 0x81, input)
    }
    private fun privateOperation(slot: Int, algorithm: KeyAlgorithm, tag: Int, input: ByteArray): ByteArray =
        PivTlv.one(PivTlv.one(send(Apdu(ins = 0x87, p1 = algorithm.code, p2 = slot,
            data = PivTlv.encode(0x7c, PivTlv.encode(0x82, byteArrayOf()) + PivTlv.encode(tag, input))), true), 0x7c), 0x82)

    companion object {
        val slots: List<Int> = listOf(0x9a, 0x9c, 0x9d, 0x9e) + (0x82..0x95)
        private fun requireSlot(slot: Int) { require(slot in slots) }
        // CardKey applies the same digit and padding rules to PIN and PUK.
        private fun secret(value: CharArray): ByteArray = try {
            require(value.size in 6..8 && value.all { it in '0'..'9' })
            ByteArray(8) { index -> if (index < value.size) value[index].code.toByte() else 0xff.toByte() }
        } finally { value.fill('\u0000') }
        private fun byte(fields: Map<Int, ByteArray>, tag: Int): Int =
            (fields[tag] ?: malformed()).also { if (it.size != 1) malformed() }[0].toInt() and 255
        private fun flag(fields: Map<Int, ByteArray>, tag: Int): Boolean = byte(fields, tag).let {
            if (it !in 0..1) malformed()
            it == 1
        }
        private fun objectTag(slot: Int): ByteArray {
            val objectId = when (slot) { 0x9a -> 0x5fc105; 0x9c -> 0x5fc10a; 0x9d -> 0x5fc10b; 0x9e -> 0x5fc101
                else -> 0x5fc10d + slot - 0x82 }
            return PivTlv.encode(0x5c, byteArrayOf((objectId ushr 16).toByte(), (objectId ushr 8).toByte(), objectId.toByte()))
        }
        private fun parseCertificate(bytes: ByteArray): X509Certificate {
            require(bytes.isNotEmpty() && bytes.size <= 60000)
            try {
                val input = ByteArrayInputStream(bytes)
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
                require(input.available() == 0 && cert.encoded.contentEquals(bytes))
                return cert
            } catch (e: java.security.cert.CertificateException) { throw IllegalArgumentException(e) }
        }
        private fun publicKey(algorithm: KeyAlgorithm, encoded: ByteArray): PublicKey {
            try {
                val fields = PivTlv.parse(encoded)
                if (algorithm == KeyAlgorithm.RSA2048) {
                    val modulus = BigInteger(1, fields[0x81] ?: malformed())
                    val exponent = BigInteger(1, fields[0x82] ?: malformed())
                    if (modulus.bitLength() != 2048 || exponent < BigInteger.valueOf(3) || !exponent.testBit(0)) malformed()
                    return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
                }
                val encodedPoint = fields[0x86] ?: malformed()
                val size = if (algorithm == KeyAlgorithm.P256) 32 else 48
                if (encodedPoint.size != 1 + size * 2 || encodedPoint[0] != 4.toByte()) malformed()
                val params = AlgorithmParameters.getInstance("EC")
                params.init(ECGenParameterSpec(algorithm.curve))
                val spec = params.getParameterSpec(ECParameterSpec::class.java)
                val x = BigInteger(1, encodedPoint.copyOfRange(1, 1 + size))
                val y = BigInteger(1, encodedPoint.copyOfRange(1 + size, encodedPoint.size))
                val prime = (spec.curve.field as ECFieldFp).p
                if (x >= prime || y >= prime || y.modPow(BigInteger.valueOf(2), prime) !=
                    x.modPow(BigInteger.valueOf(3), prime).add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(prime)) malformed()
                return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
            } catch (_: GeneralSecurityException) { malformed() }
        }
        private fun point(key: ECPublicKey): ByteArray {
            val size = (key.params.curve.field.fieldSize + 7) / 8
            fun coordinate(value: BigInteger): ByteArray = value.toByteArray().let {
                if (it.size > size) it.copyOfRange(it.size - size, it.size) else ByteArray(size - it.size) + it
            }
            return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
        }
    }
}
