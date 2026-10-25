package cc.cardkey.enrollment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import cc.cardkey.MainActivity
import cc.cardkey.ui.ManagerViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnrollmentRootPickerTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pendingRootSelectionSurvivesActivityRecreationAndDeliversToRetainedModel() {
        val fixture = EnrollmentFixtures()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val original = controller.get()
            val model = ViewModelProvider(original)[ManagerViewModel::class.java]
            model.enrollmentSettings("https://localhost", fixture.csr.cn)
            compose.waitForIdle()
            compose.onNode((hasContentDescription("设置") or (hasText("设置") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))) and hasClickAction()).performClick()
            compose.onNodeWithText("申请 PIV 证书").performScrollTo().performClick()
            compose.onNodeWithText("选择 CA 根证书").performScrollTo().performClick()
            val launch = shadowOf(original).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            controller.recreate()
            val recreated = controller.get()
            assertNotSame(original, recreated)
            assertSame(model, ViewModelProvider(recreated)[ManagerViewModel::class.java])
            val uri = Uri.parse("content://enrollment-fixture/root")
            shadowOf(recreated.contentResolver).registerInputStream(uri, ByteArrayInputStream(fixture.root))
            assertTrue(recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            compose.waitUntil(5000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.enrollment.state.value.hasRoot
            }
            assertArrayEquals(fixture.root, model.enrollment.ca().anchor)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recreatedRootCallbackStillRejectsRetiredSettingsGeneration() {
        val fixture = EnrollmentFixtures()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val original = controller.get()
            val model = ViewModelProvider(original)[ManagerViewModel::class.java]
            model.enrollmentSettings("https://localhost", fixture.csr.cn)
            compose.waitForIdle()
            compose.onNode((hasContentDescription("设置") or (hasText("设置") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))) and hasClickAction()).performClick()
            compose.onNodeWithText("申请 PIV 证书").performScrollTo().performClick()
            compose.onNodeWithText("选择 CA 根证书").performScrollTo().performClick()
            val launch = shadowOf(original).nextStartedActivityForResult
            controller.recreate()
            val recreated = controller.get()
            model.enrollmentSettings("https://different.invalid", "New identity")
            model.enrollmentSettings("https://localhost", fixture.csr.cn)
            val uri = Uri.parse("content://enrollment-fixture/stale-root")
            val read = java.util.concurrent.CountDownLatch(1)
            shadowOf(recreated.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(fixture.root) {
                override fun close() { super.close(); read.countDown() }
            })
            assertTrue(recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            compose.waitUntil(5000) {
                shadowOf(Looper.getMainLooper()).idle()
                read.count == 0L && model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive }
            }
            assertFalse(model.enrollment.state.value.hasRoot)
        } finally { controller.pause().stop().destroy() }
    }
}
