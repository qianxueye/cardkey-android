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
                assertEquals("test-operator", reader.state.value.identity?.id)
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
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
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
                assertNull(reader.state.value.person); assertNull(reader.state.value.identity?.id)
                assertEquals(CardArtworkState.Default, reader.front.state.value)
                reader.connect("https://issuer-b.invalid", "") ; awaitReady(reader)
                assertEquals(R.string.issuer_token_required, reader.state.value.error)
                assertTrue(b.requests.isEmpty())
                reader.connect("https://issuer-b.invalid", "token-b"); awaitReady(reader)
                delay(600)
                assertEquals("Endpoint B Person", reader.state.value.people.single().name)
                assertNull(reader.state.value.card)
                assertTrue(b.requests.all { it.second == "Bearer token-b" })
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
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
                withTimeout(5000) { while (reader.state.value.identity?.id != null) delay(10) }
                assertNull(reader.state.value.revision); assertNull(secrets.token("https://issuer.invalid"))
                assertEquals(R.string.issuer_unauthorized, reader.state.value.error)
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun redirectsAreRefusedWithoutCredentialLeakAndMediaLimitsAreEnforced() = runBlocking {
        IssuerFixture().use { a -> IssuerFixture().use { b ->
            val api = IssuerClient(a.origin, "token-a")
            a.failure = 302; a.redirect = "${b.origin}/v1/me"
            try { api.me(); fail("redirect accepted") } catch (e: IssuerFailure) { assertEquals(302, e.status) }
            assertTrue(b.requests.isEmpty())
            a.failure = 0
            api.me()
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
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
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
                assertEquals(R.string.issuer_missing, reader.state.value.revisionsError)
                assertNull(reader.state.value.revision); assertEquals(CardArtworkState.Default, reader.front.state.value)
                fixture.failure = 401
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                assertNull(reader.state.value.identity?.id); assertTrue(reader.state.value.people.isEmpty())
                assertEquals(R.string.issuer_unauthorized, reader.state.value.error)
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun failedCardsRemainFailedAfterUnrelatedPeoplePageSucceeds() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.peoplePages = true; fixture.peopleFirstPageHasPerson = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "token"); awaitReady(reader)
                fixture.failure = 503; fixture.failurePath = "/v1/cards"
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                assertEquals(R.string.issuer_failed, reader.state.value.cardsError)
                assertFalse(reader.state.value.cardsLoaded)
                reader.morePeople(); awaitReady(reader)
                assertEquals("", reader.state.value.peopleCursor)
                assertEquals(R.string.issuer_failed, reader.state.value.cardsError)
                assertFalse(reader.state.value.cardsLoaded)
                fixture.failure = 0
                reader.retryCards(); awaitReady(reader)
                assertNull(reader.state.value.cardsError); assertTrue(reader.state.value.cardsLoaded)
                assertEquals(fixture.cardID, reader.state.value.cards.single().id)
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun failedHistoryRemainsFailedAfterUnrelatedPeoplePageSucceeds() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.peoplePages = true; fixture.peopleFirstPageHasPerson = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "token"); awaitReady(reader)
                reader.selectPerson(reader.state.value.people.single()); awaitReady(reader)
                fixture.failure = 503; fixture.failurePath = "/v1/cards/${fixture.cardID}/badge-revisions"
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader)
                assertEquals(R.string.issuer_failed, reader.state.value.revisionsError)
                assertFalse(reader.state.value.revisionsLoaded)
                reader.morePeople(); awaitReady(reader)
                assertEquals("", reader.state.value.peopleCursor)
                assertEquals(R.string.issuer_failed, reader.state.value.revisionsError)
                assertFalse(reader.state.value.revisionsLoaded)
                fixture.failure = 0
                reader.retryRevisions(); awaitReady(reader); awaitImages(reader)
                assertNull(reader.state.value.revisionsError); assertTrue(reader.state.value.revisionsLoaded)
                assertEquals(fixture.revisionID, reader.state.value.revision!!.id)
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun rejectedReplacementRetainsSavedTokenButRejectedSavedTokenIsRemoved() = runBlocking {
        IssuerFixture().use { fixture ->
            val store = secrets()
            store.save("https://issuer.invalid", "token-a")
            fixture.rejectedTokens = setOf("token-b")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(store, scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "token-b"); awaitReady(reader)
                assertEquals(R.string.issuer_unauthorized, reader.state.value.error)
                assertEquals("token-a", store.token("https://issuer.invalid"))
                fixture.rejectedTokens = setOf("token-a")
                reader.connect("https://issuer.invalid", ""); awaitReady(reader)
                assertEquals(R.string.issuer_unauthorized, reader.state.value.error)
                assertNull(store.token("https://issuer.invalid"))
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun delayedRejectionCannotEraseNewerSavedCredentialOrChangedConnection() = runBlocking {
        IssuerFixture().use { fixture ->
            val store = secrets()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(store, scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                fixture.delayPath = "/v1/me"; fixture.delayToken = "token-a"; fixture.delayMillis = 300
                fixture.rejectedTokens = setOf("token-a")
                store.save("https://issuer.invalid", "token-a")
                reader.connect("https://issuer.invalid", "token-a")
                withContext(Dispatchers.IO) { assertTrue(fixture.enteredDelay.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                store.save("https://issuer.invalid", "token-b")
                awaitReady(reader)
                assertEquals(R.string.issuer_unauthorized, reader.state.value.error)
                assertEquals("token-b", store.token("https://issuer.invalid"))
                for (nextOrigin in listOf("https://issuer.invalid", "https://other.invalid")) {
                    fixture.enteredDelay = java.util.concurrent.CountDownLatch(1)
                    reader.connect("https://issuer.invalid", "token-a")
                    withContext(Dispatchers.IO) { assertTrue(fixture.enteredDelay.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                    reader.connect(nextOrigin, "token-b"); awaitReady(reader)
                    delay(400)
                    assertEquals("test-operator", reader.state.value.identity?.id)
                    assertEquals(nextOrigin, reader.state.value.origin)
                    assertEquals("token-b", store.token(nextOrigin))
                    assertEquals("token-b", store.token("https://issuer.invalid"))
                }
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun employeeReadsOnlyBoundProfileCardsAndCurrentArtwork() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.role = "employee"; fixture.cardPages = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "employee-token"); awaitReady(reader)
                assertEquals("test-operator", reader.state.value.identity?.id)
                assertEquals(fixture.personID, reader.state.value.person!!.id)
                assertTrue(reader.state.value.people.isEmpty())
                assertEquals("second", reader.state.value.cardsCursor)
                reader.moreCards(); awaitReady(reader)
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader); awaitImages(reader)
                assertEquals(fixture.revisionID, reader.state.value.revision!!.id)
                assertTrue(reader.front.state.value is CardArtworkState.Loaded)
                assertTrue(reader.back.state.value is CardArtworkState.Loaded)
                assertTrue(reader.state.value.revisions.isEmpty())
                assertTrue(fixture.requests.all { it.first == "/v1/me" || it.first.startsWith("/v1/me/") })
                assertFalse(fixture.requests.any { it.first.contains("person_id=") || it.first.contains("badge-revisions?") })
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun unknownRoleCannotLoadStaffRecords() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.role = "future-admin"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "token"); awaitReady(reader)
                assertNull(reader.state.value.identity?.id)
                assertTrue(reader.state.value.people.isEmpty())
                assertEquals(listOf("/v1/me"), fixture.requests.map { it.first })
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun malformedRoleCapabilitiesAndBindingsFailBeforeRecordReads() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.role = "employee"
            val base = com.google.gson.JsonParser.parseString(fixture.identity()).asJsonObject
            val cases = listOf<(com.google.gson.JsonObject) -> Unit>(
                { it.getAsJsonObject("operator").remove("role") },
                { it.getAsJsonObject("operator").addProperty("role", 1) },
                { it.getAsJsonObject("operator").remove("person_id") },
                { it.getAsJsonObject("operator").add("person_id", com.google.gson.JsonNull.INSTANCE) },
                { it.getAsJsonObject("operator").addProperty("person_id", "wrong") },
                { it.getAsJsonObject("operator").addProperty("role", "operator") },
                { it.getAsJsonObject("operator").addProperty("administrator", true) },
                { it.getAsJsonObject("operator").addProperty("id", 123) },
                { it.getAsJsonObject("operator").addProperty("display_name", " ") },
                { it.addProperty("capabilities", "self:read") },
                { it.getAsJsonArray("capabilities").add("cards:read") },
                { it.getAsJsonArray("capabilities").add("unknown:read") },
                { it.getAsJsonArray("capabilities").add("self:read") },
                { it.getAsJsonArray("capabilities").remove(0) },
                { it.getAsJsonArray("capabilities").set(0, com.google.gson.JsonPrimitive(1)) },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val store = secrets()
            val reader = IssuerReader(store, scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                cases.forEachIndexed { index, mutate ->
                    val malformed = base.deepCopy(); mutate(malformed); fixture.identityOverride = malformed.toString()
                    fixture.requests.clear()
                    reader.connect("https://issuer.invalid", "token"); awaitReady(reader)
                    assertNull("case $index", reader.state.value.identity)
                    assertEquals("case $index", R.string.issuer_invalid_identity, reader.state.value.error)
                    assertEquals(listOf("/v1/me"), fixture.requests.map { it.first })
                    assertNull(store.token("https://issuer.invalid"))
                }
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun administratorRetainsStaffReadsAndEmployeeBindingsAreChecked() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.role = "administrator"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "admin-token"); awaitReady(reader)
                assertEquals(IssuerRole.ADMINISTRATOR, reader.state.value.identity!!.role)
                assertEquals(1, reader.state.value.people.size)
                fixture.role = "employee"; fixture.selfPersonOverride = fixture.oldID
                fixture.requests.clear()
                reader.connect("https://issuer.invalid", "employee-token"); awaitReady(reader)
                assertNull(reader.state.value.person)
                assertEquals(R.string.issuer_invalid_response, reader.state.value.error)
                assertFalse(fixture.requests.any { it.first.startsWith("/v1/me/cards") })
                fixture.selfPersonOverride = null; fixture.selfCardPersonOverride = fixture.oldID
                reader.connect("https://issuer.invalid", "employee-token"); awaitReady(reader)
                assertEquals(fixture.personID, reader.state.value.person!!.id)
                assertTrue(reader.state.value.cards.isEmpty())
                assertFalse(reader.state.value.cardsLoaded)
                assertEquals(R.string.issuer_invalid_response, reader.state.value.error)
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun staffToEmployeeSwitchRetiresDelayedArtworkAndStaffActions() = runBlocking {
        IssuerFixture().use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets(), scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "staff-token"); awaitReady(reader)
                val previousPerson = reader.state.value.people.single()
                reader.selectPerson(previousPerson); awaitReady(reader)
                fixture.delayPath = "/v1/badge-revisions/${fixture.revisionID}/front"; fixture.delayMillis = 400
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader)
                val previousRevision = reader.state.value.revision!!
                withContext(Dispatchers.IO) { assertTrue(fixture.enteredDelay.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                fixture.role = "employee"; fixture.personName = "Employee self profile"
                reader.connect("https://issuer.invalid", "employee-token")
                assertEquals(CardArtworkState.Default, reader.front.state.value)
                awaitReady(reader)
                val count = fixture.requests.size
                reader.morePeople(); reader.selectPerson(previousPerson)
                reader.moreRevisions(); reader.selectRevision(previousRevision)
                assertEquals(count, fixture.requests.size)
                assertTrue(reader.state.value.people.isEmpty()); assertTrue(reader.state.value.revisions.isEmpty())
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader); awaitImages(reader)
                delay(500)
                assertEquals(IssuerRole.EMPLOYEE, reader.state.value.identity!!.role)
                assertEquals("Employee self profile", reader.state.value.person!!.name)
                assertNull(reader.state.value.revision!!.actor); assertNull(reader.state.value.revision!!.sourceCreated)
                assertTrue(reader.front.state.value is CardArtworkState.Loaded)
                reader.close()
                assertNull(reader.state.value.identity); assertNull(reader.state.value.person)
                assertEquals(CardArtworkState.Default, reader.front.state.value)
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun employeeMissingImageRequiresCurrentRefreshAndForbiddenClearsAuthority() = runBlocking {
        IssuerFixture().use { fixture ->
            fixture.role = "employee"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val store = secrets()
            val reader = IssuerReader(store, scope) { _, token -> IssuerClient(fixture.origin, token) }
            try {
                reader.connect("https://issuer.invalid", "employee-token"); awaitReady(reader)
                fixture.mediaFailure = 404
                reader.selectCard(reader.state.value.cards.single()); awaitReady(reader); awaitImages(reader)
                withTimeout(5000) { while (reader.state.value.revisionsError == null) delay(10) }
                assertNull(reader.state.value.revision); assertNull(reader.state.value.current)
                assertEquals(R.string.issuer_artwork_unavailable, reader.state.value.revisionsError)
                assertEquals(CardArtworkState.Default, reader.front.state.value)
                fixture.mediaFailure = 0
                reader.retryRevisions(); awaitReady(reader); awaitImages(reader)
                assertTrue(reader.front.state.value is CardArtworkState.Loaded)
                assertEquals(2, fixture.requests.count { it.first.endsWith("/badge-current") })
                fixture.mediaFailure = 403
                reader.retryArtwork(); awaitReady(reader); awaitImages(reader)
                withTimeout(5000) { while (reader.state.value.identity != null) delay(10) }
                assertNull(reader.state.value.person); assertTrue(reader.state.value.cards.isEmpty())
                assertNull(reader.state.value.revision)
                assertEquals(R.string.issuer_forbidden, reader.state.value.error)
                assertEquals("employee-token", store.token("https://issuer.invalid"))
                fixture.mediaFailure = 0; fixture.failure = 403; fixture.failurePath = "/v1/me/person"
                reader.connect("https://issuer.invalid", "employee-token"); awaitReady(reader)
                assertNull(reader.state.value.identity); assertNull(reader.state.value.person)
                assertEquals(R.string.issuer_forbidden, reader.state.value.error)
            } finally { reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
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
        store.forget("https://a.invalid", "synthetic-secret"); assertNull(store.token("https://a.invalid"))
    }
    @Test fun originsRejectCleartextCredentialsPathsQueriesAndFragments() {
        listOf("http://issuer.invalid", "https://u:p@issuer.invalid", "https://issuer.invalid/path", "https://issuer.invalid/?a=b", "https://issuer.invalid/#x").forEach {
            assertTrue(runCatching { issuerOrigin(it) }.isFailure)
        }
        assertEquals("https://issuer.invalid", issuerOrigin("HTTPS://ISSUER.INVALID:443/"))
    }
}
