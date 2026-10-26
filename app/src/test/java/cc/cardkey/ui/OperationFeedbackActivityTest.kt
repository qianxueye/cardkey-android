package cc.cardkey.ui

import android.os.Looper
import android.nfc.NfcAdapter
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import cc.cardkey.MainActivity
import cc.cardkey.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.shadows.ShadowNfcAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OperationFeedbackActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before @After fun clearCachedApplicationFactory() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
    }

    private fun settle(model: ManagerViewModel, result: OperationResult<ManagerReport>) {
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        state.value = model.state.value.copy(busy = true, operation = ManagerOperation.PIV_INFO, result = null)
        ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java).apply {
            isAccessible = true
        }.invoke(model, result)
    }

    @Test fun visibleActivityReceivesCompletionWithoutReplayingOnRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            controller.windowFocusChanged(true)
            compose.waitForIdle()
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ManagerViewModel::class.java]
            val view = activity.window.decorView
            assertTrue(view.isShown && view.hasWindowFocus())
            compose.runOnIdle { settle(model, OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE)) }
            compose.waitForIdle()
            assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(view).lastHapticFeedbackPerformed())
            controller.recreate().windowFocusChanged(true)
            compose.waitForIdle()
            assertSame(model, ViewModelProvider(controller.get())[ManagerViewModel::class.java])
            assertEquals(-1, shadowOf(controller.get().window.decorView).lastHapticFeedbackPerformed())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unfocusedAndPausedResultsDoNotBuzzOrReplayOnResume() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            controller.windowFocusChanged(false)
            compose.waitForIdle()
            val model = ViewModelProvider(controller.get())[ManagerViewModel::class.java]
            val view = controller.get().window.decorView
            compose.runOnIdle { settle(model, OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE)) }
            compose.waitForIdle()
            assertEquals(-1, shadowOf(view).lastHapticFeedbackPerformed())
            controller.pause()
            settle(model, OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN))
            shadowOf(Looper.getMainLooper()).idle()
            controller.resume().windowFocusChanged(true)
            compose.waitForIdle()
            assertEquals(-1, shadowOf(view).lastHapticFeedbackPerformed())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun confirmedOperationFailureUsesTheFocusedDialogBeforeItCloses() {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            controller.windowFocusChanged(true)
            val activity = controller.get()
            shadowOf(NfcAdapter.getDefaultAdapter(activity)).setEnabled(false)
            compose.waitForIdle()
            compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
            compose.onNode((hasContentDescription("设置") or (hasText("设置") and
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))) and hasClickAction()).performClick()
            compose.onNodeWithText("高级 FIDO 设置与诊断").performScrollTo().performClick()
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("U2F · 注册").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("manager-run").performClick()
            compose.onNodeWithText("确认").assertIsDisplayed()
            val dialogView = ShadowDialog.getLatestDialog().window!!.decorView
            compose.runOnIdle {
                controller.windowFocusChanged(false)
                val root = ReflectionHelpers.callInstanceMethod<Any>(dialogView, "getViewRootImpl")
                Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true)
            }
            shadowOf(Looper.getMainLooper()).idle()
            compose.runOnIdle {
                assertFalse(activity.window.decorView.hasWindowFocus())
                assertTrue(dialogView.isShown && dialogView.hasWindowFocus())
                val ownerToken = (activity.window.decorView.layoutParams as WindowManager.LayoutParams).token
                assertNotNull(ownerToken)
                assertEquals(ownerToken, (dialogView.layoutParams as WindowManager.LayoutParams).token)
            }
            compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            val model = ViewModelProvider(activity)[ManagerViewModel::class.java]
            assertEquals(FailureCode.NFC_DISABLED, (model.state.value.result as OperationResult.Failed).code)
            assertEquals(HapticFeedbackConstants.REJECT, shadowOf(dialogView).lastHapticFeedbackPerformed())
            assertEquals(-1, shadowOf(activity.window.decorView).lastHapticFeedbackPerformed())
            controller.windowFocusChanged(true)
            compose.waitForIdle()
            assertEquals(-1, shadowOf(activity.window.decorView).lastHapticFeedbackPerformed())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun focusedWindowFromAnotherActivityCannotReceiveManagerFeedback() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val other = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.windowFocusChanged(false)
            other.windowFocusChanged(true)
            compose.waitForIdle()
            val model = ViewModelProvider(controller.get())[ManagerViewModel::class.java]
            assertTrue(other.get().window.decorView.hasWindowFocus())
            compose.runOnIdle { settle(model, OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE)) }
            compose.waitForIdle()
            assertEquals(-1, shadowOf(other.get().window.decorView).lastHapticFeedbackPerformed())
            controller.windowFocusChanged(true)
            compose.waitForIdle()
            assertEquals(-1, shadowOf(controller.get().window.decorView).lastHapticFeedbackPerformed())
        } finally {
            other.pause().stop().destroy()
            controller.pause().stop().destroy()
        }
    }
}
