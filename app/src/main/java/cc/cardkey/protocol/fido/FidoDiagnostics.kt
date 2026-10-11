package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider

data class AuthenticatorData(val rpIdHash: ByteArray, val flags: Int, val counter: Long,
    val aaguid: ByteArray?, val credentialId: ByteArray?, val publicKey: Cbor.Map?, val extensions: Cbor.Map?) {
    val userPresent get() = flags and 1 != 0
    val userVerified get() = flags and 4 != 0
    companion object {
        fun parse(bytes: ByteArray): AuthenticatorData {
            if (bytes.size < 37 || bytes.size > CborCodec.MAX_BYTES) malformed()
            val flags = bytes[32].toInt() and 255
            var counter = 0L
            for (i in 33..36) counter = (counter shl 8) or (bytes[i].toLong() and 255)
            var offset = 37; var aaguid: ByteArray? = null; var id: ByteArray? = null; var key: Cbor.Map? = null
            if (flags and 0x40 != 0) {
                if (bytes.size - offset < 18) malformed()
                aaguid = bytes.copyOfRange(offset, offset + 16); offset += 16
                val length = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255); offset += 2
                if (length == 0 || length > bytes.size - offset) malformed()
                id = bytes.copyOfRange(offset, offset + length); offset += length
                val parsed = CborCodec.decodePrefix(bytes.copyOfRange(offset, bytes.size))
                key = parsed.first.map(); offset += parsed.second
            }
            val extensions = if (flags and 0x80 != 0) {
                val parsed = CborCodec.decodePrefix(bytes.copyOfRange(offset, bytes.size))
                offset += parsed.second; parsed.first.map()
            } else null
            if (offset != bytes.size) malformed()
            return AuthenticatorData(bytes.copyOfRange(0, 32), flags, counter, aaguid, id, key, extensions)
        }
    }
}

enum class AttestationKind { NONE, SELF, CERTIFICATE_SIGNATURE_ONLY }
data class AttestationDiagnostic(val signatureValid: Boolean?, val kind: AttestationKind,
    val credentialId: ByteArray, val publicKey: Cbor.Map, val counter: Long)
data class AssertionDiagnostic(val signatureValid: Boolean, val counterAdvanced: Boolean?, val counter: Long)

object FidoDiagnostics {
    fun verifyRegistration(result: MakeCredentialResult, rpId: String, clientDataHash: ByteArray,
        requireUv: Boolean = true): AttestationDiagnostic {
        require(clientDataHash.size == 32)
        val auth = AuthenticatorData.parse(result.authData)
        checkAuth(auth, rpId, requireUv)
        val credential = auth.credentialId ?: malformed(); val key = auth.publicKey ?: malformed()
        val statement = result.statement
        val kind: AttestationKind; val verified: Boolean?
        when (result.format) {
            "none" -> {
                if (statement.value.isNotEmpty()) malformed()
                kind = AttestationKind.NONE; verified = null
            }
            "packed" -> {
                val algorithm = statement["alg"].number()
                val chain = statement["x5c"]?.array()
                if (chain != null) {
                    if (chain.isEmpty()) malformed()
                    val certificate = certificate(chain.first().bytes())
                    verified = verify(certificate.publicKey, algorithm, result.authData + clientDataHash, statement["sig"].bytes())
                    kind = AttestationKind.CERTIFICATE_SIGNATURE_ONLY
                } else {
                    if (statement["ecdaaKeyId"] != null) throw CardFailure(FailureCode.UNSUPPORTED)
                    if (key[3].number() != algorithm) malformed()
                    verified = verify(publicKey(key), algorithm, result.authData + clientDataHash, statement["sig"].bytes())
                    kind = AttestationKind.SELF
                }
            }
            "fido-u2f" -> {
                val chain = statement["x5c"].array()
                if (chain.size != 1 || key[3].number() != -7L) malformed()
                val ec = coseEcPublic(key, -7)
                val signed = byteArrayOf(0) + auth.rpIdHash + clientDataHash + credential + byteArrayOf(4) +
                    fixed32(ec.w.affineX) + fixed32(ec.w.affineY)
                verified = verify(certificate(chain[0].bytes()).publicKey, -7, signed, statement["sig"].bytes())
                kind = AttestationKind.CERTIFICATE_SIGNATURE_ONLY
            }
            else -> throw CardFailure(FailureCode.UNSUPPORTED)
        }
        return AttestationDiagnostic(verified, kind, credential, key, auth.counter)
    }

    fun verifyAssertion(assertion: Assertion, credentialPublicKey: Cbor.Map, rpId: String,
        clientDataHash: ByteArray, previousCounter: Long? = null, requireUv: Boolean = true): AssertionDiagnostic {
        require(clientDataHash.size == 32 && (previousCounter == null || previousCounter in 0..0xffffffffL))
        val auth = AuthenticatorData.parse(assertion.authData); checkAuth(auth, rpId, requireUv)
        if (auth.credentialId != null) malformed()
        val signature = verify(publicKey(credentialPublicKey), credentialPublicKey[3].number(),
            assertion.authData + clientDataHash, assertion.signature)
        val advanced = previousCounter?.let { if (it == 0L && auth.counter == 0L) null else auth.counter > it }
        return AssertionDiagnostic(signature, advanced, auth.counter)
    }

    private fun checkAuth(auth: AuthenticatorData, rpId: String, requireUv: Boolean) {
        if (!auth.rpIdHash.contentEquals(sha256(rpId.toByteArray(Charsets.UTF_8))) || !auth.userPresent ||
            (requireUv && !auth.userVerified)) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
    }

    internal fun publicKey(key: Cbor.Map): PublicKey = try {
        when (key[3].number()) {
            -7L -> coseEcPublic(key, -7)
            -257L -> {
                if (key[1].number() != 3L) malformed()
                val modulus = key[-1].bytes(); val exponent = key[-2].bytes()
                if (modulus.size !in 256..512 || exponent.isEmpty() || exponent.size > 4) malformed()
                KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, modulus), BigInteger(1, exponent)))
            }
            -8L -> {
                if (key[1].number() != 1L || key[-1].number() != 6L || key[-2].bytes().size != 32) malformed()
                val prefix = byteArrayOf(0x30, 0x2a, 0x30, 5, 6, 3, 0x2b, 0x65, 0x70, 3, 0x21, 0)
                KeyFactory.getInstance("Ed25519", BouncyCastleProvider()).generatePublic(X509EncodedKeySpec(prefix + key[-2].bytes()))
            }
            else -> throw CardFailure(FailureCode.UNSUPPORTED)
        }
    } catch (_: java.security.GeneralSecurityException) { malformed() }

    internal fun certificate(bytes: ByteArray): X509Certificate = try {
        val input = ByteArrayInputStream(bytes)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        if (input.available() != 0) malformed()
        certificate
    } catch (_: java.security.cert.CertificateException) { malformed() }

    internal fun verify(key: PublicKey, algorithm: Long, message: ByteArray, signature: ByteArray): Boolean = try {
        val verifier = when (algorithm) {
            -7L -> Signature.getInstance("SHA256withECDSA")
            -257L -> Signature.getInstance("SHA256withRSA")
            -8L -> Signature.getInstance("Ed25519", BouncyCastleProvider())
            else -> throw CardFailure(FailureCode.UNSUPPORTED)
        }
        verifier.initVerify(key); verifier.update(message); verifier.verify(signature)
    } catch (_: java.security.GeneralSecurityException) { false }
}
