package cc.cardkey.issuer

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import cc.cardkey.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.cardkey.ui.*
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protectedpiv.ProtectedPivState
import cc.cardkey.signing.SigningFileState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import javax.crypto.KeyGenerator
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w375dp-h812dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssuerBadgeLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun phone375RendersSelectedServerPortraitWithoutPhysicalOperation() = render(375, 812, 1f, "phone")
    @Test @Config(qualifiers = "zh-rCN-w375dp-h812dp-night-mdpi")
    fun darkPhoneKeepsRecordTextReadable() = render(375, 812, 1f, "dark-phone")
    @Test @Config(qualifiers = "zh-rCN-w812dp-h375dp-mdpi")
    fun landscapeLargeTextKeepsConnectionAndBothImagesReachable() = render(812, 375, 1.6f, "landscape")

    @Test @Config(qualifiers = "en-rUS-w375dp-h812dp-mdpi")
    fun englishPhoneUsesTranslatedIssuerControls() = render(375, 812, 1f, "english-phone")
    @Test @Config(qualifiers = "en-rUS-w375dp-h812dp-night-mdpi")
    fun englishDarkPhoneUsesTranslatedIssuerControls() = render(375, 812, 1f, "english-dark-phone")
    @Test @Config(qualifiers = "en-rUS-w812dp-h375dp-mdpi")
    fun englishLandscapeKeepsLongLabelsReachable() = render(812, 375, 1.6f, "english-landscape")

    @Test fun employeePhoneShowsOnlyOwnProfileAndCurrentArtwork() = render(375, 812, 1f, "employee-phone", "employee")
    @Test @Config(qualifiers = "en-rUS-w812dp-h375dp-mdpi")
    fun employeeEnglishLandscapeKeepsSelfViewReadable() = render(812, 375, 1.6f, "employee-english-landscape", "employee")
    @Test @Config(qualifiers = "zh-rCN-w375dp-h812dp-night-mdpi")
    fun employeeDarkPhoneShowsOwnBadge() = render(375, 812, 1f, "employee-dark-phone", "employee")
    @Test @Config(qualifiers = "en-rUS-w375dp-h812dp-mdpi")
    fun administratorEnglishPhoneRetainsStaffSelection() = render(375, 812, 1f, "administrator-english-phone", "administrator")

    private fun render(width: Int, height: Int, fontScale: Float, name: String, role: String = "operator") = runBlocking<Unit> {
        IssuerFixture().use { fixture ->
            fixture.role = role
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val secrets = IssuerSecrets(RuntimeEnvironment.getApplication().getSharedPreferences("layout-$name", Context.MODE_PRIVATE)) { key }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets, scope) { _, token -> IssuerClient(fixture.origin, token) }
            reader.connect("https://issuer.invalid", "synthetic-token")
            withTimeout(10000) { while (reader.state.value.busy) delay(10) }
            if (role != "employee") {
                reader.selectPerson(reader.state.value.people.single())
                withTimeout(10000) { while (reader.state.value.busy) delay(10) }
            }
            reader.selectCard(reader.state.value.cards.single())
            withTimeout(10000) { while (reader.state.value.busy || reader.front.state.value is CardArtworkState.Loading || reader.back.state.value is CardArtworkState.Loading) delay(10) }
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            try {
                activity.get().setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        CardKeyTheme { Box(Modifier.requiredSize(width.dp, height.dp)) {
                            ManagerScreen(ManagerState(), 0, {}, {}, { fail("physical operation dispatched") }, {}, {}, null, {}, {}, {}, {},
                                EnrollmentState(), { _, _ -> }, {}, { fail("enrollment dispatched") }, { _, _ -> fail("issuance dispatched") }, {},
                                TlsQualificationState(), {}, {}, {}, SigningFileState(), { _, _ -> }, {}, InventoryState(),
                                { _, _ -> }, { _, _ -> }, {}, {}, protectedPiv = ProtectedPivState(), onProtectedPivDocument = {},
                                onProtectedPivClear = {}, onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = {},
                                badgeContent = { IssuerBadgePanel(reader) })
                        } }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithText(activity.get().getString(R.string.issuer_title)).performScrollTo().performClick()
                compose.onNodeWithTag("issuer-records").assertExists()
                compose.onNodeWithText(activity.get().getString(R.string.issuer_address)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(activity.get().getString(R.string.issuer_connect)).performScrollTo().assertIsDisplayed()
                save("$name-connection", activity.get())
                if (role == "employee") {
                    compose.onNodeWithText(activity.get().getString(R.string.issuer_people)).assertDoesNotExist()
                    compose.onNodeWithText(activity.get().getString(R.string.issuer_more_history)).assertDoesNotExist()
                    compose.onNodeWithText(activity.get().getString(R.string.issuer_person_self)).performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText(activity.get().getString(R.string.issuer_archived, "2026-10-25T00:00:00Z")).assertDoesNotExist()
                } else compose.onNodeWithText(activity.get().getString(R.string.issuer_people)).assertExists()
                compose.onNodeWithText(activity.get().getString(R.string.issuer_snapshot)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Historical Person · EMP-001").assertExists()
                compose.onNodeWithText(activity.get().getString(R.string.issuer_back)).performScrollTo().assertIsDisplayed()
                compose.onAllNodesWithContentDescription("Synthetic portrait card")[1].performScrollTo().assertIsDisplayed()
                save("$name-artwork", activity.get())
                assertEquals(2, compose.onAllNodesWithContentDescription("Synthetic portrait card").fetchSemanticsNodes().size)
                compose.onNodeWithTag("issuer-token").performScrollTo().performClick()
                compose.onNodeWithTag("issuer-token").assertIsFocused()
                compose.runOnIdle {
                    val info = EditorInfo()
                    val editor = requireNotNull(findEditor(activity.get().window.decorView))
                    assertNotNull(editor.onCreateInputConnection(info))
                    assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
                    assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
                    assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
                    reader.editConnection()
                }
                compose.onNodeWithText("Historical Person · EMP-001").assertDoesNotExist()
                compose.onAllNodesWithContentDescription("Synthetic portrait card").assertCountEquals(0)
                if (height > width) {
                    compose.onNodeWithTag("native-home-back").performClick()
                    compose.onNodeWithTag("native-card-home").assertExists()
                    compose.onNode(hasText(activity.get().getString(R.string.navigation_passkeys)) and hasClickAction())
                        .performScrollTo().assertIsEnabled().performClick()
                    compose.onNodeWithTag("workspace-navigation").assertExists()
                }
            } finally { activity.pause().stop().destroy(); reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun cardsFailureStaysVisibleAfterSuccessfulPeoplePagination() = renderQueryFailure(false)
    @Test fun historyFailureStaysVisibleAfterSuccessfulPeoplePagination() = renderQueryFailure(true)

    private fun renderQueryFailure(history: Boolean) = runBlocking<Unit> {
        IssuerFixture().use { fixture ->
            fixture.peoplePages = true; fixture.peopleFirstPageHasPerson = true
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val secrets = IssuerSecrets(RuntimeEnvironment.getApplication().getSharedPreferences("query-failure-$history", Context.MODE_PRIVATE)) { key }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets, scope) { _, token -> IssuerClient(fixture.origin, token) }
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            try {
                reader.connect("https://issuer.invalid", "synthetic-token")
                withTimeout(10000) { while (reader.state.value.busy) delay(10) }
                if (history) {
                    reader.selectPerson(reader.state.value.people.single())
                    withTimeout(10000) { while (reader.state.value.busy) delay(10) }
                }
                fixture.failure = 503
                fixture.failurePath = if (history) "/v1/cards/${fixture.cardID}/badge-revisions" else "/v1/cards"
                if (history) reader.selectCard(reader.state.value.cards.single()) else reader.selectPerson(reader.state.value.people.single())
                withTimeout(10000) { while (reader.state.value.busy) delay(10) }
                reader.morePeople()
                withTimeout(10000) { while (reader.state.value.busy) delay(10) }
                activity.get().setContent { CardKeyTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { IssuerBadgePanel(reader) }
                } }
                compose.waitForIdle()
                val empty = if (history) R.string.issuer_no_badge else R.string.issuer_no_cards
                val retry = if (history) R.string.issuer_retry_revisions else R.string.issuer_retry_cards
                compose.onNodeWithText(activity.get().getString(empty)).assertDoesNotExist()
                compose.onNodeWithText(activity.get().getString(R.string.issuer_failed)).performScrollTo().assertIsDisplayed()
                fixture.failure = 0
                compose.onNodeWithText(activity.get().getString(retry)).performScrollTo().performClick()
                withTimeout(10000) { while (reader.state.value.busy) delay(10) }
                compose.waitForIdle()
                compose.onNodeWithText(activity.get().getString(R.string.issuer_failed)).assertDoesNotExist()
                compose.onNodeWithText(activity.get().getString(retry)).assertDoesNotExist()
                if (history) assertNotNull(reader.state.value.revision) else assertEquals(1, reader.state.value.cards.size)
            } finally { activity.pause().stop().destroy(); reader.close(); withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        }
    }
    @Test fun emptyPagesKeepPaginationAvailableUntilExhausted() = runBlocking<Unit> {
        IssuerFixture().use { fixture ->
            fixture.peoplePages = true; fixture.cardPages = true
            fixture.historyPages = true; fixture.historyFirstPageEmpty = true
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val secrets = IssuerSecrets(RuntimeEnvironment.getApplication().getSharedPreferences("empty-page-layout", Context.MODE_PRIVATE)) { key }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets, scope) { _, token -> IssuerClient(fixture.origin, token) }
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            suspend fun settle() {
                withTimeout(10000) { while (reader.state.value.busy) delay(10) }
                compose.waitForIdle()
            }
            fun text(resource: Int) = compose.onNodeWithText(activity.get().getString(resource))
            try {
                reader.connect("https://issuer.invalid", "synthetic-token")
                withTimeout(10000) { while (reader.state.value.busy) delay(10) }
                activity.get().setContent { CardKeyTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { IssuerBadgePanel(reader) }
                } }
                compose.waitForIdle()
                text(R.string.issuer_no_people).assertDoesNotExist()
                text(R.string.issuer_more_people).performScrollTo().performClick(); settle()
                val person = reader.state.value.people.single()
                compose.runOnIdle { reader.selectPerson(person) }; settle()
                text(R.string.issuer_no_cards).assertDoesNotExist()
                text(R.string.issuer_more_cards).performScrollTo().performClick(); settle()
                val card = reader.state.value.cards.single()
                compose.runOnIdle { reader.selectCard(card) }; settle()
                text(R.string.issuer_no_badge).assertDoesNotExist()
                text(R.string.issuer_more_history).performScrollTo().performClick(); settle()
                assertEquals(fixture.oldID, reader.state.value.revisions.single().id)

                fixture.cardsEmpty = true; fixture.cardPages = false
                compose.runOnIdle { reader.selectPerson(person) }; settle()
                text(R.string.issuer_no_cards).performScrollTo().assertIsDisplayed()
                fixture.cardsEmpty = false
                compose.runOnIdle { reader.selectPerson(person) }; settle()
                fixture.currentMissing = true; fixture.historyEmpty = true; fixture.historyPages = false
                compose.runOnIdle { reader.selectCard(card) }; settle()
                text(R.string.issuer_no_badge).performScrollTo().assertIsDisplayed()
                fixture.peopleEmpty = true; fixture.peoplePages = false
                compose.runOnIdle { reader.connect("https://issuer.invalid", "synthetic-token") }; settle()
                text(R.string.issuer_no_people).performScrollTo().assertIsDisplayed()
            } finally {
                activity.pause().stop().destroy(); reader.close()
                withTimeout(10000) { scope.coroutineContext[Job]!!.cancelAndJoin() }
            }
        }
    }
    private fun findEditor(view: android.view.View): android.view.View? {
        if (view.onCheckIsTextEditor()) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private fun save(name: String, activity: ComponentActivity) {
        val view = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val directory = File("build/ui-previews/issuer").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
