package cc.cardkey.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.cardkey.R

@Composable
internal fun CardArtworkHero(state: CardArtworkState, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state is CardArtworkState.Loaded) {
            val ratio = state.bitmap.width.toFloat() / state.bitmap.height
            val width = minOf(333.dp, LocalConfiguration.current.screenHeightDp.dp * 0.65f * ratio)
            Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = width).fillMaxWidth().aspectRatio(ratio)) {
                Image(state.bitmap.asImageBitmap(), state.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        } else {
            ApprovedCardHero(Modifier.size(112.dp, 70.dp), label = stringResource(R.string.artwork_generic))
        }
        when (state) {
            CardArtworkState.Default -> Unit
            CardArtworkState.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.artwork_loading), style = MaterialTheme.typography.bodySmall)
            }
            CardArtworkState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.artwork_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.artwork_retry)) }
            }
            is CardArtworkState.Loaded -> Unit
        }
    }
}

@Composable
internal fun ApprovedCardHero(modifier: Modifier = Modifier, label: String? = null) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.foundation.Canvas(if (label != null) modifier.semantics { contentDescription = label } else modifier) {
        val scale = minOf(size.width / 89.6f, size.height / 58.68f)
        withTransform({
            translate((size.width - 89.6f * scale) / 2f, (size.height - 58.68f * scale) / 2f)
            scale(scale, scale, pivot = Offset.Zero)
            translate(2f, 2f)
        }) {
            val edge = if (dark) Color(0xFF92989D) else Color(0xFFB9BFC3)
            drawRoundRect(edge, Offset(0f, .7f), Size(85.6f, 53.98f), CornerRadius(3.18f))
            // Preserve the SVG object-bounding-box gradient under the non-square face transform.
            val gradientX = .7f / 85.6f
            val gradientY = 1f / 53.98f
            val gradientLength = 1.49f / (gradientX * gradientX + gradientY * gradientY)
            drawRoundRect(Brush.linearGradient(
                0f to if (dark) Color(0xFFF0F0ED) else Color.White,
                .58f to if (dark) Color(0xFFD7D9DA) else Color(0xFFF4F4F1),
                1f to if (dark) Color(0xFFB5B9BD) else Color(0xFFE4E6E7),
                start = Offset.Zero, end = Offset(gradientX * gradientLength, gradientY * gradientLength)),
                size = Size(85.6f, 53.98f), cornerRadius = CornerRadius(3.18f))
            drawRoundRect(edge, Offset(.12f, .12f), Size(85.36f, 53.74f), CornerRadius(3.06f), style = Stroke(.24f))
            drawRoundRect(Brush.verticalGradient(
                0f to Color.White.copy(alpha = .85f), .6f to Color.White.copy(alpha = .08f),
                1f to Color.White.copy(alpha = .35f), startY = .4f, endY = 53.58f),
                Offset(.4f, .4f), Size(84.8f, 53.18f), CornerRadius(2.78f), style = Stroke(.2f))
            withTransform({ translate(8.56f, 17.99f); scale(.13f, .13f, pivot = Offset.Zero) }) {
                drawRoundRect(Color(0xFFF9C72D), size = Size(100f, 90.5f), cornerRadius = CornerRadius(11f))
                drawPath(ChipContacts, Color(0xFF34373B), style = Stroke(3f, join = StrokeJoin.Round))
            }
        }
    }
}

private val ChipContacts: Path = PathParser().parsePathString(
    "M49.5 0V26.5L36 31.8L30.7 45.2L35.7 58.3L49.1 63.6L62.2 58.3L67.5 45.2L62.2 31.8L69.3 23.3H100" +
        "M0 23.3H29.7L36 31.8M0 45.2H30.7M0 66.1H29.7L35.7 58.3M67.5 45.2H100M62.2 58.3L68.6 66.1H100M49.1 63.6V90.5" +
        "M32.5 71.8Q49.1 82.5 65.7 71.8").toPath()
