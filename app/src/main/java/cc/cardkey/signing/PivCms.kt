package cc.cardkey.signing

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.TransportException
import cc.cardkey.protocol.piv.KeyAlgorithm
import cc.cardkey.protocol.piv.SlotMetadata
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.SimpleAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

internal object PivCms {
    const val DOCUMENT_SIGNING_EKU = "1.3.6.1.5.5.7.3.36"
    private val provider = BouncyCastleProvider()

    fun requireEligible(certificate: X509Certificate, metadata: SlotMetadata): KeyAlgorithm {
        val algorithm = metadata.algorithm ?: throw CardFailure(FailureCode.UNSUPPORTED)
        val key = metadata.publicKey ?: throw CardFailure(FailureCode.UNSUPPORTED)
        require(certificate.basicConstraints == -1)
        require(certificate.keyUsage?.getOrNull(0) == true)
        require(certificate.extendedKeyUsage?.contains(DOCUMENT_SIGNING_EKU) == true)
        try { certificate.checkValidity() }
        catch (_: java.security.cert.CertificateException) { throw CardFailure(FailureCode.INVALID_INPUT) }
        if (!certificate.publicKey.encoded.contentEquals(key.encoded))
            throw CardFailure(FailureCode.CERTIFICATE_KEY_MISMATCH)
        return algorithm
    }

    fun generate(bytes: ByteArray, certificate: X509Certificate, algorithm: KeyAlgorithm,
                 sign: (ByteArray) -> ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        val signer = object : ContentSigner {
            override fun getAlgorithmIdentifier() = DefaultSignatureAlgorithmIdentifierFinder().find(algorithm.signature)
            override fun getOutputStream() = buffer
            // CMS supplies the DER SET OF signed attributes; PivClient hashes this message once.
            override fun getSignature() = sign(buffer.toByteArray())
        }
        val attributes = ASN1EncodableVector().apply {
            add(Attribute(CMSAttributes.contentType, DERSet(CMSObjectIdentifiers.data)))
            add(Attribute(CMSAttributes.messageDigest, DERSet(DEROctetString(MessageDigest.getInstance(algorithm.hash).digest(bytes)))))
        }
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(provider).build())
            .setSignedAttributeGenerator(SimpleAttributeTableGenerator(AttributeTable(attributes)))
            .build(signer, certificate))
        generator.addCertificate(X509CertificateHolder(certificate.encoded))
        val encoded = try {
            generator.generate(CMSProcessableByteArray(bytes), false).getEncoded(ASN1Encoding.DER)
        } catch (failure: org.bouncycastle.cms.CMSException) {
            // BC wraps a card transport IOException while constructing SignerInfo.
            val cause = failure.cause
            if (cause is TransportException) throw cause
            throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        }
        verify(encoded, bytes, certificate, algorithm)
        return encoded
    }

    fun verify(encoded: ByteArray, bytes: ByteArray, certificate: X509Certificate, algorithm: KeyAlgorithm) {
        try {
            require(ASN1Primitive.fromByteArray(encoded).getEncoded(ASN1Encoding.DER).contentEquals(encoded))
            val detached = CMSSignedData(encoded)
            require(detached.isDetachedSignature && detached.signedContentTypeOID == CMSObjectIdentifiers.data.id)
            val certificates = detached.certificates.getMatches(null)
            require(certificates.size == 1 && certificates.single().encoded.contentEquals(certificate.encoded))
            val cms = CMSSignedData(CMSProcessableByteArray(bytes), encoded)
            val signer = cms.signerInfos.signers.single()
            require(signer.sid.match(X509CertificateHolder(certificate.encoded)))
            val attributes = requireNotNull(signer.signedAttributes)
            require(attributes.size() == 2)
            val type = attributes.getAll(CMSAttributes.contentType)
            val digest = attributes.getAll(CMSAttributes.messageDigest)
            require(type.size() == 1 && digest.size() == 1)
            val typeValues = Attribute.getInstance(type.get(0)).attrValues
            val digestValues = Attribute.getInstance(digest.get(0)).attrValues
            require(typeValues.size() == 1 && typeValues.getObjectAt(0) == CMSObjectIdentifiers.data)
            require(digestValues.size() == 1 && ASN1OctetString.getInstance(digestValues.getObjectAt(0)).octets
                .contentEquals(MessageDigest.getInstance(algorithm.hash).digest(bytes)))
            require(signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(certificate)))
        } catch (failure: Exception) {
            throw CardFailure(FailureCode.MALFORMED_RESPONSE)
        }
    }
}
