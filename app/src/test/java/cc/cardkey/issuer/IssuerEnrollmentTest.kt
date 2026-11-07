package cc.cardkey.issuer

import cc.cardkey.enrollment.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssuerEnrollmentTest {
    private val id = "55555555-5555-4555-8555-555555555555"
    private val key = "66666666-6666-4666-8666-666666666666"
    private fun expected(fixture: IssuerFixture, purpose: EnrollmentPurpose = EnrollmentPurpose.TLS): IssuerIssuanceExpectation {
        val identity = EnrollmentIdentity("EMP-001", "Synthetic Person", "synthetic@apture.cc", "synthetic@apture.cc")
        val selection = EnrollmentSelection(purpose)
        val keys = if (purpose == EnrollmentPurpose.KEY_MANAGEMENT) KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            else KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val der = JcaPKCS10CertificationRequestBuilder(EnrollmentProfile.name(identity.displayName), keys.public)
            .build(JcaContentSignerBuilder(if (purpose == EnrollmentPurpose.KEY_MANAGEMENT) "SHA256withRSA" else "SHA256withECDSA").build(keys.private)).encoded
        return IssuerIssuanceExpectation(fixture.personID, fixture.cardID, "test-ca", EnrollmentCsr(der, identity, selection))
    }
    private fun row(expected: IssuerIssuanceExpectation): JsonObject = expected.request().apply {
        addProperty("id", id); addProperty("outcome", "pending"); addProperty("certificate_id", "")
        addProperty("actor", "synthetic"); addProperty("created_at", "2026-11-06T00:00:00Z"); addProperty("updated_at", "2026-11-06T00:00:00Z")
        add("certificate_identity", JsonObject().apply {
            addProperty("employee_reference", expected.csr.identity.employeeReference)
            addProperty("display_name", expected.csr.identity.displayName)
            addProperty("upn", expected.csr.identity.upn); addProperty("email", expected.csr.identity.email)
        })
    }
    @Test fun frozenIdentityAndEveryRequestFieldMustMatchBeforeMint() = runBlocking {
        IssuerFixture().use { fixture ->
            val expected = expected(fixture)
            val client = IssuerClient(fixture.origin, "synthetic"); client.me()
            for (field in listOf("person_id", "card_id", "ca_id", "csr_der", "slot", "algorithm", "purpose", "common_name", "employee_reference", "display_name", "upn", "email")) {
                val value = row(expected)
                if (field in setOf("employee_reference", "display_name", "upn", "email")) value.getAsJsonObject("certificate_identity").addProperty(field, "mismatch@apture.cc")
                else value.addProperty(field, when (field) {
                    "person_id", "card_id" -> key
                    else -> "mismatch"
                })
                fixture.enrollmentResponse = { _, _, _ -> 200 to value.toString() }
                assertTrue("Mismatch $field must stop mint", runCatching { client.mintIssuanceSigningToken(id, key, expected) }.isFailure)
            }
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun putReadbackAndTokenUsePinnedRoutesUuidKeyAndPurpose() = runBlocking {
        IssuerFixture().use { fixture ->
            val client = IssuerClient(fixture.origin, "synthetic"); client.me()
            for (purpose in EnrollmentPurpose.entries) {
                val expected = expected(fixture, purpose)
                fixture.enrollmentResponse = { _, path, _ ->
                    200 to if (path.endsWith("/token")) """{"token":"synthetic.jwt.signature","jti":"synthetic-jti","exp":2000000000,"provisioner_role":"${expected.purpose}"}""" else row(expected).toString()
                }
                client.registerIssuance(id, key, expected.personID, expected.cardID, expected.caID, expected.csr)
                assertEquals(expected.csr.identity, client.issuance(id).identity)
                assertEquals(expected.purpose, client.mintIssuanceSigningToken(id, key, expected).provisionerRole)
                val put = fixture.mutations[fixture.mutations.size - 2]
                assertEquals("PUT", put.first)
                assertEquals(expected.request(), JsonParser.parseString(put.third))
                val mint = fixture.mutations.last()
                assertEquals("POST", mint.first); assertEquals(key, mint.second["idempotency-key"])
                assertEquals("no-store", mint.second["cache-control"])
                assertEquals("{}", mint.third)
                assertEquals("/v1/issuances/$id/token", fixture.requests.last().first)
            }
        }
    }
    @Test fun mint503And408WithRetryAfterZeroAreNeverRetried() = runBlocking {
        IssuerFixture().use { fixture ->
            val expected = expected(fixture)
            val client = IssuerClient(fixture.origin, "synthetic"); client.me()
            for ((status, code) in listOf(503 to "signing_unavailable", 408 to "busy", 409 to "signing_token_not_replayable", 409 to "signing_token_live")) {
                fixture.enrollmentResponse = { _, path, _ -> if (path.endsWith("/token")) status to """{"error":{"code":"$code"}}""" else 200 to row(expected).toString() }
                val before = fixture.mutations.size
                val error = runCatching { client.mintIssuanceSigningToken(id, key, expected) }.exceptionOrNull() as IssuerEnrollmentFailure
                assertEquals(status, error.status); assertEquals(IssuerMintError.fromCode(code), error.reason)
                assertEquals(before + 1, fixture.mutations.size)
            }
            assertEquals(IssuerMintError.UNKNOWN, IssuerMintError.fromCode("future_error"))
            for (error in IssuerMintError.entries.filter { it != IssuerMintError.UNKNOWN }) assertEquals(error, IssuerMintError.fromCode(error.code))
        }
    }
    @Test fun employeeCannotRegisterReadOrMint() = runBlocking {
        IssuerFixture().use { fixture ->
            val expected = expected(fixture)
            fixture.role = "employee"
            val client = IssuerClient(fixture.origin, "synthetic"); client.me()
            val before = fixture.requests.size
            assertTrue(runCatching { client.registerIssuance(id, key, expected.personID, expected.cardID, expected.caID, expected.csr) }.isFailure)
            assertTrue(runCatching { client.issuance(id) }.isFailure)
            assertTrue(runCatching { client.mintIssuanceSigningToken(id, key, expected) }.isFailure)
            assertEquals(before, fixture.requests.size)
        }
    }
    @Test fun personUpnAndEmailMustBePresentJsonStrings() = runBlocking {
        IssuerFixture().use { fixture ->
            val client = IssuerClient(fixture.origin, "synthetic"); client.me()
            assertEquals("synthetic@apture.cc", client.people().items.single().upn)
            val original = fixture.person()
            for (field in listOf("upn", "email")) {
                for (variant in 0..2) {
                    fixture.personOverride = JsonParser.parseString(original).asJsonObject.apply {
                        when (variant) {
                            0 -> remove(field)
                            1 -> addProperty(field, 123)
                            else -> addProperty(field, "x".repeat(255))
                        }
                    }.toString()
                    assertTrue(runCatching { client.people() }.isFailure)
                }
            }
        }
    }
    @Test fun tokenResponseIsBoundedAndRedirectAndInvalidUuidNeverReplayMutation() = runBlocking {
        IssuerFixture().use { fixture ->
            val expected = expected(fixture)
            val client = IssuerClient(fixture.origin, "synthetic"); client.me()
            fixture.enrollmentResponse = { _, path, _ -> if (path.endsWith("/token")) 200 to "x".repeat(65537) else 200 to row(expected).toString() }
            assertTrue(runCatching { client.mintIssuanceSigningToken(id, key, expected) }.isFailure)
            assertEquals(1, fixture.mutations.size)
            fixture.enrollmentResponse = { _, path, _ -> if (path.endsWith("/token")) 302 to "{}" else 200 to row(expected).toString() }
            assertTrue(runCatching { client.mintIssuanceSigningToken(id, key, expected) }.isFailure)
            assertEquals(2, fixture.mutations.size)
            val requests = fixture.requests.size
            assertTrue(runCatching { client.mintIssuanceSigningToken(id, "invalid-key", expected) }.isFailure)
            assertEquals(requests, fixture.requests.size)
        }
    }
    @Test fun unknownResultUsesPutAndDoesNotSendCertificateOrChain() = runBlocking {
        IssuerFixture().use { fixture ->
            val expected = expected(fixture)
            val client = IssuerClient(fixture.origin, "synthetic"); client.me()
            fixture.enrollmentResponse = { _, _, _ -> 200 to row(expected).apply { addProperty("outcome", "unknown") }.toString() }
            assertEquals("unknown", client.reportIssuanceResult(id, key, expected, "unknown").outcome)
            val request = fixture.mutations.single()
            assertEquals("PUT", request.first)
            assertEquals("/v1/issuances/$id/result", fixture.requests.last().first)
            assertEquals("{\"outcome\":\"unknown\"}", request.third)
        }
    }

}
