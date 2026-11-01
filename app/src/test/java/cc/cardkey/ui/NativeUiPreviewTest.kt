package cc.cardkey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import cc.cardkey.R
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protocol.Effect
import cc.cardkey.protocol.OperationResult
import cc.cardkey.protocol.fido.Cbor
import cc.cardkey.protocol.fido.Credential
import cc.cardkey.protocol.fido.FidoUser
import cc.cardkey.protocol.fido.RelyingParty
import cc.cardkey.signing.SigningFileState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeUiPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lightScreens() = renderScreens("light")

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h915dp-night-xhdpi")
    fun darkScreens() = renderScreens("dark")

    private fun credential(name: String, site: String, suffix: Int) = InventoryCredential(
        InventoryRp(RelyingParty(site, site, ByteArray(32))),
        Credential(ByteArray(32) { 0x42 }.also { it[31] = suffix.toByte() },
            FidoUser(byteArrayOf(suffix.toByte()), name, name), Cbor.Map(emptyMap()), null))

    private fun renderScreens(theme: String) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val inventory = InventoryState(rps = emptyList(), load = InventoryLoad.LOADED, credentials = listOf(
            credential("account-a", "github.com", 1), credential("account-b", "github.com", 2),
            credential("user-b@example.com", "google.com", 3), credential("account-a", "apple.com", 4)))
        val state = mutableStateOf(ManagerState(nfc = true, recognition = CardRecognition("nfc", 1, CardFamily.FIDO2)))
        host.get().setContent {
            CardKeyTheme {
                Box(Modifier.fillMaxSize()) {
                    ManagerScreen(state.value, 0, {}, {}, {}, {}, {}, null, {}, {}, {}, {}, EnrollmentState(), { _, _ -> }, {},
                        { it.close() }, { token, _ -> token.fill('\u0000') }, {}, TlsQualificationState(), {}, {}, { it.close() },
                        SigningFileState(), { _, _ -> }, {}, inventory, { _, _ -> }, { _, _ -> }, {}, {},
                        protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(),
                        onProtectedPivDocument = {}, onProtectedPivClear = {},
                        onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
                }
            }
        }
        try {
            compose.waitForIdle()
            capture(host.get(), "home-$theme")
            compose.onNode(hasText(host.get().getString(R.string.native_prepare)) and hasClickAction()).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).performTextInput("1234")
            capture(host.get(), "pin-$theme")
            compose.onNodeWithTag("native-home-back").performClick()
            compose.waitForIdle()
            compose.onNode(hasText(host.get().getString(R.string.navigation_passkeys)) and hasClickAction()).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("workspace-object-back").performScrollTo().performClick()
            compose.waitForIdle()
            capture(host.get(), "passkeys-$theme")
            compose.onNodeWithTag("workspace-navigation").assertExists()
            compose.onNode(hasText(host.get().getString(R.string.navigation_certificates)) and hasClickAction()).performClick()
            compose.waitForIdle()
            capture(host.get(), "certificates-$theme")
            compose.onNodeWithTag("object-slot-9D").performScrollTo().performClick()
            compose.runOnIdle {
                state.value = state.value.copy(operation = ManagerOperation.PIV_SLOT, result = OperationResult.Completed(ManagerReport(listOf(
                    ResultRow("piv_slot", "9D"), ResultRow("piv_not_before", "2026-01-12T00:00:00Z"),
                    ResultRow("piv_not_after", "2027-01-12T00:00:00Z"), ResultRow("piv_assessed_at", "2026-11-01T00:00:00Z"))), Effect.APPLIED))
            }
            compose.waitForIdle()
            capture(host.get(), "certificate-slot-$theme")
        } finally { host.pause().stop().destroy() }
    }

    private fun capture(activity: ComponentActivity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        File("build/ui-previews").mkdirs()
        val file = File("build/ui-previews/native-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }
}
