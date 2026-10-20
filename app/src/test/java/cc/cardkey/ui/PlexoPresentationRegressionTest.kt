package cc.cardkey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlexoPresentationRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun artworkFitsPhoneAndWideLandscapeWithoutOverlappingFollowingContent() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var width by mutableIntStateOf(400)
        var artwork by mutableStateOf<CardArtworkState>(CardArtworkState.Default)
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CardKeyTheme {
                    Column(Modifier.requiredSize(width.dp, 480.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        CardArtworkHero(artwork) {}
                        Text("Following content", Modifier.testTag("following-content"))
                    }
                }
            }
        }
        try {
            val loaded = CardArtworkState.Loaded(Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888), "Synthetic card image")
            for (candidateWidth in listOf(400, 1200)) {
                for (state in listOf(CardArtworkState.Default, CardArtworkState.Loading, CardArtworkState.Failed, loaded)) {
                    compose.runOnIdle { width = candidateWidth; artwork = state }
                    val label = if (state is CardArtworkState.Loaded) state.label else "通用卡片示意图"
                    val image = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
                    val next = compose.onNodeWithTag("following-content").fetchSemanticsNode().boundsInRoot
                    assertTrue("Artwork height exceeds its 210dp budget: $image", image.height <= 210.5f)
                    assertTrue("Artwork overlaps following content: $image / $next", image.bottom <= next.top)
                    assertEquals(1.586f, image.width / image.height, 0.02f)
                }
            }
        } finally { host.pause().stop().destroy() }
    }

    private val rp = InventoryRp(RelyingParty("example.test", "Synthetic site", ByteArray(32)))
    private fun credential(name: String?, displayName: String?, suffix: Int) = InventoryCredential(rp,
        Credential(ByteArray(32) { 0x42 }.also { it[31] = suffix.toByte() },
            FidoUser(byteArrayOf(suffix.toByte()), name, displayName), Cbor.Map(emptyMap()), null))

    private fun renderCredentials(credentials: List<InventoryCredential>, check: (() -> InventoryCredential?) -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var selected: InventoryCredential? = null
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CardKeyTheme {
                    Box(Modifier.requiredSize(400.dp, 800.dp)) {
                        PlexoObjects(0, 0x9a, InventoryState(rps = listOf(rp), rp = rp, credentials = credentials),
                            true, rememberScrollState(), {}, {}, {}, {}, { selected = it }, {}, {})
                    }
                }
            }
        }
        try { compose.waitForIdle(); check { selected } }
        finally { host.pause().stop().destroy() }
    }

    @Test fun sameDisplayNameStillShowsDifferentActualUsernamesAndRetainsSelection() {
        val credentials = listOf(credential("alice@example.test", "Personal", 1), credential("bob@example.test", "Personal", 2))
        renderCredentials(credentials) { selected ->
            credentials.forEach { credential ->
                compose.onNodeWithTag("object-credential-${credential.idHex}").performScrollTo()
                    .assertTextContains(requireNotNull(credential.name), substring = true).performClick()
                assertSame(credential, selected())
            }
        }
    }

    @Test fun identicalAccountTextIncludesCompleteCredentialIds() {
        val credentials = listOf(credential("account@example.test", "Personal", 1), credential("account@example.test", "Personal", 2))
        assertCompleteIdsAndSelection(credentials)
    }

    @Test fun missingAccountNamesIncludeCompleteCredentialIds() {
        val credentials = listOf(credential(null, "Personal", 3), credential("", "Personal", 4),
            credential(null, null, 5), credential(null, null, 6))
        assertCompleteIdsAndSelection(credentials)
    }

    private fun assertCompleteIdsAndSelection(credentials: List<InventoryCredential>) {
        renderCredentials(credentials) { selected ->
            credentials.forEach { credential ->
                val row = compose.onNodeWithTag("object-credential-${credential.idHex}")
                row.performScrollTo().assertTextContains(credential.idHex, substring = true)
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(credential.idHex, substring = true, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertFalse("Credential ID must remain fully laid out", results.single().hasVisualOverflow)
                row.performClick()
                assertSame(credential, selected())
            }
        }
    }
}
