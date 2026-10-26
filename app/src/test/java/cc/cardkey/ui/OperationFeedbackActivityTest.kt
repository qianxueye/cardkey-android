package cc.cardkey.ui

import android.os.Looper
import android.view.HapticFeedbackConstants
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
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
}
