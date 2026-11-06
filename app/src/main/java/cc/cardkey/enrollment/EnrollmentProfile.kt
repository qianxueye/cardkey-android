package cc.cardkey.enrollment

import cc.cardkey.protocol.piv.KeyAlgorithm
import cc.cardkey.protocol.piv.PivClient
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERUTF8String
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
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

enum class EnrollmentPurpose(val eku: String) {
    TLS("1.3.6.1.5.5.7.3.2"), DOCUMENT("1.3.6.1.5.5.7.3.36"), KEY_MANAGEMENT("1.3.6.1.5.5.7.3.4")
}

data class EnrollmentIdentity(val employeeReference: String, val displayName: String, val upn: String, val email: String) {
    init {
        require(employeeReference.isNotBlank() && employeeReference.none { it.isISOControl() })
        EnrollmentProfile.name(displayName)
        require(upn.none { it.isISOControl() } && email.all { it.code in 0x21..0x7e })
    }
}

data class EnrollmentSelection(val purpose: EnrollmentPurpose = EnrollmentPurpose.TLS,
                               val slot: Int = when (purpose) {
                                   EnrollmentPurpose.TLS -> 0x9a
                                   EnrollmentPurpose.DOCUMENT -> 0x9c
                                   EnrollmentPurpose.KEY_MANAGEMENT -> 0x9d
                               },
                               val algorithm: KeyAlgorithm = if (purpose == EnrollmentPurpose.KEY_MANAGEMENT) KeyAlgorithm.RSA2048 else KeyAlgorithm.P256) {
    init {
        require(slot in PivClient.slots)
        require(when (purpose) {
            EnrollmentPurpose.TLS -> slot == 0x9a && algorithm == KeyAlgorithm.P256
            EnrollmentPurpose.DOCUMENT -> slot == 0x9c
            EnrollmentPurpose.KEY_MANAGEMENT -> slot == 0x9d && algorithm == KeyAlgorithm.RSA2048
        }) { "Enrollment slot or algorithm does not match purpose" }
    }
}

class EnrollmentCsr(der: ByteArray, val identity: EnrollmentIdentity, val selection: EnrollmentSelection = EnrollmentSelection()) {
    val cn get() = identity.displayName
    private val encoded = der.copyOf()
    private val publicKey: ByteArray
    val der get() = encoded.copyOf()
    val spki get() = publicKey.copyOf()
    val binding: String = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(encoded))
    init {
        require(encoded.size in 1..16384)
        EnrollmentProfile.name(cn)
        require(selection.purpose != EnrollmentPurpose.TLS || identity.upn.isNotBlank()) { "Authentication requires a person UPN" }
        val request = PKCS10CertificationRequest(encoded)
        require(request.encoded.contentEquals(encoded))
        require(CertificationRequest.getInstance(ASN1Primitive.fromByteArray(encoded)).certificationRequestInfo.version.value.toInt() == 0)
        require(request.attributes.isEmpty())
        EnrollmentProfile.subject(request.subject, cn)
        publicKey = request.subjectPublicKeyInfo.encoded
        EnrollmentProfile.publicKey(request.subjectPublicKeyInfo, selection.algorithm)
        val signatureOid = when (selection.algorithm) {
            KeyAlgorithm.P256 -> "1.2.840.10045.4.3.2"
            KeyAlgorithm.P384 -> "1.2.840.10045.4.3.3"
            KeyAlgorithm.RSA2048 -> "1.2.840.113549.1.1.11"
        }
        require(request.signatureAlgorithm.algorithm.id == signatureOid)
        require(if (selection.algorithm == KeyAlgorithm.RSA2048) request.signatureAlgorithm.parameters == DERNull.INSTANCE
                else request.signatureAlgorithm.parameters == null)
        require(request.isSignatureValid(JcaContentVerifierProviderBuilder().build(
            KeyFactory.getInstance(if (selection.algorithm == KeyAlgorithm.RSA2048) "RSA" else "EC").generatePublic(X509EncodedKeySpec(publicKey)))))
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
    private const val CARD_LIFETIME_MS = 8760L * 60 * 60 * 1000
    private const val CLOCK_SKEW_MS = 300000L
    fun name(cn: String): X500Name {
        require(cn.isNotBlank() && cn.toByteArray(Charsets.UTF_8).size <= 64 && cn.none { it.isISOControl() })
        return X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, org.bouncycastle.asn1.DERUTF8String(cn)).build()
    }
    fun subject(name: X500Name, cn: String) {
        require(name.rdNs.size == 1 && name.rdNs.single().typesAndValues.size == 1)
        val attribute = name.rdNs.single().first
        require(attribute.type == BCStyle.CN && (attribute.value as? ASN1String)?.string == cn)
    }
    fun certificateSubject(name: X500Name, identity: EnrollmentIdentity) {
        val expected = listOf(BCStyle.C to "US", BCStyle.O to "Aperture Intelligence LLC",
            BCStyle.UID to identity.employeeReference, BCStyle.CN to identity.displayName)
        require(name.rdNs.size == expected.size && name.rdNs.zip(expected).all { (rdn, value) ->
            rdn.typesAndValues.size == 1 && rdn.first.type == value.first &&
                (rdn.first.value as? ASN1String)?.string == value.second
        }) { "Certificate subject does not match the person identity" }
    }
    private fun alternativeNames(holder: X509CertificateHolder, csr: EnrollmentCsr) {
        val names = GeneralNames.fromExtensions(holder.extensions, Extension.subjectAlternativeName)?.names.orEmpty()
        val emails = names.filter { it.tagNo == GeneralName.rfc822Name }
        val upns = names.filter { it.tagNo == GeneralName.otherName }
        require(names.size == emails.size + upns.size) { "Certificate SAN contains an unsupported name type" }
        require(emails.size <= 1 && emails.all { (it.name as? ASN1String)?.string == csr.identity.email && csr.identity.email.isNotEmpty() }) {
            "Certificate email SAN does not match the person email"
        }
        if (csr.selection.purpose == EnrollmentPurpose.TLS) {
            require(upns.size == 1) { "Authentication certificate requires exactly one UPN SAN" }
            val valid = runCatching {
                val value = ASN1Sequence.getInstance(upns.single().name)
                val tagged = ASN1TaggedObject.getInstance(value.getObjectAt(1))
                value.size() == 2 && value.getObjectAt(0) == ASN1ObjectIdentifier("1.3.6.1.4.1.311.20.2.3") &&
                    tagged.tagNo == 0 && tagged.isExplicit &&
                    (tagged.explicitBaseObject as? DERUTF8String)?.string == csr.identity.upn
            }.getOrDefault(false)
            require(valid) { "Certificate UPN SAN does not match the person UPN" }
        } else {
            require(upns.isEmpty()) { "Certificate SAN contains an unsupported name type" }
            require(emails.size == if (csr.identity.email.isEmpty()) 0 else 1) {
                "Certificate email SAN does not match the person email"
            }
        }
    }
    private fun usages(extensions: Extensions, csr: EnrollmentCsr) {
        val usage = when (csr.selection.purpose) {
            EnrollmentPurpose.TLS -> KeyUsage.digitalSignature
            EnrollmentPurpose.DOCUMENT -> KeyUsage.digitalSignature or KeyUsage.nonRepudiation
            EnrollmentPurpose.KEY_MANAGEMENT -> KeyUsage.keyEncipherment
        }
        require(KeyUsage.fromExtensions(extensions)?.encoded?.contentEquals(KeyUsage(usage).encoded) == true) {
            "Certificate key usage does not match purpose"
        }
        val expected = when (csr.selection.purpose) {
            EnrollmentPurpose.TLS -> setOf(EnrollmentPurpose.TLS.eku, "1.3.6.1.4.1.311.20.2.2")
            EnrollmentPurpose.DOCUMENT -> setOf(EnrollmentPurpose.DOCUMENT.eku, "1.3.6.1.4.1.311.10.3.12") +
                if (csr.identity.email.isEmpty()) emptySet() else setOf(EnrollmentPurpose.KEY_MANAGEMENT.eku)
            EnrollmentPurpose.KEY_MANAGEMENT -> if (csr.identity.email.isEmpty()) emptySet() else setOf(EnrollmentPurpose.KEY_MANAGEMENT.eku)
        }
        val eku = ExtendedKeyUsage.fromExtensions(extensions)
        val actual = eku?.usages?.map { it.id }.orEmpty()
        require(actual.toSet() == expected && (expected.isNotEmpty() || eku == null)) {
            "Certificate extended key usage does not match purpose"
        }
    }
    fun publicKey(spki: SubjectPublicKeyInfo, algorithm: KeyAlgorithm) {
        require(spki.publicKeyData.padBits == 0)
        if (algorithm == KeyAlgorithm.RSA2048) {
            require(spki.algorithm.algorithm == PKCSObjectIdentifiers.rsaEncryption && spki.algorithm.parameters == DERNull.INSTANCE)
            val key = RSAPublicKey.getInstance(spki.parsePublicKey())
            require(key.modulus.signum() > 0 && key.modulus.bitLength() == 2048 && key.publicExponent == java.math.BigInteger.valueOf(65537))
        } else {
            val curve = if (algorithm == KeyAlgorithm.P256) X9ObjectIdentifiers.prime256v1 else SECObjectIdentifiers.secp384r1
            val size = if (algorithm == KeyAlgorithm.P256) 65 else 97
            require(spki.algorithm.algorithm == X9ObjectIdentifiers.id_ecPublicKey && spki.algorithm.parameters == curve)
            require(spki.publicKeyData.bytes.size == size && spki.publicKeyData.bytes[0] == 4.toByte())
        }
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
        certificateSubject(holder.subject, csr.identity)
        require(runCatching { publicKey(holder.subjectPublicKeyInfo, csr.selection.algorithm) }.isSuccess) {
            "Certificate public key does not match the requested algorithm"
        }
        require(leaf.publicKey.encoded.contentEquals(csr.spki)) { "Certificate public key does not match the CSR" }
        val extensions = requireNotNull(holder.extensions)
        alternativeNames(holder, csr)
        usages(extensions, csr)
        val constraints = BasicConstraints.fromExtensions(extensions)
        require(constraints == null || constraints.encoded.contentEquals(BasicConstraints(false).encoded)) {
            "Certificate basicConstraints must identify an end entity"
        }
        require(leaf.notBefore.time >= issuedAt - CLOCK_SKEW_MS && leaf.notBefore.time <= issuedAt) {
            "Certificate notBefore is outside the issuance window"
        }
        require(leaf.notAfter.after(leaf.notBefore)) { "Certificate notAfter must be later than notBefore" }
        require(leaf.notAfter.time - leaf.notBefore.time <= CARD_LIFETIME_MS + CLOCK_SKEW_MS) {
            "Certificate lifetime exceeds 8760 hours plus 300 seconds"
        }
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
