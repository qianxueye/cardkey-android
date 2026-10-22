package cc.cardkey.protectedpiv

import cc.cardkey.protocol.piv.Cs7
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

class ProtectedPivProfileTest {
    private val ski = ByteArray(20) { (it + 1).toByte() }
    private val guid = "00112233445566778899aabbccddeeff"
    private val cardKey = key("secp384r1")
    private val cardPoint = Cs7.point(cardKey.public as ECPublicKey).hex()

    private fun key(curve: String) = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair()

    private fun certificate(curve: String = "secp384r1", identifier: ByteArray? = ski): ByteArray {
        val pair = key(curve)
        val name = X500Name("CN=Synthetic enrolled issuer")
        // Expired certificates remain usable as explicit pin containers.
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(1000), name, pair.public)
        if (identifier != null) builder.addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(identifier))
        return builder.build(JcaContentSignerBuilder("SHA384withECDSA").build(pair.private)).encoded
    }

    private fun json(point: String = cardPoint, id: String = ski.copyOf(8).hex(), code: String = "12345678") =
        """{"guid": "$guid", "issuer_id": "$id", "card_public": "$point", "pairing": "$code"}"""

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun pem(der: ByteArray) = "-----BEGIN CERTIFICATE-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der)}\n-----END CERTIFICATE-----\n"

    @Test fun producerRecordMapsToExistingSecureIdentity() {
        val der = certificate()
        val issuer = ProtectedPivProfileParser.issuer(pem(der).toByteArray())
        assertEquals("CN=Synthetic enrolled issuer", issuer.subject)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(der).hex(), issuer.fingerprint)
        assertEquals(issuer.fingerprint, ProtectedPivProfileParser.issuer(der).fingerprint)
        ProtectedPivProfileParser.profile(issuer, json().toByteArray()).use { profile ->
            assertEquals(guid, profile.guid)
            assertEquals(issuer.fingerprint, profile.issuerFingerprint)
            assertArrayEquals(ski.copyOf(8), profile.identity.issuerId)
            assertArrayEquals(cardPoint.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), profile.identity.cardPoint)
            val first = profile.pairingCopy()
            first.fill('0')
            assertArrayEquals("12345678".toCharArray(), profile.pairingCopy())
        }
    }

    @Test fun rejectsIssuerBundlesKeysTrailingDataAndInvalidCurveOrSki() {
        val der = certificate()
        val invalid = listOf(
            byteArrayOf(), ByteArray(32 * 1024 + 1), der + byteArrayOf(0), der + der,
            (pem(der) + pem(der)).toByteArray(),
            pem(der).replace("CERTIFICATE", "PRIVATE KEY").toByteArray(),
            (pem(der) + "trailing").toByteArray(), certificate("secp256r1"),
            certificate(identifier = null), certificate(identifier = ByteArray(7))
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { ProtectedPivProfileParser.issuer(it) } }
    }

    @Test fun rejectsMalformedDuplicateUnknownMissingAndWrongTypesWithoutLeakingInput() {
        val issuer = ProtectedPivProfileParser.issuer(certificate())
        val valid = json()
        val invalid = listOf(
            "", "[]", valid + " true", valid.dropLast(1) + ",\"pairing\":\"87654321\"}",
            valid.dropLast(1) + ",\"unknown\":\"private-marker\"}",
            valid.replace("\"pairing\": \"12345678\"", "\"pairing\": 12345678"),
            valid.replace("\"pairing\": \"12345678\"", "\"pairing\": null"),
            valid.replace(", \"pairing\": \"12345678\"", ""),
            valid.replace("\"guid\"", "guid"), valid.replace(": ", ": '").replace(",", "',"),
            valid.dropLast(1) + ",}", "/*comment*/$valid",
            json(id = "00".repeat(8)), json(id = "01"), json(point = "04" + "00".repeat(96)),
            json(point = "02" + cardPoint.drop(2)), json(point = cardPoint.drop(2)),
            json(code = "1234567"), json(code = "１２３４５６７８"), json(code = "1234567x"),
            valid.replace(guid, "g".repeat(32)), valid.replace(guid, guid.drop(2)),
            valid + " ".repeat(4096)
        )
        invalid.forEach { text ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ProtectedPivProfileParser.profile(issuer, text.toByteArray())
            }
            assertEquals("Invalid protected PIV enrollment profile", failure.message)
            assertNull(failure.cause)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedPivProfileParser.profile(issuer, byteArrayOf(0xc3.toByte(), 0x28))
        }
    }

    @Test fun minimumSkiAndJsonWhitespaceAreAccepted() {
        val issuer = ProtectedPivProfileParser.issuer(certificate(identifier = ski.copyOf(8)))
        ProtectedPivProfileParser.profile(issuer, ("\n " + json().replace(guid, guid.uppercase()) + " \r\n").toByteArray()).use {
            assertEquals(guid, it.guid)
        }
    }

    @Test fun escapedFieldsAndPairingReorderedWithInitialBomRemainCompatible() {
        val issuer = ProtectedPivProfileParser.issuer(certificate())
        val escaped = """{ "p\u0061iring" : "\u0031\u0032\u0033\u0034\u0035\u0036\u0037\u0038", "card_public" : "$cardPoint", "issuer_id" : "${ski.copyOf(8).hex()}", "\u0067uid" : "${guid.uppercase()}" }"""
        for (prefix in listOf("", "\uFEFF")) {
            val bytes = (prefix + "\t\r\n " + escaped + "\n").toByteArray()
            val original = bytes.copyOf()
            ProtectedPivProfileParser.profile(issuer, bytes).use { profile ->
                val pairing = profile.pairingCopy()
                try { assertArrayEquals("12345678".toCharArray(), pairing) } finally { pairing.fill('\u0000') }
                assertEquals(guid, profile.guid)
            }
            assertArrayEquals(original, bytes)
        }
    }

    @Test fun strictLexicalFailuresStayRedactedIncludingEscapedDuplicatesAndUtf8Suffixes() {
        val issuer = ProtectedPivProfileParser.issuer(certificate())
        val valid = json()
        val invalid = listOf(
            " \uFEFF$valid", valid + "\u000c", valid + "\u00a0",
            valid.dropLast(1) + ",\"p\\u0061iring\":\"87654321\"}",
            valid.replace("\"guid\"", "\"\\u0031\\u0032\\u0033\\u0034\\u0035\\u0036\\u0037\\u0038\""),
            json(code = "\\x31\\u0032345678"), json(code = "\\u003"), json(code = "\\u00zz2345678"),
            json(code = "1234\n5678"), json(code = "1234\\n5678"), json(code = "\\ud8002345678"),
            valid.replace("\"pairing\": \"12345678\"", "\"pairing\": [\"12345678\"]"),
            valid.replace("\"pairing\": \"12345678\"", "\"pairing\": true"),
            valid.replace(", \"issuer_id\"", " \"issuer_id\""), valid.replace(": ", " = "),
            valid.dropLast(1), valid.dropLast(1) + ",}", valid + valid,
            json(code = "1".repeat(3000))
        ).map { it.toByteArray() } + listOf(
            valid.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28),
            valid.toByteArray() + byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            valid.toByteArray() + byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte())
        )
        invalid.forEach { bytes ->
            val original = bytes.copyOf()
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ProtectedPivProfileParser.profile(issuer, bytes)
            }
            assertEquals("Invalid protected PIV enrollment profile", failure.message)
            assertNull(failure.cause)
            assertArrayEquals(original, bytes)
        }
    }

    @Test fun profileInputBoundIncludesWhitespaceAndRetainsCallerOwnership() {
        val issuer = ProtectedPivProfileParser.issuer(certificate())
        val bytes = json().padEnd(4096, ' ').toByteArray()
        val original = bytes.copyOf()
        ProtectedPivProfileParser.profile(issuer, bytes).close()
        assertArrayEquals(original, bytes)
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedPivProfileParser.profile(issuer, bytes + byteArrayOf(32))
        }
    }

    @Test fun strictJsonAgreesWithPreviousSchemaForEmptyObjectAndStandardEscapes() {
        val issuer = ProtectedPivProfileParser.issuer(certificate())
        // Synthetic inputs retain the replaced Gson schema as a compatibility oracle.
        fun legacyAccepts(bytes: ByteArray): Boolean = runCatching {
            require(bytes.isNotEmpty() && bytes.size <= 4096)
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val fields = mutableMapOf<String, String>()
            val required = setOf("guid", "issuer_id", "card_public", "pairing")
            com.google.gson.stream.JsonReader(java.io.StringReader(text)).use { reader ->
                reader.strictness = com.google.gson.Strictness.STRICT
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name in required && name !in fields)
                    require(reader.peek() == com.google.gson.stream.JsonToken.STRING)
                    fields[name] = reader.nextString()
                }
                reader.endObject()
                require(reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT && fields.keys == required)
            }
            fun hex(name: String, size: Int): ByteArray {
                val value = fields.getValue(name)
                require(value.length == size * 2 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                return ByteArray(size) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
            val pairing = fields.getValue("pairing")
            require(pairing.length == 8 && pairing.all { it in '0'..'9' })
            val issuerId = hex("issuer_id", 8)
            require(MessageDigest.isEqual(issuerId, issuer.issuerId))
            cc.cardkey.protocol.piv.EnrolledPivCard(issuer.spki, issuerId, hex("guid", 16), hex("card_public", 97))
        }.isSuccess
        val valid = json()
        val cases = listOf(
            valid to true, valid.replace("pairing", "p\\u0061iring") to true,
            json(code = "\\u00312345678") to true,
            "{}" to false, json(code = "\\/2345678") to false, json(code = "\\b2345678") to false,
            valid.dropLast(1) + ",\"p\\u0061iring\":\"87654321\"}" to false,
            valid + " true" to false
        )
        cases.forEach { (text, expected) ->
            val bytes = text.toByteArray()
            assertEquals(expected, legacyAccepts(bytes))
            val actual = runCatching { ProtectedPivProfileParser.profile(issuer, bytes).close() }.isSuccess
            assertEquals(expected, actual)
        }
    }

    @Test fun fourKiBByteBoundCountsBomAndRejectsMultibyteBoundaryTruncation() {
        val issuer = ProtectedPivProfileParser.issuer(certificate())
        val document = ("\uFEFF" + json()).toByteArray()
        val bounded = document + ByteArray(4096 - document.size) { 32 }
        assertEquals(4096, bounded.size)
        assertEquals(4094, bounded.toString(Charsets.UTF_8).length)
        ProtectedPivProfileParser.profile(issuer, bounded).close()
        val complete = json().toByteArray().let { it + ByteArray(4094 - it.size) { 32 } } + "é".toByteArray()
        assertEquals(4096, complete.size)
        for (bytes in listOf(bounded + byteArrayOf(32), complete, complete.copyOf(4095), complete + byteArrayOf(32))) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ProtectedPivProfileParser.profile(issuer, bytes)
            }
            assertEquals("Invalid protected PIV enrollment profile", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun ownedJsonBuffersAreClearedAfterSuccessAndPartialDecodeOrStringFailure() {
        val type = Class.forName("cc.cardkey.protectedpiv.ProtectedPivProfileParser\$ProfileJsonReader")
        val constructor = type.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
        val decode = type.getDeclaredMethod("decode", ByteArray::class.java).apply { isAccessible = true }
        val expect = type.getDeclaredMethod("expect", Char::class.javaPrimitiveType).apply { isAccessible = true }
        val string = type.getDeclaredMethod("string").apply { isAccessible = true }
        val textField = type.getDeclaredField("text").apply { isAccessible = true }
        val valueField = type.getDeclaredField("value").apply { isAccessible = true }
        val valid = """{"pairing":"12345678"}""".toByteArray()
        val malformedUtf8 = valid + byteArrayOf(0xc3.toByte(), 0x28)
        val invalidEscape = """{"pairing":"1234\q"}""".toByteArray()
        val oversized = ("{\"pairing\":\"" + "1".repeat(195) + "\"}").toByteArray()
        for (bytes in listOf(valid, malformedUtf8, invalidEscape, oversized)) {
            val reader = constructor.newInstance(bytes.size) as java.io.Closeable
            val text = textField.get(reader) as CharArray
            val value = valueField.get(reader) as CharArray
            try {
                val parse = {
                    decode.invoke(reader, bytes)
                    expect.invoke(reader, '{')
                    string.invoke(reader)
                    expect.invoke(reader, ':')
                    string.invoke(reader)
                }
                if (bytes === valid) parse()
                else assertThrows(java.lang.reflect.InvocationTargetException::class.java) { parse() }
                assertTrue(text.any { it != '\u0000' })
                if (bytes !== malformedUtf8) assertTrue(value.any { it != '\u0000' })
            } finally { reader.close() }
            assertTrue(text.all { it == '\u0000' })
            assertTrue(value.all { it == '\u0000' })
        }
    }

    @Test fun closingRetiresOwnedSecretAndCannotRaceCopyIntoPartialSecret() {
        val issuer = ProtectedPivProfileParser.issuer(certificate())
        val profile = ProtectedPivProfileParser.profile(issuer, json().toByteArray())
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val reader = Thread {
            try {
                start.await()
                repeat(100) {
                    try {
                        val copy = profile.pairingCopy()
                        try { assertArrayEquals("12345678".toCharArray(), copy) } finally { copy.fill('\u0000') }
                    } catch (_: IllegalStateException) { }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        reader.start()
        start.countDown()
        profile.close()
        reader.join(5000)
        assertFalse(reader.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent profile access failed", it) }
        profile.close()
        assertThrows(IllegalStateException::class.java) { profile.pairingCopy() }
        val secret = ProtectedPivProfile::class.java.getDeclaredField("pairing").apply { isAccessible = true }.get(profile) as CharArray
        assertTrue(secret.all { it == '\u0000' })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ProtectedPivPairingCleanupTest.ReaderShadow::class],
    instrumentedPackages = ["cc.cardkey.protectedpiv"])
class ProtectedPivPairingCleanupTest {
    private val key = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp384r1"))
    }.generateKeyPair()
    private val issuer = ProtectedPivIssuer("Synthetic issuer", "synthetic", key.public.encoded, ByteArray(8) { 1 })

    private fun document(mismatch: Boolean): ByteArray {
        val point = Cs7.point(key.public as ECPublicKey).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val id = if (mismatch) "00".repeat(8) else "01".repeat(8)
        return ("{\"pairing\":\"12345678\",\"guid\":\"00112233445566778899aabbccddeeff\"," +
            "\"card_public\":\"" + point + "\",\"issuer_id\":\"" + id + "\"}").toByteArray()
    }

    @Before fun resetCapture() {
        ReaderShadow.hits = 0
        ReaderShadow.returned = null
    }

    @After fun clearCapture() {
        ReaderShadow.returned?.fill('\u0000')
        ReaderShadow.returned = null
        ReaderShadow.hits = 0
    }

    @Test fun pairingParsedFirstIsClearedWhenLaterIssuerValidationFails() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProtectedPivProfileParser.profile(issuer, document(mismatch = true))
        }
        assertEquals("Invalid protected PIV enrollment profile", failure.message)
        assertNull(failure.cause)
        assertEquals(1, ReaderShadow.hits)
        val returned = requireNotNull(ReaderShadow.returned)
        assertTrue(returned.all { it == '\u0000' })
    }

    @Test fun returnedPairingIsClearedWhileSuccessfulProfileOwnsItsDistinctCopy() {
        val profile = ProtectedPivProfileParser.profile(issuer, document(mismatch = false))
        try {
            assertEquals(1, ReaderShadow.hits)
            val returned = requireNotNull(ReaderShadow.returned)
            assertTrue(returned.all { it == '\u0000' })
            val owned = ProtectedPivProfile::class.java.getDeclaredField("pairing").apply { isAccessible = true }.get(profile) as CharArray
            assertNotSame(returned, owned)
            assertArrayEquals("12345678".toCharArray(), owned)
            val copy = profile.pairingCopy()
            try { assertArrayEquals("12345678".toCharArray(), copy) } finally { copy.fill('\u0000') }
            profile.close()
            assertTrue(owned.all { it == '\u0000' })
        } finally { profile.close() }
    }

    @Implements(className = "cc.cardkey.protectedpiv.ProtectedPivProfileParser\$ProfileJsonReader", isInAndroidSdk = false)
    class ReaderShadow {
        @RealObject lateinit var reader: Any

        @Implementation fun pairing(): CharArray {
            val actual = Shadow.directlyOn<CharArray>(reader, reader.javaClass.name, "pairing")
            assertArrayEquals("12345678".toCharArray(), actual)
            assertTrue(actual.all { it != '\u0000' })
            hits++
            assertEquals(1, hits)
            returned = actual
            return actual
        }

        companion object {
            var hits = 0
            var returned: CharArray? = null
        }
    }
}
