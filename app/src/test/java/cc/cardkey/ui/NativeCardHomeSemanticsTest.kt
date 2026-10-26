package cc.cardkey.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import android.hardware.usb.UsbDevice
import cc.cardkey.transport.UsbReaderDescriptor
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.junit.Assert.assertTrue
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import cc.cardkey.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeCardHomeSemanticsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun chineseTaskLabelsAndActionsRemainAccessibleWithoutDecorativeChevronText() = checkTaskSemantics()

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h800dp-mdpi")
    fun englishTaskLabelsAndActionsRemainAccessibleWithoutDecorativeChevronText() = checkTaskSemantics()

    @Test fun chineseNoReaderStateKeepsSelectionAvailableAndReadDisabled() = checkReaderEligibility("zh")

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h800dp-mdpi")
    fun englishNoReaderStateKeepsSelectionAvailableAndReadDisabled() = checkReaderEligibility("en")

    private fun checkReaderEligibility(locale: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val state = mutableStateOf(ManagerState(nfc = false))
        var reads = 0
        var selections = 0
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "synthetic-home-reader")
        val reader = UsbReaderDescriptor(device, 1)
        try {
            activity.setContent {
                CardKeyTheme {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        NativeCardHome(state.value, CardArtworkState.Default,
                            onRead = { reads++ }, onPrepare = {}, onFido = {}, onPiv = {}, onTechnical = {},
                            onReaders = { selections++ }, onRetry = {})
                    }
                }
            }
            compose.onNodeWithTag("native-read-card").assertIsDisplayed().assertIsNotEnabled()
            assertTrue(compose.onNodeWithTag("native-read-card").fetchSemanticsNode().boundsInRoot.height >= 48f)
            compose.onNodeWithText(activity.getString(R.string.native_reader_missing)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.native_choose_reader_hint)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.native_choose_reader)).assertIsEnabled().performClick()
            assertEquals(1, selections)
            assertEquals(0, reads)
            val view = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
            File("build/ui-previews").mkdirs()
            File("build/ui-previews/no-reader-$locale.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.runOnIdle { state.value = ManagerState(nfc = false, selectedReader = reader) }
            compose.onNodeWithTag("native-read-card").assertIsEnabled()
            compose.runOnIdle { state.value = ManagerState(nfc = false, readers = listOf(reader)) }
            compose.onNodeWithTag("native-read-card").assertIsNotEnabled()
            compose.onNodeWithText(activity.getString(R.string.native_choose_reader)).assertIsEnabled()
            compose.runOnIdle { state.value = ManagerState(nfc = true) }
            compose.onNodeWithTag("native-read-card").assertIsEnabled().performClick()
            assertEquals(1, reads)
        } finally { controller.pause().stop().destroy() }
    }

    private fun checkTaskSemantics() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val clicks = mutableListOf<String>()
        try {
            activity.setContent {
                CardKeyTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NativeCardHome(ManagerState(), CardArtworkState.Default,
                            onRead = {}, onPrepare = { clicks += "prepare" },
                            onFido = { clicks += "fido" }, onPiv = { clicks += "piv" },
                            onTechnical = { clicks += "technical" }, onReaders = {}, onRetry = {})
                    }
                }
            }
            listOf(R.string.navigation_passkeys, R.string.native_certificates, R.string.native_technical)
                .forEach { label ->
                    compose.onNode(hasText(activity.getString(label)) and hasClickAction())
                        .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                }
            compose.onNode(hasText(activity.getString(R.string.native_prepare)) and hasClickAction())
                .performScrollTo().assertIsDisplayed().performClick()
            assertEquals(listOf("fido", "piv", "technical", "prepare"), clicks)
            compose.onAllNodesWithText("›").assertCountEquals(0)
            compose.onAllNodesWithText("›", useUnmergedTree = true).assertCountEquals(0)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
