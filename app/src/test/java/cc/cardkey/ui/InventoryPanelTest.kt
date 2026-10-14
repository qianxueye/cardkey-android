package cc.cardkey.ui

import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protocol.*
import cc.cardkey.signing.SigningFileState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InventoryPanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inventory = FidoInventory().apply {
        seedInventory(this, InventoryCard()); action(ManagerOperation.FIDO_VERIFY_CREDENTIAL)
    }
    private val manager = mutableStateOf(ManagerState())
    private var submitted: OperationInput? = null
    private var cancelled = 0
    private fun render(width: Int, height: Int, test: (ComponentActivity, () -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().enableEdgeToEdge()
        controller.get().setContent {
            val state by inventory.state.collectAsState()
            MaterialTheme { Box(Modifier.requiredSize(width.dp, height.dp)) {
                ManagerScreen(manager.value, 0, {}, {}, { submitted = it }, { cancelled++; inventory.retire() }, {}, null, {}, {}, {}, {},
                    EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                    TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(), { _, _ -> }, {},
                    state, { _, rp -> inventory.selectRp(rp) }, { _, credential -> inventory.selectCredential(credential) }, {}, inventory::action)
            } }
        }
        try { compose.waitForIdle(); test(controller.get()) { controller.pause().resume(); compose.waitForIdle() } }
        finally { submitted?.close(); controller.pause().stop().destroy() }
    }
    private fun pin() = compose.onNode(hasText("PIN") and hasSetTextAction())
    private fun composeRoot(view: View): ViewRootForTest? = if (view is ViewRootForTest) view else (view as? ViewGroup)?.let {
        (0 until it.childCount).firstNotNullOfOrNull { index -> composeRoot(it.getChildAt(index)) }
    }
    @Test fun compactSelectedProofRequiresPinAndConfirmationShowsExactTuple() {
        render(360, 480) { _, _ ->
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            compose.onNodeWithText("RP SHA-256 · HEX").assertDoesNotExist()
            pin().performScrollTo().performTextInput("1234")
            compose.onNodeWithTag("manager-run").assertIsEnabled().performClick()
            compose.onNodeWithText("RP · login.example.test").assertIsDisplayed()
            compose.onNodeWithText("凭据 ID · 15").assertIsDisplayed()
            compose.onNodeWithText("确认").performClick()
            assertEquals(ManagerOperation.FIDO_VERIFY_CREDENTIAL, submitted!!.operation)
            assertEquals(inventory.state.value.generation, submitted!!.inventoryGeneration)
            assertEquals("login.example.test", submitted!!.inventorySelection!!.rp.id)
            assertArrayEquals("1234".toCharArray(), submitted!!.pin)
        }
    }
    @Test fun landscapeImeKeepsProofControlReachableAndPauseRetiresConfirmationPin() {
        render(800, 480) { activity, pause ->
            compose.runOnIdle { requireNotNull(composeRoot(activity.window.decorView)).view.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 240)).setVisible(WindowInsets.Type.ime(), true).build()) }
            pin().performScrollTo().performTextInput("1234")
            compose.onNodeWithTag("manager-run").performClick()
            compose.onNodeWithText("确认").assertIsDisplayed()
            pause()
            compose.onNodeWithText("确认").assertDoesNotExist(); assertNull(submitted)
            pin().performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
    }
    @Test fun cleanupAndUnknownWriteBlockSelectionAndProofWithoutSecretViewer() {
        render(360, 480) { _, _ ->
            pin().performScrollTo().performTextInput("1234")
            compose.runOnIdle { manager.value = manager.value.copy(busy = true) }
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            compose.onNode(hasText("RP") and hasClickAction()).performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { manager.value = manager.value.copy(busy = false, requiresInspection = true,
                result = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE)) }
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            compose.onNodeWithText("查看秘密输出").assertDoesNotExist()
        }
    }
}
