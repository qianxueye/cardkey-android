package cc.cardkey.provider

import org.junit.Assert.*
import org.junit.Test

class RequestParserTest {
    private val create = """{"rp":{"id":"Example.com","name":"Example"},"user":{"id":"AQID","name":"alice","displayName":"Alice"},"challenge":"BAUG","pubKeyCredParams":[{"type":"public-key","alg":-257},{"type":"public-key","alg":-7}],"authenticatorSelection":{"residentKey":"required","userVerification":"required","authenticatorAttachment":"cross-platform"},"excludeCredentials":[{"type":"public-key","id":"BwgJ","transports":["nfc","usb"]}],"attestation":"direct"}"""
    private val get = """{"rpId":"example.com","challenge":"AQID","allowCredentials":[{"id":"BAUG","type":"public-key"}],"userVerification":"discouraged"}"""

    @Test fun preservesCreateIntentAndExactRp() {
        val parsed = RequestParser.parseCreate(create)
        assertEquals("Example.com", parsed.rpId)
        assertEquals("Example", parsed.rpName)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.user.id)
        assertEquals("alice", parsed.user.name)
        assertEquals("Alice", parsed.user.displayName)
        assertEquals("BAUG", parsed.challenge)
        assertEquals(ResidentKeyRequirement.REQUIRED, parsed.residentKey)
        assertEquals(UserVerificationRequirement.REQUIRED, parsed.userVerification)
        assertEquals(AttestationPreference.DIRECT, parsed.attestation)
        assertArrayEquals(byteArrayOf(7, 8, 9), parsed.excludeIds.single())
    }

    @Test fun preservesGetAllowListAndUv() {
        val parsed = RequestParser.parseGet(get)
        assertArrayEquals(byteArrayOf(4, 5, 6), parsed.allowIds.single())
        assertEquals(UserVerificationRequirement.DISCOURAGED, parsed.userVerification)
        val discoverable = RequestParser.parseGet("""{"rpId":"example.com","challenge":"AQID"}""")
        assertTrue(discoverable.allowIds.isEmpty())
        assertEquals(UserVerificationRequirement.PREFERRED, discoverable.userVerification)
    }

    @Test fun legacyResidentRequirementOnlyAppliesWhenModernFieldIsAbsent() {
        assertEquals(ResidentKeyRequirement.REQUIRED, RequestParser.parseCreate(create.replace(
            "\"residentKey\":\"required\"", "\"requireResidentKey\":true")).residentKey)
        assertEquals(ResidentKeyRequirement.DISCOURAGED, RequestParser.parseCreate(create.replace(
            "\"residentKey\":\"required\"", "\"residentKey\":\"discouraged\",\"requireResidentKey\":true")).residentKey)
    }

    @Test fun refusesMalformedJsonAndAmbiguousDuplicateNames() {
        listOf(
            get.replace("\"rpId\"", "'rpId'"),
            get.replace("\"rpId\"", "rpId"),
            get.dropLast(1) + ",}",
            get + "{}",
            get.replace("\"rpId\":", "\"rpId\":\"evil.test\",\"rpId\":"),
            get.replace("\"rpId\":", "\"rp\\u0049d\":\"evil.test\",\"rpId\":"),
            get.replace("\"challenge\":\"AQID\"", "\"challenge\":null"),
            get.replace("\"challenge\":\"AQID\"", "\"challenge\":123"),
            get.replace("\"challenge\":\"AQID\"", "\"challenge\":true"),
        ).forEach { malformed -> assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(malformed) } }
    }

    @Test fun refusesNoncanonicalBase64AndInvalidDomainWithoutNormalizing() {
        listOf("", "A", "AB", "AQID=", "AQ+_", "AQ ID").forEach {
            assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(get.replace("AQID", it)) }
        }
        listOf(" example.com", "example.com.", "https://example.com", "example.com:443", "example.com/a",
            "example.com@evil.com", "example..com", "-example.com", "127.0.0.1", "[::1]", "exämple.com").forEach {
            assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(get.replace("example.com", it)) }
        }
    }

    @Test fun boundsJsonDepthChallengeUserAndDescriptors() {
        assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(" ".repeat(StrictJson.MAX_BYTES + 1) + get) }
        assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(get.dropLast(1) + ",\"x\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}") }
        assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(get.replace("AQID", "A".repeat(5464))) }
        assertThrows(ProviderRequestException::class.java) { RequestParser.parseCreate(create.replace("\"id\":\"AQID\"", "\"id\":\"${"A".repeat(88)}\"")) }
        val descriptor = """{"id":"BAUG","type":"public-key"}"""
        assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(get.replace(descriptor, List(65) { descriptor }.joinToString(","))) }
    }

    @Test fun rejectsUnsupportedRequirementsBeforeAnyCardInteraction() {
        listOf(
            create.replace("\"alg\":-7", "\"alg\":-8"),
            create.replace("cross-platform", "platform"),
            create.replace("\"attestation\":\"direct\"", "\"attestation\":\"enterprise\""),
        ).forEach {
            val failure = assertThrows(ProviderRequestException::class.java) { RequestParser.parseCreate(it) }
            assertTrue(failure.unsupported)
        }
        assertThrows(ProviderRequestException::class.java) { RequestParser.parseGet(get.dropLast(1) + ",\"extensions\":{\"appid\":\"https://example.com\"}}") }
    }

    @Test fun optionalExtensionsDoNotChangeTheRequestOrClaimResults() {
        val parsed = RequestParser.parseCreate(create.dropLast(1) + ",\"extensions\":{\"credProps\":true,\"largeBlob\":{\"support\":\"preferred\"},\"unknown\":true}}")
        assertEquals(ResidentKeyRequirement.REQUIRED, parsed.residentKey)
        assertEquals("BAUG", parsed.challenge)
    }

    @Test fun rejectsWrongTypesAndFractionalAlgorithms() {
        listOf(
            create.replace("\"alg\":-7", "\"alg\":-7.1"),
            create.replace("\"alg\":-7", "\"alg\":\"-7\""),
            create.replace("\"userVerification\":\"required\"", "\"userVerification\":true"),
            create.replace("\"residentKey\":\"required\"", "\"residentKey\":\"yes\""),
            create.dropLast(1) + ",\"timeout\":-1}",
        ).forEach { assertThrows(ProviderRequestException::class.java) { RequestParser.parseCreate(it) } }
    }
}
