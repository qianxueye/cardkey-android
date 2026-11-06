package cc.cardkey.enrollment

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.DERUTF8String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.math.BigInteger
import java.util.Date

class EnrollmentV22CompatibilityTest {
    @Test fun accepts9aV22SubjectUpnAndSmartcardLogonEku() {
        val fixture = EnrollmentFixtures()
        val builder = JcaX509v3CertificateBuilder(fixture.issuerName, BigInteger.ONE,
            Date(fixture.now - 60000), Date(fixture.now + 3600000),
            X500Name("C=US,O=Aperture Intelligence LLC,UID=EMP-001,CN=Synthetic"), fixture.clientKey.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.getInstance(ASN1ObjectIdentifier("1.3.6.1.4.1.311.20.2.2")))))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.otherName,
            DERSequence(arrayOf(ASN1ObjectIdentifier("1.3.6.1.4.1.311.20.2.3"), DERTaggedObject(true, 0, DERUTF8String("synthetic@apture.cc")))))))
        val leaf = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(fixture.intermediateKey.private)).encoded
        assertArrayEquals(leaf, EnrollmentProfile.validate(listOf(leaf, fixture.intermediate), fixture.root,
            fixture.csr, fixture.now, fixture.now).leaf)
    }
}
