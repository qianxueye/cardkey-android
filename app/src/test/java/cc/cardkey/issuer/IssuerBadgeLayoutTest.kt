package cc.cardkey.issuer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.cardkey.ui.*
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protectedpiv.ProtectedPivState
import cc.cardkey.signing.SigningFileState
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w375dp-h812dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssuerBadgeLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun phone375RendersSelectedServerPortraitWithoutPhysicalOperation() = render(375, 812, 1f, "phone")
    @Test @Config(qualifiers = "zh-rCN-w375dp-h812dp-night-mdpi")
    fun darkPhoneKeepsRecordTextReadable() = render(375, 812, 1f, "dark-phone")
    @Test @Config(qualifiers = "zh-rCN-w812dp-h375dp-mdpi")
    fun landscapeLargeTextKeepsConnectionAndBothImagesReachable() = render(812, 375, 1.6f, "landscape")

    private fun render(width: Int, height: Int, fontScale: Float, name: String) = runBlocking<Unit> {
        IssuerFixture().use { fixture ->
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val secrets = IssuerSecrets(RuntimeEnvironment.getApplication().getSharedPreferences("layout-$name", Context.MODE_PRIVATE)) { key }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = IssuerReader(secrets, scope) { _, token -> IssuerClient(fixture.origin, token) }
            reader.connect("https://issuer.invalid", "synthetic-token")
            withTimeout(10000) { while (reader.state.value.busy) delay(10) }
            reader.selectPerson(reader.state.value.people.single())
            withTimeout(10000) { while (reader.state.value.busy) delay(10) }
            reader.selectCard(reader.state.value.cards.single())
            withTimeout(10000) { while (reader.state.value.busy || reader.front.state.value is CardArtworkState.Loading || reader.back.state.value is CardArtworkState.Loading) delay(10) }
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            try {
                activity.get().setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        CardKeyTheme { Box(Modifier.requiredSize(width.dp, height.dp)) {
                            ManagerScreen(ManagerState(), 0, {}, {}, { fail("physical operation dispatched") }, {}, {}, null, {}, {}, {}, {},
                                EnrollmentState(), { _, _ -> }, {}, { fail("enrollment dispatched") }, { _, _ -> fail("issuance dispatched") }, {},
                                TlsQualificationState(), {}, {}, {}, SigningFileState(), { _, _ -> }, {}, InventoryState(),
                                { _, _ -> }, { _, _ -> }, {}, {}, protectedPiv = ProtectedPivState(), onProtectedPivDocument = {},
                                onProtectedPivClear = {}, onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = {},
                                badgeContent = { IssuerBadgePanel(reader) })
                        } }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithText("工卡记录").performScrollTo().performClick()
                compose.onNodeWithTag("issuer-records").assertExists()
                compose.onNodeWithText("工卡服务地址（HTTPS）").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("连接并刷新").performScrollTo().assertIsDisplayed()
                save("$name-connection", activity.get())
                compose.onNodeWithText("卡面发布时的资料").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Historical Person · EMP-001").assertExists()
                compose.onNodeWithText("背面").performScrollTo().assertIsDisplayed()
                compose.onAllNodesWithContentDescription("Synthetic portrait card")[1].performScrollTo().assertIsDisplayed()
                save("$name-artwork", activity.get())
                assertEquals(2, compose.onAllNodesWithContentDescription("Synthetic portrait card").fetchSemanticsNodes().size)
                compose.runOnIdle { reader.editConnection() }
                compose.onNodeWithText("Historical Person · EMP-001").assertDoesNotExist()
                compose.onAllNodesWithContentDescription("Synthetic portrait card").assertCountEquals(0)
            } finally { activity.pause().stop().destroy(); reader.close(); scope.cancel() }
        }
    }
    private fun save(name: String, activity: ComponentActivity) {
        val view = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val directory = File("build/ui-previews/issuer").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
