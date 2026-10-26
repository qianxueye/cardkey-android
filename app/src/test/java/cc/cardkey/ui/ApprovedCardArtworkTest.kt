package cc.cardkey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

    @Test fun lightHeroPreservesNeutralCardAndPhysicalModuleProportions() = render("approved-hero-light")

    @Test
    @Config(qualifiers = "w400dp-h800dp-night-mdpi")
    fun darkHeroPreservesNeutralCardAndPhysicalModuleProportions() = render("approved-hero-dark")

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

    private fun render(name: String) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var origin = Offset.Zero
        host.get().setContent {
            CardKeyTheme {
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    ApprovedCardHero(Modifier.size(112.dp, 70.dp).onGloballyPositioned { origin = it.positionInWindow() })
                }
            }
        }
        try {
            compose.waitForIdle()
            val view = host.get().window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
            fun pixel(x: Int, y: Int) = bitmap.getPixel(origin.x.roundToInt() + x, origin.y.roundToInt() + y)
            val body = pixel(70, 35)
            val channels = listOf(16, 8, 0).map { (body shr it) and 255 }
            assertTrue("Generic body stays neutral", channels.max() - channels.min() <= 5)
            val metal = (0 until 112).flatMap { x -> (0 until 70).mapNotNull { y ->
                val color = pixel(x, y)
                val red = (color shr 16) and 255
                val green = (color shr 8) and 255
                val blue = color and 255
                if (red - green > 10 && green - blue > 20) x to y else null
            } }
            assertTrue("IC module remains visible at the actual 112dp composition", metal.isNotEmpty())
            val width = metal.maxOf { it.first } - metal.minOf { it.first } + 1
            val height = metal.maxOf { it.second } - metal.minOf { it.second } + 1
            assertTrue("Module width follows the 13mm physical footprint: $width", width in 14..17)
            assertTrue("Module height follows the 11.8mm physical footprint: $height", height in 13..15)
            assertTrue("Module is wider than tall", width > height)
            File("build/ui-previews").mkdirs()
            val artwork = Bitmap.createBitmap(bitmap, origin.x.roundToInt(), origin.y.roundToInt(), 112, 70)
            File("build/ui-previews/$name.png").outputStream().use { artwork.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { host.pause().stop().destroy() }
    }
}
