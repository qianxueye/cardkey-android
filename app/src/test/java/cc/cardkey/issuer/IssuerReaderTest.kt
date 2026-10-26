package cc.cardkey.issuer

import android.content.Context
import cc.cardkey.R
import cc.cardkey.ui.CardArtworkState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssuerReaderTest {
    private fun secrets(): IssuerSecrets {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(java.util.UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return IssuerSecrets(prefs) { key }
    }
    private suspend fun awaitReady(reader: IssuerReader) { withTimeout(10000) { while (reader.state.value.busy) delay(10) } }
    private suspend fun awaitImages(reader: IssuerReader) { withTimeout(10000) { while (reader.front.state.value is CardArtworkState.Loading || reader.back.state.value is CardArtworkState.Loading) delay(10) } }
    @Test fun actualHttpContractPaginationHistoricalSelectionAndImages() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.peoplePages = true; fixture.cardPages = true; fixture.historyPages = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer-a.invalid", "synthetic-token"); awaitReady(reader)
                assertEquals("test-operator", reader.state.value.actor)
                assertTrue(reader.state.value.people.isEmpty()); reader.morePeople(); awaitReady(reader)
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                assertFalse(reader.state.value.person!!.active)
                reader.moreCards(); awaitReady(reader)
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader); awaitImages(reader)
                assertEquals("lost", reader.state.value.card!!.status)
                assertEquals("Historical Person", reader.state.value.revision!!.name)
                assertTrue(reader.front.state.value is CardArtworkState.Loaded)
                assertTrue(reader.back.state.value is CardArtworkState.Loaded)
                reader.moreRevisions(); awaitReady(reader)
                reader.selectRevision(reader.state.value.revisions.last()); awaitImages(reader)
                assertEquals(fixture.oldID, reader.state.value.revision!!.id)
                assertEquals(fixture.revisionID, reader.state.value.current)
                assertTrue(fixture.requests.all { it.second == "Bearer synthetic-token" })
                assertTrue(fixture.requests.any { it.first == "/v1/badge-revisions/${fixture.oldID}/back" })
            } finally { reader.close(); scope.cancel() }
        }
    }
    @Test fun endpointAndOperatorEditsRetireDelayedIdenticalRecordLoads() = runBlocking {
        IssuerFixture().use { a -> IssuerFixture().use { b ->
            b.personName = "Endpoint B Person"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { origin, token -> IssuerClient(if (origin.contains("issuer-a")) a.origin else b.origin, token) }
            try {
                reader.connect("https://issuer-a.invalid", "token-a"); awaitReady(reader)
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                a.delayPath = "/v1/cards/${a.cardID}/badge-current"; a.delayMillis = 500
                reader.selectCard(reader.state.value.cards.single())
                withContext(Dispatchers.IO) { assertTrue(a.enteredDelay.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                reader.editConnection()
                assertNull(reader.state.value.person); assertNull(reader.state.value.actor)
                assertEquals(CardArtworkState.Default, reader.front.state.value)
                reader.connect("https://issuer-b.invalid", "") ; awaitReady(reader)
                assertEquals(R.string.issuer_token_required, reader.state.value.error)
                assertTrue(b.requests.isEmpty())
                reader.connect("https://issuer-b.invalid", "token-b"); awaitReady(reader)
                delay(600)
                assertEquals("Endpoint B Person", reader.state.value.people.single().name)
                assertNull(reader.state.value.card)
                assertTrue(b.requests.all { it.second == "Bearer token-b" })
            } finally { reader.close(); scope.cancel() }
        } }
    }
    @Test fun mediaUnauthorizedClearsIdentityAndSavedCredential() = runBlocking {
        IssuerFixture().use { fixture ->
            val secrets = secrets()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets, scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "token"); awaitReady(reader)
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                fixture.mediaFailure = 401
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader); awaitImages(reader)
                withTimeout(5000) { while (reader.state.value.actor != null) delay(10) }
                assertNull(reader.state.value.revision); assertNull(secrets.token("https://issuer.invalid"))
                assertEquals(R.string.issuer_unauthorized, reader.state.value.error)
            } finally { reader.close(); scope.cancel() }
        }
    }
    @Test fun redirectsAreRefusedWithoutCredentialLeakAndMediaLimitsAreEnforced() = runBlocking {
        IssuerFixture().use { a -> IssuerFixture().use { b ->
            val api = IssuerClient(a.origin, "token-a")
            a.failure = 302; a.redirect = "${b.origin}/v1/me"
            try { api.me(); fail("redirect accepted") } catch (e: IssuerFailure) { assertEquals(302, e.status) }
            assertTrue(b.requests.isEmpty())
            a.failure = 0
            val card = api.cards(a.personID).items.single()
            val revision = api.current(card)!!
            a.mediaBytes = ByteArray(1_048_577)
            try { api.artwork(revision, false); fail("oversized image accepted") } catch (_: IllegalArgumentException) { }
            a.mediaBytes = null; a.mediaType = "text/html"
            try { api.artwork(revision, false); fail("invalid media accepted") } catch (_: IllegalArgumentException) { }
            a.mediaType = "image/png"; a.mediaBytes = ByteArray(a.png.size)
            try { api.artwork(revision, false); fail("corrupt image accepted") } catch (_: IllegalArgumentException) { }
        } }
    }
    @Test fun changingRevisionAndPersonCancelsDelayedImages() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.historyPages = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "token"); awaitReady(reader)
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                fixture.delayPath = "/v1/badge-revisions/${fixture.revisionID}/front"; fixture.delayMillis = 500
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader)
                withContext(Dispatchers.IO) { assertTrue(fixture.enteredDelay.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                reader.moreRevisions(); awaitReady(reader)
                reader.selectRevision(reader.state.value.revisions.last()); awaitImages(reader)
                delay(600)
                assertEquals("Synthetic historical portrait card", (reader.front.state.value as CardArtworkState.Loaded).label)
                reader.selectPerson(reader.state.value.people.single())
                assertNull(reader.state.value.revision)
                assertEquals(CardArtworkState.Default, reader.front.state.value)
                awaitReady(reader)
            } finally { reader.close(); scope.cancel() }
        }
    }
    @Test fun missingCardRetiresPriorImagesAndUnauthorizedMetadataClearsSession() = runBlocking {
        IssuerFixture().use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "token"); awaitReady(reader)
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader); awaitImages(reader)
                fixture.currentMissing = true; fixture.historyMissing = true
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader)
                assertEquals(R.string.issuer_missing, reader.state.value.error)
                assertNull(reader.state.value.revision); assertEquals(CardArtworkState.Default, reader.front.state.value)
                fixture.failure = 401
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                assertNull(reader.state.value.actor); assertTrue(reader.state.value.people.isEmpty())
                assertEquals(R.string.issuer_unauthorized, reader.state.value.error)
            } finally { reader.close(); scope.cancel() }
        }
    }
    @Test fun encryptedCredentialsAreBoundToOriginAndNeverStoredAsPlaintext() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("issuer-secret-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = IssuerSecrets(prefs) { key }
        store.save("https://a.invalid", "synthetic-secret")
        assertFalse(prefs.all.values.any { it.toString().contains("synthetic-secret") })
        assertEquals("synthetic-secret", store.token("https://a.invalid"))
        assertNull(store.token("https://b.invalid"))
        prefs.edit().putString("credential:https://b.invalid", prefs.getString("credential:https://a.invalid", null)).commit()
        assertTrue(runCatching { store.token("https://b.invalid") }.isFailure)
        store.forget("https://a.invalid"); assertNull(store.token("https://a.invalid"))
    }
    @Test fun originsRejectCleartextCredentialsPathsQueriesAndFragments() {
        listOf("http://issuer.invalid", "https://u:p@issuer.invalid", "https://issuer.invalid/path", "https://issuer.invalid/?a=b", "https://issuer.invalid/#x").forEach {
            assertTrue(runCatching { issuerOrigin(it) }.isFailure)
        }
        assertEquals("https://issuer.invalid", issuerOrigin("HTTPS://ISSUER.INVALID:443/"))
    }
}
