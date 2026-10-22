package cc.cardkey.protectedpiv

import cc.cardkey.protocol.piv.Cs7
import cc.cardkey.protocol.piv.EnrolledPivCard
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
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
            var seen = 0
            ProfileJsonReader(bytes.size).use { reader ->
                reader.decode(bytes)
                reader.expect('{')
                while (true) {
                    reader.string()
                    val field = when {
                        reader.matches("guid") -> 1
                        reader.matches("issuer_id") -> 2
                        reader.matches("card_public") -> 4
                        reader.matches("pairing") -> 8
                        else -> error("Unknown field")
                    }
                    require(seen and field == 0)
                    seen = seen or field
                    reader.expect(':')
                    reader.string()
                    when (field) {
                        1 -> guid = reader.hex(16)
                        2 -> issuerId = reader.hex(8)
                        4 -> cardPoint = reader.hex(97)
                        8 -> pairing = reader.pairing()
                    }
                    if (reader.take('}')) break
                    reader.expect(',')
                }
                reader.end()
                require(seen == 15)
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

    private class ProfileJsonReader(capacity: Int) : Closeable {
        private val text = CharArray(capacity)
        private val value = CharArray(97 * 2)
        private var limit = 0
        private var position = 0
        private var valueLength = 0

        fun decode(bytes: ByteArray) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            // Own partial decoder output so a malformed suffix can still be cleared.
            val output = CharBuffer.wrap(text)
            val decoded = decoder.decode(ByteBuffer.wrap(bytes), output, true)
            if (decoded.isError) decoded.throwException()
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
            limit = output.position()
            if (limit > 0 && text[0] == '\uFEFF') position = 1
        }

        fun take(expected: Char): Boolean {
            whitespace()
            if (position >= limit || text[position] != expected) return false
            position++
            return true
        }

        fun expect(expected: Char) { require(take(expected)) }

        fun string() {
            value.fill('\u0000')
            valueLength = 0
            expect('"')
            while (position < limit) {
                var char = text[position++]
                if (char == '"') return
                require(char >= ' ')
                if (char == '\\') {
                    require(position < limit)
                    char = when (val escaped = text[position++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(limit - position >= 4)
                            var code = 0
                            repeat(4) { code = code * 16 + hexDigit(text[position++]) }
                            code.toChar()
                        }
                        else -> error("Invalid escape")
                    }
                }
                require(valueLength < value.size)
                value[valueLength++] = char
            }
            error("Unterminated string")
        }

        fun matches(name: String) = valueLength == name.length && name.indices.all { value[it] == name[it] }

        fun hex(size: Int): ByteArray {
            require(valueLength == size * 2)
            return ByteArray(size) { (hexDigit(value[it * 2]) * 16 + hexDigit(value[it * 2 + 1])).toByte() }
        }

        fun pairing(): CharArray {
            require(valueLength == 8 && (0 until valueLength).all { value[it] in '0'..'9' })
            return value.copyOf(valueLength)
        }

        fun end() {
            whitespace()
            require(position == limit)
        }

        private fun whitespace() {
            while (position < limit) {
                when (text[position]) {
                    ' ', '\t', '\r', '\n' -> position++
                    else -> return
                }
            }
        }

        private fun hexDigit(char: Char): Int = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> error("Invalid hexadecimal digit")
        }

        override fun close() {
            text.fill('\u0000')
            value.fill('\u0000')
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
