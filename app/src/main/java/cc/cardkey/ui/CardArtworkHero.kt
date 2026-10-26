package cc.cardkey.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = width).fillMaxWidth().aspectRatio(ratio)) {
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
            withTransform({ translate(8.56f, 17.99f) }) {
                drawRoundRect(Color(0xFF76603E), size = Size(13f, 11.8f), cornerRadius = CornerRadius(1.25f))
                drawRoundRect(Brush.linearGradient(
                    0f to Color(0xFFB28D4E), .18f to Color(0xFFE8CF91), .43f to Color(0xFFF4DFAA),
                    .68f to Color(0xFFD1AE69), .86f to Color(0xFFE7CA8A), 1f to Color(0xFFAD8345),
                    start = Offset.Zero, end = Offset(13f, 11.8f)),
                    Offset(.2f, .2f), Size(12.6f, 11.4f), CornerRadius(1.05f))
                val metal = Path().apply { addRoundRect(RoundRect(.2f, .2f, 12.8f, 11.6f, CornerRadius(1.05f))) }
                clipPath(metal) {
                    val segments = Path().apply {
                        moveTo(4.85f, .2f); cubicTo(4.85f, .8f, 4.35f, .75f, 4.35f, 1.65f)
                        lineTo(4.35f, 9.95f); cubicTo(4.35f, 11f, 4.85f, 11f, 4.85f, 11.6f)
                        moveTo(8.15f, .2f); cubicTo(8.15f, .8f, 8.65f, .75f, 8.65f, 1.65f)
                        lineTo(8.65f, 9.95f); cubicTo(8.65f, 11f, 8.15f, 11f, 8.15f, 11.6f)
                        for (y in listOf(3.24f, 5.78f, 8.32f)) {
                            moveTo(.2f, y); lineTo(4.35f, y)
                            moveTo(8.65f, y); lineTo(12.8f, y)
                        }
                    }
                    drawPath(segments, Color(0xFF654B28), style = Stroke(.16f))
                    for (y in listOf(.55f, 11.2f)) drawLine(Color(0xFFFFF0C2).copy(alpha = .45f),
                        Offset(.5f, y), Offset(12.5f, y), .1f)
                }
            }
        }
    }
}
