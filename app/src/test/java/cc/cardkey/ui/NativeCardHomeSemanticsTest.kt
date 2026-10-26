package cc.cardkey.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
