package cc.cardkey.signing

import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import java.math.BigInteger
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test

internal fun documentCertificate(card: EnrollmentCard, ca: Boolean = false, usage: Int? = KeyUsage.digitalSignature,
                                 eku: String? = PivCms.DOCUMENT_SIGNING_EKU,
                                 start: Long = System.currentTimeMillis() - 60_000,
                                 end: Long = System.currentTimeMillis() + 86_400_000): X509Certificate {
    val name = X500Name("CN=Synthetic document signer")
    val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(start), Date(end), name, card.keyPair.public)
        .addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
    if (usage != null) builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
    if (eku != null) builder.addExtension(Extension.extendedKeyUsage, false,
        ExtendedKeyUsage(KeyPurposeId.getInstance(ASN1ObjectIdentifier(eku))))
    return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder(card.algorithm.signature).build(card.keyPair.private)))
}

class PivCmsTest {
    @Test fun realCardPathSignsAttributesOnceForAllAlgorithmsAndExportsOpenSslFixtures() {
        for (algorithm in KeyAlgorithm.entries) {
            val card = EnrollmentCard(algorithm = algorithm)
            val certificate = documentCertificate(card)
            card.certificate = certificate.encoded
            val bytes = "Exact binary document\u0000\r\n\u00ff".toByteArray()
            var signedInput: ByteArray? = null
            val channel = object : CardChannel by card {
                override fun transmit(command: ByteArray): ByteArray {
                    val result = card.transmit(command)
                    if (command[1].toInt() and 255 == 0x87 && command[0].toInt() and 0x10 == 0)
                        signedInput = PivTlv.parse(PivTlv.one(card.commands.last().data, 0x7c)).getValue(0x81)
                    return result
                }
            }
            val result = CardSession(channel).run { PivClient(it).signCmsFile(0x9c, "123456".toCharArray(), bytes) }
            assertTrue(result.toString(), result is OperationResult.Completed)
            val encoded = (result as OperationResult.Completed).value
            PivCms.verify(encoded, bytes, certificate, algorithm)
            val cms = CMSSignedData(encoded)
            assertTrue(cms.isDetachedSignature)
            val signer = cms.signerInfos.signers.single()
            assertEquals(2, signer.signedAttributes.size())
            assertNotNull(signer.signedAttributes[CMSAttributes.contentType])
            assertEquals(1, card.commands.count { it.ins == 0x20 })
            assertEquals(1, card.commands.count { it.ins == 0x87 })
            val ga = card.commands.single { it.ins == 0x87 }
            assertEquals(0x9c, ga.p2)
            val attributesHash = java.security.MessageDigest.getInstance(algorithm.hash).digest(signer.encodedSignedAttributes)
            assertArrayEquals(attributesHash, requireNotNull(signedInput).takeLast(attributesHash.size).toByteArray())
            assertFalse(java.security.MessageDigest.getInstance(algorithm.hash).digest(bytes).contentEquals(signedInput))
            val directory = java.io.File("build/cms-openssl/${algorithm.name}").apply { mkdirs() }
            java.io.File(directory, "signature.p7s").writeBytes(encoded)
            java.io.File(directory, "original.bin").writeBytes(bytes)
            java.io.File(directory, "signer.pem").writeBytes(cc.cardkey.ui.ManagerOperations.pem("CERTIFICATE", certificate.encoded))
        }
    }

    @Test fun sizeAndContactBoundariesRejectBeforeAuthentication() {
        for ((size, medium) in listOf(0 to Medium.CONTACT, 1_048_577 to Medium.CONTACT,
                1 to Medium.NFC, 1_048_576 to Medium.CONTACT)) {
            val card = EnrollmentCard(medium = medium).apply { certificate = documentCertificate(this).encoded }
            val input = ByteArray(size) { (it % 251).toByte() }
            val pin = "123456".toCharArray()
            val result = CardSession(card).run { PivClient(it).signCmsFile(0x9c, pin, input) }
            assertTrue(pin.all { it == '\u0000' })
            if (size == 1_048_576) {
                assertTrue(result.toString(), result is OperationResult.Completed)
                assertEquals(1, card.commands.count { it.ins == 0x87 })
            } else {
                assertTrue(result.toString(), result is OperationResult.Failed)
                assertEquals(0, card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
            }
        }
    }

    @Test fun profileRejectionsNeverVerifyPinOrSign() {
        for (failure in listOf("missing", "malformed", "wrong-key", "ca", "wrong-ku", "no-ku", "client-auth", "no-eku", "expired", "future", "no-metadata", "unsupported", "no-key")) {
            val card = EnrollmentCard()
            val now = System.currentTimeMillis()
            card.certificate = when (failure) {
                "missing" -> null
                "malformed" -> byteArrayOf(1, 2, 3)
                "wrong-key" -> documentCertificate(EnrollmentCard()).encoded
                "ca" -> documentCertificate(card, ca = true).encoded
                "wrong-ku" -> documentCertificate(card, usage = KeyUsage.keyEncipherment).encoded
                "no-ku" -> documentCertificate(card, usage = null).encoded
                "client-auth" -> documentCertificate(card, eku = "1.3.6.1.5.5.7.3.2").encoded
                "no-eku" -> documentCertificate(card, eku = null).encoded
                "expired" -> documentCertificate(card, start = now - 120_000, end = now - 60_000).encoded
                "future" -> documentCertificate(card, start = now + 60_000, end = now + 120_000).encoded
                else -> documentCertificate(card).encoded
            }
            if (failure in listOf("no-metadata", "unsupported", "no-key")) card.slotMetadata =
                PivTlv.encode(2, byteArrayOf(2, 1)) + PivTlv.encode(3, byteArrayOf(1)) +
                    when (failure) {
                        "no-metadata" -> byteArrayOf()
                        "unsupported" -> PivTlv.encode(1, byteArrayOf(0x22))
                        else -> PivTlv.encode(1, byteArrayOf(0x11))
                    }
            val result = CardSession(card).run { PivClient(it).signCmsFile(0x9c, "123456".toCharArray(), byteArrayOf(1)) }
            assertTrue(failure, result is OperationResult.Failed)
            assertEquals(failure, 0, card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
        }
    }

    @Test fun changedFileWrongCertificateAndCorruptCmsFailVerification() {
        val card = EnrollmentCard()
        val cert = documentCertificate(card)
        card.certificate = cert.encoded
        val bytes = byteArrayOf(1, 2, 3)
        val encoded = (CardSession(card).run { PivClient(it).signCmsFile(0x9c, "123456".toCharArray(), bytes) } as OperationResult.Completed).value
        for (failure in listOf("changed-file", "wrong-cert", "corrupt", "missing-cert", "missing-attrs", "wrong-content-type", "wrong-digest", "duplicate-digest")) {
            val cms = CMSSignedData(encoded)
            val altered = when (failure) {
                "corrupt" -> encoded.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
                "missing-cert" -> CMSSignedData.replaceCertificatesAndCRLs(cms, org.bouncycastle.util.CollectionStore<org.bouncycastle.cert.X509CertificateHolder>(emptyList()), null, null).encoded
                "missing-attrs", "wrong-content-type", "wrong-digest", "duplicate-digest" -> {
                    val content = org.bouncycastle.asn1.cms.SignedData.getInstance(cms.toASN1Structure().content)
                    val signer = org.bouncycastle.asn1.cms.SignerInfo.getInstance(content.signerInfos.getObjectAt(0))
                    val attributes = if (failure == "missing-attrs") null else {
                        val vector = org.bouncycastle.asn1.ASN1EncodableVector()
                        for (attribute in signer.authenticatedAttributes) {
                            val parsed = org.bouncycastle.asn1.cms.Attribute.getInstance(attribute)
                            vector.add(when {
                                parsed.attrType == CMSAttributes.contentType && failure == "wrong-content-type" ->
                                    org.bouncycastle.asn1.cms.Attribute(parsed.attrType, org.bouncycastle.asn1.DERSet(ASN1ObjectIdentifier("1.2.3")))
                                parsed.attrType == CMSAttributes.messageDigest && failure == "wrong-digest" ->
                                    org.bouncycastle.asn1.cms.Attribute(parsed.attrType, org.bouncycastle.asn1.DERSet(org.bouncycastle.asn1.DEROctetString(ByteArray(32))))
                                else -> parsed
                            })
                            if (failure == "duplicate-digest" && parsed.attrType == CMSAttributes.messageDigest) vector.add(parsed)
                        }
                        org.bouncycastle.asn1.DERSet(vector)
                    }
                    val changed = org.bouncycastle.asn1.cms.SignerInfo(signer.sid, signer.digestAlgorithm, attributes, signer.digestEncryptionAlgorithm, signer.encryptedDigest, null as org.bouncycastle.asn1.ASN1Set?)
                    org.bouncycastle.asn1.cms.ContentInfo(cms.toASN1Structure().contentType,
                        org.bouncycastle.asn1.cms.SignedData(content.digestAlgorithms, content.encapContentInfo, content.certificates, content.crLs, org.bouncycastle.asn1.DERSet(changed))).encoded
                }
                else -> encoded
            }
            try {
                PivCms.verify(altered, if (failure == "changed-file") byteArrayOf(1, 2, 4) else bytes,
                    if (failure == "wrong-cert") documentCertificate(EnrollmentCard()) else cert, card.algorithm)
                fail(failure)
            } catch (expected: CardFailure) { assertEquals(FailureCode.MALFORMED_RESPONSE, expected.code) }
        }
    }

    @Test fun incorrectPinAndLostVerificationPreserveFailureEvidenceWithoutSigning() {
        for (lost in listOf(false, true)) {
            val card = EnrollmentCard().apply {
                certificate = documentCertificate(this).encoded
                if (!lost) pinStatus = 0x63c2
            }
            val channel = object : CardChannel by card {
                override fun transmit(command: ByteArray): ByteArray {
                    val response = card.transmit(command)
                    if (lost && command[1].toInt() and 255 == 0x20) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    return response
                }
            }
            val result = CardSession(channel).run { PivClient(it).signCmsFile(0x9c, "123456".toCharArray(), byteArrayOf(1)) } as OperationResult.Failed
            assertEquals(if (lost) FailureCode.TRANSPORT_FAILURE else FailureCode.PIN_INVALID, result.code)
            assertEquals(if (lost) Effect.UNKNOWN else Effect.APPLIED, result.effect)
            assertEquals(if (lost) Dispatch.RESPONSE_UNAVAILABLE else null, result.dispatch)
            assertEquals(1, card.commands.count { it.ins == 0x20 })
            assertEquals(0, card.commands.count { it.ins == 0x87 })
        }
    }

    @Test fun corruptedOrLostCardResponseAndChangedDocumentNeverReturnCms() {
        for (failure in listOf("corrupt", "lost", "changed-file", "removed")) {
            val card = EnrollmentCard().apply { certificate = documentCertificate(this).encoded }
            if (failure == "corrupt") card.corruptSignature = true
            val bytes = byteArrayOf(1, 2, 3)
            val channel = object : CardChannel by card {
                override fun transmit(command: ByteArray): ByteArray {
                    val result = card.transmit(command)
                    if (command[1].toInt() and 255 == 0x87) {
                        if (failure == "lost") throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                        if (failure == "removed") throw java.io.IOException("removed")
                        if (failure == "changed-file") bytes[0] = 9
                    }
                    return result
                }
            }
            val result = CardSession(channel).run { PivClient(it).signCmsFile(0x9c, "123456".toCharArray(), bytes) }
            assertTrue(failure, result is OperationResult.Failed)
            assertEquals(1, card.commands.count { it.ins == 0x87 })
            if (failure in listOf("lost", "removed")) assertEquals(Effect.UNKNOWN, (result as OperationResult.Failed).effect)
        }
    }
}
