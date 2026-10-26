package cc.cardkey.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FidoModelRowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pendingLookupKeepsRawGuidAndCardChangeCannotShowPreviousModel() {
        val known = "eabb46cc-e241-80bf-ae9e-96fa6d2975cf"
        val zero = "00000000-0000-0000-0000-000000000000"
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val catalog = FidoModelCatalog({
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            Response.Builder().request(Request.Builder().url("https://fixture.invalid/").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .header("Cache-Control", "max-age=300")
                .body("""{"no":1,"$known":{"friendlyNames":{"en-US":"TOKEN2 PIN Plus Security Key Series "}}}""".toResponseBody("application/json".toMediaType())).build()
        })
        val guid = mutableStateOf(known)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {
                CardKeyTheme {
                    CompositionLocalProvider(LocalFidoModelCatalog provides catalog) {
                    Column {
                        ResultFactRow(ResultRow("AAGUID", guid.value))
                        ResultFactRow(ResultRow("关联型号", guid.value, fidoModel = true))
                    }
                    }
                }
            }
            compose.onNodeWithText(known).assertIsDisplayed()
            compose.onNodeWithText("正在查询…").assertIsDisplayed()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { guid.value = zero }
            compose.onNodeWithText(zero).assertIsDisplayed()
            release.countDown()
            compose.waitUntil(5000) { compose.onAllNodesWithText("未收录").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("TOKEN2 PIN Plus Security Key Series").assertDoesNotExist()
            compose.runOnIdle { guid.value = known }
            compose.waitUntil(5000) { compose.onAllNodesWithText("TOKEN2 PIN Plus Security Key Series").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("TOKEN2 PIN Plus Security Key Series").assertIsDisplayed()
        } finally {
            release.countDown()
            activity.pause().stop().destroy()
        }
    }
}
