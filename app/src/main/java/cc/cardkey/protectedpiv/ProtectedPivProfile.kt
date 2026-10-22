package cc.cardkey.protectedpiv

import cc.cardkey.protocol.piv.Cs7
import cc.cardkey.protocol.piv.EnrolledPivCard
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Closeable
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.X509CertificateHolder

class ProtectedPivIssuer internal constructor(
    val subject: String,
    val fingerprint: String,
    internal val spki: ByteArray,
    internal val issuerId: ByteArray
)

class ProtectedPivProfile internal constructor(
    val identity: EnrolledPivCard,
    val guid: String,
    val issuerFingerprint: String,
    pairing: CharArray
) : Closeable {
    private val pairing = pairing.copyOf()
    private var closed = false

    @Synchronized
    fun pairingCopy(): CharArray {
        check(!closed) { "Protected PIV profile is closed" }
        return pairing.copyOf()
    }

    @Synchronized
    override fun close() {
        closed = true
        pairing.fill('\u0000')
    }
}

object ProtectedPivProfileParser {
    fun issuer(bytes: ByteArray): ProtectedPivIssuer {
        try {
            require(bytes.isNotEmpty() && bytes.size <= 32 * 1024)
            val der = if (bytes[0] == '-'.code.toByte() || bytes[0].toInt().toChar().isWhitespace()) {
                val pem = decodeUtf8(bytes)
                val match = Regex("\\A[ \\t\\r\\n]*-----BEGIN CERTIFICATE-----[ \\t\\r\\n]+([A-Za-z0-9+/=\\r\\n \\t]+)-----END CERTIFICATE-----[ \\t\\r\\n]*\\z")
                    .matchEntire(pem) ?: error("Invalid certificate")
                Base64.getDecoder().decode(match.groupValues[1].filterNot { it.isWhitespace() })
            } else bytes
            val certificate = X509CertificateHolder(der)
            require(ASN1Primitive.fromByteArray(der).getEncoded("DER").contentEquals(der))
            val spki = certificate.subjectPublicKeyInfo.getEncoded("DER")
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
            require(publicKey.params.order == Cs7.curve.order && publicKey.params.curve == Cs7.curve.curve &&
                publicKey.params.generator == Cs7.curve.generator && publicKey.params.cofactor == 1)
            Cs7.publicKey(Cs7.point(publicKey))
            val ski = SubjectKeyIdentifier.getInstance(certificate.getExtension(Extension.subjectKeyIdentifier)?.parsedValue)
                ?.keyIdentifier ?: error("Missing subject key identifier")
            require(ski.size >= 8)
            // Explicitly selected SPKI pin container; no PKIX or validity decision is made here.
            return ProtectedPivIssuer(certificate.subject.toString(), hex(MessageDigest.getInstance("SHA-256").digest(der)),
                spki, ski.copyOf(8))
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid protected PIV issuer certificate")
        }
    }

    fun profile(issuer: ProtectedPivIssuer, bytes: ByteArray): ProtectedPivProfile {
        var pairing: CharArray? = null
        try {
            require(bytes.isNotEmpty() && bytes.size <= 4 * 1024)
            var guid: ByteArray? = null
            var issuerId: ByteArray? = null
            var cardPoint: ByteArray? = null
            val seen = mutableSetOf<String>()
            JsonReader(StringReader(decodeUtf8(bytes))).use { reader ->
                reader.strictness = Strictness.STRICT
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name in setOf("guid", "issuer_id", "card_public", "pairing") && seen.add(name))
                    require(reader.peek() == JsonToken.STRING)
                    val value = reader.nextString()
                    when (name) {
                        "guid" -> guid = unhex(value, 16)
                        "issuer_id" -> issuerId = unhex(value, 8)
                        "card_public" -> cardPoint = unhex(value, 97)
                        "pairing" -> {
                            require(value.length == 8 && value.all { it in '0'..'9' })
                            pairing = value.toCharArray()
                        }
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT && seen.size == 4)
            }
            require(MessageDigest.isEqual(issuerId!!, issuer.issuerId))
            val identity = EnrolledPivCard(issuer.spki, issuerId!!, guid!!, cardPoint!!)
            return ProtectedPivProfile(identity, hex(guid!!), issuer.fingerprint, pairing!!)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid protected PIV enrollment profile")
        } finally {
            pairing?.fill('\u0000')
        }
    }

    private fun unhex(value: String, size: Int): ByteArray {
        require(value.length == size * 2 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        return ByteArray(size) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
