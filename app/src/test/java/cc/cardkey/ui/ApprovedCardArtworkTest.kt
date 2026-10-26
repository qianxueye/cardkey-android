package cc.cardkey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import cc.cardkey.R
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ApprovedCardArtworkTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lightHeroPreservesApprovedGeometryAndIndependentPaint() = render("approved-hero-light", 0xFF2358C4.toInt())

    @Test
    @Config(qualifiers = "w400dp-h800dp-night-mdpi")
    fun darkHeroPreservesApprovedGeometryAndIndependentPaint() = render("approved-hero-dark", 0xFF8EAFFF.toInt())

    @Test fun allApprovedVectorsRenderAtTheirNative24DpSize() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val icons = listOf(R.drawable.ic_approved_card, R.drawable.ic_approved_reader,
            R.drawable.ic_approved_reader_contactless, R.drawable.ic_approved_passkey,
            R.drawable.ic_approved_certificate, R.drawable.ic_approved_read,
            R.drawable.ic_approved_settings, R.drawable.ic_approved_fido_auth)
        var origin = Offset.Zero
        host.get().setContent {
            Row(Modifier.background(Color.White).onGloballyPositioned { origin = it.positionInWindow() }) {
                icons.forEach { Icon(painterResource(it), null, Modifier.size(24.dp), tint = Color.Black) }
            }
        }
        try {
            compose.waitForIdle()
            val view = host.get().window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
            icons.forEachIndexed { index, _ ->
                val pixels = (0 until 24).sumOf { x -> (0 until 24).count { y ->
                    bitmap.getPixel(origin.x.roundToInt() + index * 24 + x, origin.y.roundToInt() + y) != android.graphics.Color.WHITE
                } }
                assertTrue("Vector $index must render its visible approved geometry", pixels > 50)
            }
            File("build/ui-previews").mkdirs()
            File("build/ui-previews/approved-icons.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { host.pause().stop().destroy() }
    }

    private fun render(name: String, signal: Int) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var origin = Offset.Zero
        host.get().setContent {
            CardKeyTheme {
                Box(Modifier.background(Color.White)) {
                    ApprovedCardHero(Modifier.size(160.dp, 100.dp).onGloballyPositioned { origin = it.positionInWindow() })
                }
            }
        }
        try {
            compose.waitForIdle()
            val view = host.get().window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
            fun pixel(x: Int, y: Int) = bitmap.getPixel(origin.x.roundToInt() + x, origin.y.roundToInt() + y)
            assertEquals("Full-opacity outline", signal, pixel(2, 50))
            assertEquals("Approved gold chip", 0xFFB28C39.toInt(), pixel(30, 40))
            assertEquals("Chip division", 0xFF725B2B.toInt(), pixel(38, 40))
            val fill = pixel(100, 50)
            for (shift in listOf(16, 8, 0)) {
                val expected = (255 * 0.9 + ((signal shr shift) and 255) * 0.1).roundToInt()
                assertEquals("10% signal body channel", expected.toDouble(), ((fill shr shift) and 255).toDouble(), 1.0)
            }
            File("build/ui-previews").mkdirs()
            File("build/ui-previews/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { host.pause().stop().destroy() }
    }
}
