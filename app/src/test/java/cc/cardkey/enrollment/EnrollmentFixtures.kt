package cc.cardkey.enrollment

import com.google.gson.JsonObject
import org.bouncycastle.asn1.ASN1ObjectIdentifier
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
    val csr = csr()
    fun csr(cn: String = "Synthetic", key: KeyPair = clientKey) = EnrollmentCsr(
        JcaPKCS10CertificationRequestBuilder(EnrollmentProfile.name(cn), key.public).build(JcaContentSignerBuilder("SHA256withECDSA").build(key.private)).encoded, cn)
    fun cert(subject: X500Name, issuer: X500Name, subjectKey: KeyPair, signingKey: KeyPair, ca: Boolean,
             from: Long = now - 60000, to: Long = now + 3600000): ByteArray {
        val builder = JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(System.nanoTime()), Date(from), Date(to), subject, subjectKey.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature))
        return builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(signingKey.private)).encoded
    }
    fun leaf(cn: String = csr.cn, extraSubject: Boolean = false, sans: Boolean = false,
             usage: Int = KeyUsage.digitalSignature, purposes: Array<KeyPurposeId> = arrayOf(KeyPurposeId.id_kp_clientAuth),
             ca: Boolean = false, from: Long = now - 60000, to: Long = now + 3600000,
             key: KeyPair = clientKey, critical: Boolean = false, omit: String? = null): ByteArray {
        val subject = if (extraSubject) X500Name("CN=$cn,O=extra") else EnrollmentProfile.name(cn)
        val builder = JcaX509v3CertificateBuilder(issuerName, BigInteger.valueOf(System.nanoTime()), Date(from), Date(to), subject, key.public)
        if (omit != "basic") builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        if (omit != "ku") builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
        if (omit != "eku") builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(purposes))
        if (sans) builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, "example.invalid")))
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
        fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
}
