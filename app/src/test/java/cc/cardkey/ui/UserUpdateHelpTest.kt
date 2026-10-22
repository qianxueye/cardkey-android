package cc.cardkey.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protectedpiv.ProtectedPivState
import cc.cardkey.protocol.*
import cc.cardkey.signing.SigningFileState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserUpdateHelpTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun form(locale: Locale, help: String) {
        val inventory = FidoInventory().apply { seedInventory(this, InventoryCard()); action(ManagerOperation.FIDO_UPDATE) }
        val selected = requireNotNull(inventory.state.value.credential)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val configuration = Configuration(host.get().resources.configuration).apply { setLocale(locale) }
        val context = host.get().createConfigurationContext(configuration)
        var submitted: OperationInput? = null
        host.get().setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                val state by inventory.state.collectAsState()
                MaterialTheme { Box(Modifier.requiredSize(800.dp, 1000.dp)) {
                    ManagerScreen(ManagerState(recognition = CardRecognition("nfc", 1, CardFamily.FIDO2)), 0,
                        {}, {}, { submitted = it }, {}, {}, null, {}, {}, {}, {}, EnrollmentState(), { _, _ -> }, {},
                        { it.close() }, { token, _ -> token.fill('\u0000') }, {}, TlsQualificationState(), {}, {}, { it.close() },
                        SigningFileState(), { _, _ -> }, {}, state, { _, _ -> }, { _, _ -> }, {}, {},
                        protectedPiv = ProtectedPivState(), onProtectedPivDocument = {}, onProtectedPivClear = {},
                        onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
                } }
            }
        }
        try {
            compose.onNodeWithText(help).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("新用户名") and hasSetTextAction()).performScrollTo().performTextInput("alice.updated")
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).performScrollTo().performTextInput("1234")
            assertNull(submitted)
            compose.onNodeWithTag("manager-run").performClick()
            val input = requireNotNull(submitted)
            assertEquals(ManagerOperation.FIDO_UPDATE, input.operation)
            assertEquals("alice.updated", input.secondary)
            assertSame(selected, input.inventorySelection)
            assertEquals(selected.displayName, inventory.state.value.credential?.displayName)
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("task-FIDO_DELETE").performScrollTo().performClick()
            compose.onNodeWithText(help).assertDoesNotExist()
        } finally { submitted?.close(); host.pause().stop().destroy(); inventory.retire() }
    }

    @Test fun chineseUsernameFormExplainsThatDisplayNameCannotChange() = form(Locale.SIMPLIFIED_CHINESE,
        "此卡仅支持修改用户名，不支持修改显示名称。")

    @Test fun englishUsernameFormExplainsThatDisplayNameCannotChange() = form(Locale.US,
        "On this card, you can change the username but not the display name.")
}
