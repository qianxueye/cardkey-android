package cc.cardkey.enrollment

import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.pkcs.CertificationRequest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.cert.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date

class EnrollmentCsr(der: ByteArray, val cn: String) {
    private val encoded = der.copyOf()
    private val publicKey: ByteArray
    val der get() = encoded.copyOf()
    val spki get() = publicKey.copyOf()
    val binding: String = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(encoded))
    init {
        require(encoded.size in 1..16384)
        EnrollmentProfile.name(cn)
        val request = PKCS10CertificationRequest(encoded)
        require(request.encoded.contentEquals(encoded))
        require(CertificationRequest.getInstance(ASN1Primitive.fromByteArray(encoded)).certificationRequestInfo.version.value.toInt() == 0)
        require(request.attributes.isEmpty())
        EnrollmentProfile.subject(request.subject, cn)
        publicKey = request.subjectPublicKeyInfo.encoded
        EnrollmentProfile.p256(request.subjectPublicKeyInfo)
        require(request.signatureAlgorithm.algorithm.id == "1.2.840.10045.4.3.2" && request.signatureAlgorithm.parameters == null)
        require(request.isSignatureValid(JcaContentVerifierProviderBuilder().build(
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey)))))
    }
    fun pem() = EnrollmentProfile.pem(encoded, "CERTIFICATE REQUEST")
}

class EnrollmentBundle(chain: List<ByteArray>, val verifiedAt: Long) {
    private val certificates = chain.map { it.copyOf() }
    val chain get() = certificates.map { it.copyOf() }
    val leaf get() = certificates.first().copyOf()
    fun pem() = certificates.joinToString("") { EnrollmentProfile.pem(it) }
}

object EnrollmentProfile {
    const val MAX_BUNDLE = 131072
    fun name(cn: String): X500Name {
        require(cn.isNotBlank() && cn.toByteArray(Charsets.UTF_8).size <= 64 && cn.none { it.isISOControl() })
        return X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, org.bouncycastle.asn1.DERUTF8String(cn)).build()
    }
    fun subject(name: X500Name, cn: String) {
        require(name.rdNs.size == 1 && name.rdNs.single().typesAndValues.size == 1)
        val attribute = name.rdNs.single().first
        require(attribute.type == BCStyle.CN && (attribute.value as? ASN1String)?.string == cn)
    }
    fun p256(spki: SubjectPublicKeyInfo) {
        require(spki.algorithm.algorithm == X9ObjectIdentifiers.id_ecPublicKey &&
            spki.algorithm.parameters == X9ObjectIdentifiers.prime256v1)
        require(spki.publicKeyData.padBits == 0 && spki.publicKeyData.bytes.size == 65 && spki.publicKeyData.bytes[0] == 4.toByte())
    }
    fun pem(der: ByteArray, label: String = "CERTIFICATE") =
        "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der) + "\n-----END $label-----\n"

    fun certificates(pem: String): List<ByteArray> {
        require(pem.toByteArray().size <= MAX_BUNDLE)
        val result = mutableListOf<ByteArray>()
        var rest = pem.trim()
        while (rest.isNotEmpty()) {
            val begin = "-----BEGIN CERTIFICATE-----"
            val end = "-----END CERTIFICATE-----"
            require(rest.startsWith(begin) && result.size < 5)
            val finish = rest.indexOf(end)
            require(finish >= begin.length)
            val base64 = rest.substring(begin.length, finish).filterNot(Char::isWhitespace)
            val bytes = Base64.getDecoder().decode(base64)
            certificate(bytes)
            result += bytes
            rest = rest.substring(finish + end.length).trim()
        }
        require(result.isNotEmpty())
        return result
    }
    fun root(input: ByteArray): ByteArray {
        require(input.size in 1..32768)
        val der = if (input.first() == 0x30.toByte()) input.copyOf()
            else certificates(input.toString(Charsets.UTF_8)).single()
        val root = certificate(der)
        require(root.basicConstraints >= 0 && root.subjectX500Principal == root.issuerX500Principal)
        require(root.keyUsage?.getOrNull(5) != false)
        root.verify(root.publicKey)
        return der
    }
    fun certificate(der: ByteArray): X509Certificate {
        require(der.size in 1..16384)
        val primitive = ASN1Primitive.fromByteArray(der)
        require(primitive.getEncoded("DER").contentEquals(der))
        // The BC parser rejects duplicate extension OIDs and malformed field structures.
        val holder = X509CertificateHolder(der)
        require(holder.encoded.contentEquals(der))
        val input = ByteArrayInputStream(der)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        require(input.available() == 0 && cert.encoded.contentEquals(der))
        return cert
    }
    fun validate(chain: List<ByteArray>, anchor: ByteArray, csr: EnrollmentCsr,
                 now: Long = System.currentTimeMillis(), issuedAt: Long = now): EnrollmentBundle {
        require(chain.size in 1..5 && chain.sumOf { it.size } <= MAX_BUNDLE)
        val root = certificate(root(anchor))
        root.checkValidity(Date(now))
        val certificates = chain.map(::certificate)
        require(certificates.distinctBy { Base64.getEncoder().encodeToString(it.encoded) }.size == certificates.size)
        val leaf = certificates.first()
        val holder = X509CertificateHolder(chain.first())
        val tbs = holder.toASN1Structure().tbsCertificate
        require(tbs.versionNumber == 3 && tbs.issuerUniqueId == null && tbs.subjectUniqueId == null)
        require(tbs.signature == holder.signatureAlgorithm)
        subject(holder.subject, csr.cn)
        p256(holder.subjectPublicKeyInfo)
        require(leaf.publicKey.encoded.contentEquals(csr.spki))
        require(holder.getExtension(Extension.subjectAlternativeName) == null)
        val extensions = requireNotNull(holder.extensions)
        require(KeyUsage.fromExtensions(extensions).encoded.contentEquals(KeyUsage(KeyUsage.digitalSignature).encoded))
        require(ExtendedKeyUsage.fromExtensions(extensions).encoded.contentEquals(ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth).encoded))
        require(BasicConstraints.fromExtensions(extensions).encoded.contentEquals(BasicConstraints(false).encoded))
        require(leaf.notBefore.time >= issuedAt - 300000 && leaf.notBefore.time <= issuedAt &&
            leaf.notAfter.time <= issuedAt + 86400000 && leaf.notAfter.after(leaf.notBefore))
        certificates.forEach { it.checkValidity(Date(now)) }
        val pathCerts = if (certificates.last().encoded.contentEquals(root.encoded)) certificates.dropLast(1) else certificates
        require(pathCerts.isNotEmpty())
        val path = CertificateFactory.getInstance("X.509").generateCertPath(pathCerts)
        val parameters = PKIXParameters(setOf(TrustAnchor(root, null))).apply {
            isRevocationEnabled = false
            date = Date(now)
        }
        CertPathValidator.getInstance("PKIX").validate(path, parameters)
        return EnrollmentBundle(chain, issuedAt)
    }
}
