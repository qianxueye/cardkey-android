package cc.cardkey.ui

import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.cardkey.R
import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagerScreenLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun composeRoot(view: View): ViewRootForTest? =
        if (view is ViewRootForTest) view else (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { composeRoot(group.getChildAt(it)) }
        }

    private fun render(width: Int, height: Int, check: (ComponentActivity, (ManagerState) -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val state = mutableStateOf(ManagerState())
        activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp)) {
                        ManagerScreen(state.value, 0, {}, {}, {}, {}, {}, null, {}, {}, {}, {})
                    }
                }
            }
        }
        try {
            compose.waitForIdle()
            check(activity) { next -> compose.runOnIdle { state.value = next }; compose.waitForIdle() }
        } finally { controller.pause().stop().destroy() }
    }

    private fun assertInputSpace() {
        val bounds = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Scrollable input collapsed: $bounds", bounds.height >= 80f)
    }

    private fun assertFeedbackVisible(message: String) {
        val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithText(message).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Feedback outside current operation viewport: $hint in $viewport",
            hint.height > 0f && hint.top >= viewport.top && hint.bottom <= viewport.bottom)
    }

    private fun selectLongRegistrationForm() {
        compose.onNode(hasText("操作") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(ManagerOperation.FIDO_REGISTER.title).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("扩展").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    @Test fun portraitHasScrollableInputSpace() {
        render(360, 800) { _, _ -> assertInputSpace() }
    }

    @Test fun shortLandscapeStillHasScrollableInputSpace() {
        render(800, 240) { _, _ -> assertInputSpace() }
    }

    @Test fun imeInsetsDoNotConsumeLandscapeInputSpace() {
        render(800, 480) { activity, _ ->
            compose.runOnIdle {
                requireNotNull(composeRoot(activity.window.decorView)).view.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 240))
                    .setVisible(WindowInsets.Type.ime(), true).build())
            }
            compose.waitForIdle()
            assertInputSpace()
        }
    }

    @Test fun hidPresenceIsVisibleAfterSubmittingFromLongForm() {
        render(360, 640) { activity, update ->
            selectLongRegistrationForm()
            update(ManagerState(nfc = false, busy = true, operation = ManagerOperation.FIDO_REGISTER,
                userPresenceRequired = true, resultWire = CardWire.CTAP))
            assertFeedbackVisible(activity.getString(R.string.status_hidUserPresenceRequired))
            compose.onNodeWithText("取消").assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test fun pendingHidFeedbackSurvivesImeOpening() {
        render(800, 480) { activity, update ->
            selectLongRegistrationForm()
            update(ManagerState(nfc = false, busy = true, operation = ManagerOperation.FIDO_REGISTER,
                userPresenceRequired = true, resultWire = CardWire.CTAP))
            compose.runOnIdle {
                requireNotNull(composeRoot(activity.window.decorView)).view.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 240))
                    .setVisible(WindowInsets.Type.ime(), true).build())
            }
            compose.waitForIdle()
            assertInputSpace()
            assertFeedbackVisible(activity.getString(R.string.status_hidUserPresenceRequired))
            compose.onNodeWithText("取消").assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test fun nfcPendingInstructionIsVisibleWithOriginalOperation() {
        render(360, 400) { activity, update ->
            update(ManagerState(busy = true, awaitingNfc = true, operation = ManagerOperation.FIDO_REGISTER))
            val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
            val operation = compose.onNodeWithText(ManagerOperation.FIDO_REGISTER.title)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val messages = compose.onAllNodesWithText(activity.getString(R.string.reader_nfc)).fetchSemanticsNodes()
            assertTrue("NFC instruction must follow the original operation in visible feedback", messages.any {
                val bounds = it.boundsInRoot
                bounds.height > 0f && bounds.top >= operation.bottom && bounds.bottom <= viewport.bottom
            })
            compose.onNodeWithText("取消").assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test fun unknownOutcomeRecoveryRemainsVisibleInShortWindow() {
        render(800, 240) { activity, update ->
            update(ManagerState(operation = ManagerOperation.PIV_GENERATE, requiresInspection = true,
                result = OperationResult.Failed(FailureCode.RECOVERY_REQUIRED, Effect.UNKNOWN,
                    dispatch = Dispatch.RESPONSE_UNAVAILABLE)))
            assertFeedbackVisible(activity.getString(R.string.error_recoveryRequired))
            assertFeedbackVisible(activity.getString(R.string.operation_unknown))
            compose.onNodeWithText(ManagerOperation.PIV_GENERATE.title).assertIsDisplayed()
        }
    }
}
