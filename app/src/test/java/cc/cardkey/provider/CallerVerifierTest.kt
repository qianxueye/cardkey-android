package cc.cardkey.provider

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class CallerVerifierTest {
    private val certificate = byteArrayOf(1, 2, 3, 4)
    private val fingerprint = hash(certificate).joinToString(":") { "%02X".format(it.toInt() and 255) }
    private var fetches = 0
    private fun verifier(json: String = dal()): CallerVerifier = CallerVerifier(AssetLinksFetcher {
        assertEquals("example.com", it)
        fetches++
        json.toByteArray()
    })
    private fun dal(relation: String = "delegate_permission/common.handle_all_urls", packageName: String = "test.native", fp: String = fingerprint): String =
        """[{"relation":["$relation"],"target":{"namespace":"android_app","package_name":"$packageName","sha256_cert_fingerprints":["$fp"]}}]"""
    private fun caller(origin: String? = null, packageName: String = "test.native", certificates: List<ByteArray> = listOf(certificate)) = object : CallerIdentity {
        override val packageName = packageName
        override fun origin() = origin
        override fun currentSigners() = certificates
    }

    @Test fun nativeClientJsonBytesAreExactlyTheHashedReturnedBytes() {
        val data = verifier().verify(caller(), "example.com", "AQID", "webauthn.create", null)
        val origin = "android:apk-key-hash:" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash(certificate))
        val expected = """{"type":"webauthn.create","challenge":"AQID","origin":"$origin","crossOrigin":false}""".toByteArray()
        assertArrayEquals(expected, data.json)
        assertArrayEquals(hash(expected), data.hash)
        assertFalse(data.browserDelegated)
        assertEquals(1, fetches)
    }

    @Test fun nativeAssertionUsesGetType() {
        val data = verifier().verify(caller(), "example.com", "AQID", "webauthn.get", null)
        assertEquals("webauthn.get", JsonParser.parseString(data.json.toString(Charsets.UTF_8)).asJsonObject["type"].asString)
    }

    @Test fun delegatedHashIsPreservedForRelatedOriginsWithoutDalOrSuffixChecks() {
        val supplied = ByteArray(32) { it.toByte() }
        val data = verifier().verify(caller("https://related.example.net", CallerVerifier.CHROME_PACKAGE), "example.com", "AQID", "webauthn.get", supplied)
        assertArrayEquals(supplied, data.hash)
        assertNotSame(supplied, data.hash)
        assertArrayEquals("{}".toByteArray(), data.json)
        assertTrue(data.browserDelegated)
        assertEquals(0, fetches)
    }

    @Test fun originExceptionNeverFallsBackToNativeDal() {
        var signerReads = 0
        val broken = object : CallerIdentity {
            override val packageName = "test.native"
            override fun origin(): String? = throw IllegalStateException()
            override fun currentSigners(): List<ByteArray> { signerReads++; return listOf(certificate) }
        }
        assertThrows(CallerVerificationException::class.java) { verifier().verify(broken, "example.com", "AQID", "webauthn.get", null) }
        assertEquals(0, fetches)
        assertEquals(0, signerReads)
    }

    @Test fun browserRequiresChromeAndExactly32ByteSuppliedHash() {
        listOf<ByteArray?>(null, ByteArray(0), ByteArray(31), ByteArray(33)).forEach {
            assertThrows(CallerVerificationException::class.java) { verifier().verify(caller("https://example.com", CallerVerifier.CHROME_PACKAGE), "example.com", "AQID", "webauthn.get", it) }
        }
        assertThrows(CallerVerificationException::class.java) { verifier().verify(caller("https://example.com", "org.chromium.chrome"), "example.com", "AQID", "webauthn.get", ByteArray(32)) }
        assertEquals(0, fetches)
    }

    @Test fun nativeRejectsSuppliedHashAndAmbiguousOrMissingCurrentSigners() {
        assertThrows(CallerVerificationException::class.java) { verifier().verify(caller(), "example.com", "AQID", "webauthn.get", ByteArray(32)) }
        listOf(emptyList(), listOf(certificate, byteArrayOf(5)), listOf(byteArrayOf())).forEach {
            assertThrows(CallerVerificationException::class.java) { verifier().verify(caller(certificates = it), "example.com", "AQID", "webauthn.get", null) }
        }
        assertEquals(0, fetches)
    }

    @Test fun nativeDalRequiresPackageCertificateAndHandleAllUrlsInSameStatement() {
        val rejected = listOf(
            dal("delegate_permission/common.get_login_creds"),
            dal(packageName = "test.other"), dal(fp = "00:11"),
            dal().replace("android_app", "web"),
            dal(fp = "00:11").dropLast(1) + "," + dal("delegate_permission/common.get_login_creds").drop(1),
            """[{"include":"https://example.com/other.json"}]""",
            "[]", "{}", "null", dal().replace("\"relation\":", "\"relation\":[],\"relation\":"),
        )
        rejected.forEach {
            assertThrows(CallerVerificationException::class.java) { verifier(it).verify(caller(), "example.com", "AQID", "webauthn.get", null) }
        }
        val both = dal().replace("\"delegate_permission/common.handle_all_urls\"", "\"delegate_permission/common.handle_all_urls\",\"delegate_permission/common.get_login_creds\"")
        assertFalse(verifier(both).verify(caller(), "example.com", "AQID", "webauthn.get", null).browserDelegated)
        assertFalse(verifier(dal(fp = fingerprint.lowercase())).verify(caller(), "example.com", "AQID", "webauthn.get", null).browserDelegated)
    }

    @Test fun malformedOversizedAndInvalidUtf8DalFailClosed() {
        assertFalse(CallerVerifier.matchesAssetLinks(ByteArray(StrictJson.MAX_BYTES + 1), "test.native", fingerprint))
        assertFalse(CallerVerifier.matchesAssetLinks(byteArrayOf(0xc3.toByte(), 0x28), "test.native", fingerprint))
        assertFalse(CallerVerifier.matchesAssetLinks(("[".repeat(34) + "0" + "]".repeat(34)).toByteArray(), "test.native", fingerprint))
        val failedFetch = CallerVerifier(AssetLinksFetcher { throw java.io.IOException() })
        assertThrows(CallerVerificationException::class.java) { failedFetch.verify(caller(), "example.com", "AQID", "webauthn.get", null) }
    }

    @Test fun sdkAcceptedHistoricalSignerCannotAuthorizeUntrustedCurrentSigner() {
        assertThrows(CallerVerificationException::class.java) {
            CallerVerifier.verifiedOrigin(CallerVerifier.CHROME_PACKAGE,
                { "https://example.com" }, { listOf(certificate) })
        }
        assertThrows(CallerVerificationException::class.java) {
            CallerVerifier.verifiedOrigin(CallerVerifier.CHROME_PACKAGE,
                { "https://example.com" }, { emptyList() })
        }
        var signerReads = 0
        assertThrows(CallerVerificationException::class.java) {
            CallerVerifier.verifiedOrigin(CallerVerifier.CHROME_PACKAGE,
                { throw IllegalStateException() }, { signerReads++; listOf(certificate) })
        }
        assertEquals(0, signerReads)
        assertNull(CallerVerifier.verifiedOrigin("test.native", { null }, {
            throw AssertionError("Native signers are validated by the native path")
        }))
    }

    @Test fun allowlistIsOnlyTheApprovedChromeReleaseCertificate() {
        val apps = JsonParser.parseString(CallerVerifier.privilegedAllowlist).asJsonObject["apps"].asJsonArray
        assertEquals(1, apps.size())
        val app = apps[0].asJsonObject
        assertEquals("android", app["type"].asString)
        val info = app["info"].asJsonObject
        assertEquals(CallerVerifier.CHROME_PACKAGE, info["package_name"].asString)
        val signatures = info["signatures"].asJsonArray
        assertEquals(1, signatures.size())
        assertEquals("release", signatures[0].asJsonObject["build"].asString)
        assertEquals(CallerVerifier.CHROME_FINGERPRINT, signatures[0].asJsonObject["cert_fingerprint_sha256"].asString)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
