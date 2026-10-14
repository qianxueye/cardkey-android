package cc.cardkey.ssh

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.piv.KeyAlgorithm
import cc.cardkey.protocol.piv.SlotMetadata
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo

object PivSshPublicKey {
    fun export(metadata: SlotMetadata): ByteArray = try {
        val algorithm = requireNotNull(metadata.algorithm)
        val encoded = requireNotNull(metadata.publicKey).encoded
        val spki = SubjectPublicKeyInfo.getInstance(ASN1Primitive.fromByteArray(encoded))
        require(spki.getEncoded(ASN1Encoding.DER).contentEquals(encoded) && spki.publicKeyData.padBits == 0)
        val buffer = ByteArrayOutputStream()
        val output = DataOutputStream(buffer)
        fun field(bytes: ByteArray) { output.writeInt(bytes.size); output.write(bytes) }
        fun textField(text: String) = field(text.toByteArray(Charsets.US_ASCII))
        val type: String
        if (algorithm == KeyAlgorithm.RSA2048) {
            require(spki.algorithm.algorithm.id == "1.2.840.113549.1.1.1" && spki.algorithm.parameters == DERNull.INSTANCE)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded)) as RSAPublicKey
            require(key.encoded.contentEquals(encoded) && key.modulus.bitLength() == 2048 && key.modulus.testBit(0))
            require(key.publicExponent >= BigInteger.valueOf(3) && key.publicExponent.testBit(0) && key.publicExponent < key.modulus)
            type = "ssh-rsa"
            textField(type); field(key.publicExponent.toByteArray()); field(key.modulus.toByteArray())
        } else {
            val size = if (algorithm == KeyAlgorithm.P256) 32 else 48
            val curve = if (algorithm == KeyAlgorithm.P256) "nistp256" else "nistp384"
            val oid = if (algorithm == KeyAlgorithm.P256) "1.2.840.10045.3.1.7" else "1.3.132.0.34"
            require(spki.algorithm.algorithm.id == "1.2.840.10045.2.1" && (spki.algorithm.parameters as? ASN1ObjectIdentifier)?.id == oid)
            val point = spki.publicKeyData.bytes
            require(point.size == 1 + 2 * size && point[0] == 4.toByte())
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as ECPublicKey
            require(key.encoded.contentEquals(encoded))
            val prime = (key.params.curve.field as ECFieldFp).p
            val x = key.w.affineX; val y = key.w.affineY
            require(x.signum() >= 0 && y.signum() >= 0 && x < prime && y < prime &&
                y.modPow(BigInteger.TWO, prime) == x.modPow(BigInteger.valueOf(3), prime)
                    .add(key.params.curve.a.multiply(x)).add(key.params.curve.b).mod(prime))
            type = "ecdsa-sha2-$curve"
            textField(type); textField(curve); field(point)
        }
        "$type ${Base64.getEncoder().encodeToString(buffer.toByteArray())}\n".toByteArray(Charsets.US_ASCII)
    } catch (_: Exception) { throw CardFailure(FailureCode.MALFORMED_RESPONSE) }
}
