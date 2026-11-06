package cc.cardkey.enrollment

import com.google.gson.JsonObject
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

internal class EnrollmentFixtures {
    val now = System.currentTimeMillis() / 1000 * 1000
    val rootKey = key()
    val intermediateKey = key()
    val clientKey = key()
    val rootName = X500Name("CN=Synthetic Root")
    val issuerName = X500Name("CN=Synthetic Intermediate")
    val root = cert(rootName, rootName, rootKey, rootKey, true)
    val intermediate = cert(issuerName, rootName, intermediateKey, rootKey, true)
    val identity = EnrollmentIdentity("EMP-001", "Synthetic", "synthetic@apture.cc", "synthetic@apture.cc")
    val csr = csr()
    fun csr(cn: String = "Synthetic", key: KeyPair = clientKey, selection: EnrollmentSelection = EnrollmentSelection(),
            identity: EnrollmentIdentity = this.identity.copy(displayName = cn)) = EnrollmentCsr(
        JcaPKCS10CertificationRequestBuilder(EnrollmentProfile.name(cn), key.public).build(JcaContentSignerBuilder(selection.algorithm.signature).build(key.private)).encoded, identity, selection)
    fun cert(subject: X500Name, issuer: X500Name, subjectKey: KeyPair, signingKey: KeyPair, ca: Boolean,
             from: Long = now - 60000, to: Long = now + 3600000): ByteArray {
        val builder = JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(System.nanoTime()), Date(from), Date(to), subject, subjectKey.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature))
        return builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(signingKey.private)).encoded
    }
    fun leaf(cn: String = csr.cn, extraSubject: Boolean = false, sans: Boolean = false,
             usage: Int = KeyUsage.digitalSignature, purposes: Array<KeyPurposeId> = arrayOf(KeyPurposeId.id_kp_clientAuth, oid("1.3.6.1.4.1.311.20.2.2")),
             ca: Boolean = false, from: Long = now - 60000, to: Long = now + 3600000,
             key: KeyPair = clientKey, critical: Boolean = false, omit: String? = null,
             identity: EnrollmentIdentity = this.identity.copy(displayName = cn),
             subject: X500Name = subject(identity),
             names: Array<GeneralName> = authenticationNames(identity)): ByteArray {
        val selectedSubject = if (extraSubject) X500Name("CN=$cn,O=extra") else subject
        val builder = JcaX509v3CertificateBuilder(issuerName, BigInteger.valueOf(System.nanoTime()), Date(from), Date(to), selectedSubject, key.public)
        if (omit != "basic") builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        if (omit != "ku") builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
        if (omit != "eku") builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(purposes))
        val selectedNames = if (sans) names + GeneralName(GeneralName.dNSName, "example.invalid") else names
        if (selectedNames.isNotEmpty()) builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(selectedNames))
        if (critical) builder.addExtension(ASN1ObjectIdentifier("1.2.3.4.5"), true, byteArrayOf(5, 0))
        return builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(intermediateKey.private)).encoded
    }
    fun token(csr: EnrollmentCsr = this.csr, address: String = "https://localhost", change: (JsonObject) -> Unit = {}): String {
        val claims = JsonObject().apply {
            addProperty("sub", csr.cn); addProperty("aud", "$address/1.0/sign")
            addProperty("iat", now / 1000); addProperty("exp", now / 1000 + 300)
            add("cnf", JsonObject().apply { addProperty("x5rt#S256", Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(csr.der))) })
        }
        change(claims)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString("{\"alg\":\"ES256\"}".toByteArray()) + "." + encoder.encodeToString(claims.toString().toByteArray()) + ".AA"
    }
    companion object {
        fun oid(value: String): KeyPurposeId = KeyPurposeId.getInstance(ASN1ObjectIdentifier(value))
        fun subject(identity: EnrollmentIdentity): X500Name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.C, "US").addRDN(BCStyle.O, "Aperture Intelligence LLC")
            .addRDN(BCStyle.UID, identity.employeeReference).addRDN(BCStyle.CN, identity.displayName).build()
        fun emailNames(identity: EnrollmentIdentity): Array<GeneralName> = if (identity.email.isEmpty()) emptyArray()
            else arrayOf(GeneralName(GeneralName.rfc822Name, identity.email))
        fun upn(value: String, oid: String = "1.3.6.1.4.1.311.20.2.3", utf8: Boolean = true): GeneralName =
            GeneralName(GeneralName.otherName, DERSequence(arrayOf(ASN1ObjectIdentifier(oid),
                DERTaggedObject(true, 0, if (utf8) DERUTF8String(value) else DERIA5String(value)))))
        fun authenticationNames(identity: EnrollmentIdentity): Array<GeneralName> = arrayOf(upn(identity.upn)) + emailNames(identity)

        fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
}
