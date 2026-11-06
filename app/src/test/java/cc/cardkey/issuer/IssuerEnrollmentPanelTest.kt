package cc.cardkey.issuer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import cc.cardkey.R
import cc.cardkey.enrollment.EnrollmentIdentity
import cc.cardkey.enrollment.EnrollmentTarget
import cc.cardkey.ui.CardKeyTheme
import cc.cardkey.ui.EnrollmentIssuerPanel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w375dp-h812dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssuerEnrollmentPanelTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun reader(fixture: IssuerFixture, scope: CoroutineScope): IssuerReader {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("enrollment-panel-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return IssuerReader(IssuerSecrets(prefs) { key }, scope) { _, token -> IssuerClient(fixture.origin, token) }
    }
    private suspend fun ready(reader: IssuerReader) = withTimeout(10000) { while (reader.state.value.busy) delay(10) }

    @Test fun selectingPersonAndIssuedCardFreezesExactServerIdentityAndIds() = runBlocking<Unit> {
        IssuerFixture().use { fixture ->
            fixture.personOverride = fixture.person().replace("\"employment_status\":\"inactive\"", "\"employment_status\":\"active\"")
                .replace("\"email\":\"synthetic@apture.cc\"", "\"email\":\"mail@inktrace.ink\"")
            fixture.cardItemsOverride = fixture.card().replace("\"status\":\"lost\"", "\"status\":\"issued\"") + "," +
                fixture.card().replace(fixture.cardID, fixture.oldID).replace("\"serial\":\"123456\"", "\"serial\":\"765432\"")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = reader(fixture, scope)
            reader.connect("https://issuer.invalid", "synthetic-token"); ready(reader)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            var target by mutableStateOf<EnrollmentTarget?>(null)
            try {
                activity.get().setContent {
                    CardKeyTheme {
                        Column(Modifier.requiredSize(375.dp, 812.dp).verticalScroll(rememberScrollState())) {
                            EnrollmentIssuerPanel(reader, target, true) { target = it }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("enrollment-issuer-credential").assertDoesNotExist()
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
                compose.onNodeWithText(activity.get().getString(R.string.enrollment_caId)).performScrollTo().performTextInput("synthetic-ca")
                compose.onNodeWithTag("enrollment-person").performScrollTo().performClick()
                compose.onNodeWithText("Synthetic Person · EMP-001").assertIsEnabled().performClick()
                ready(reader); compose.waitForIdle()
                compose.onNodeWithTag("enrollment-person-card").performScrollTo().performClick()
                val issued = reader.state.value.cards.single { it.status == "issued" }
                val lost = reader.state.value.cards.single { it.status == "lost" }
                compose.onNodeWithText(lost.serial).assertDoesNotExist()
                compose.onNodeWithText(issued.serial).performClick()
                compose.runOnIdle {
                    assertEquals(EnrollmentTarget("https://issuer.invalid", fixture.personID, fixture.cardID, "synthetic-ca",
                        EnrollmentIdentity("EMP-001", "Synthetic Person", "synthetic@apture.cc", "mail@inktrace.ink")), target)
                    assertNotNull(reader.clientForEnrollment())
                }
                fixture.personName = "Changed server name"
                assertEquals("Synthetic Person", target!!.identity.displayName)
                assertTrue(fixture.mutations.isEmpty())
            } finally { activity.pause().stop().destroy(); reader.close(); scope.cancel() }
        }
    }

    @Test fun inactivePersonCannotBeChosenOrProduceTargetThroughSharedReaderSelection() = runBlocking<Unit> {
        IssuerFixture().use { fixture ->
            fixture.cardItemsOverride = fixture.card().replace("\"status\":\"lost\"", "\"status\":\"issued\"")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = reader(fixture, scope)
            reader.connect("https://issuer.invalid", "synthetic-token"); ready(reader)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val initial = EnrollmentTarget("https://issuer.invalid", fixture.personID, fixture.cardID, "synthetic-ca",
                EnrollmentIdentity("EMP-001", "Synthetic Person", "synthetic@apture.cc", "synthetic@apture.cc"))
            var target: EnrollmentTarget? = null
            try {
                activity.get().setContent {
                    CardKeyTheme {
                        Column(Modifier.requiredSize(375.dp, 812.dp).verticalScroll(rememberScrollState())) {
                            EnrollmentIssuerPanel(reader, initial, true) { target = it }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("enrollment-person").performScrollTo().performClick()
                compose.onNodeWithText("Synthetic Person · EMP-001").assertIsNotEnabled()
                compose.onNodeWithTag("enrollment-issuer").performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
                reader.selectPerson(reader.state.value.people.single()); ready(reader); compose.waitForIdle()
                compose.onNodeWithTag("enrollment-person-card").performScrollTo().assertIsNotEnabled()
                assertNull(target)
                assertTrue(fixture.mutations.isEmpty())
            } finally { activity.pause().stop().destroy(); reader.close(); scope.cancel() }
        }
    }
}
