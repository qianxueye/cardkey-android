package cc.cardkey.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.cardkey.R

@Composable
internal fun CardArtworkHero(state: CardArtworkState, onRetry: () -> Unit, nfc: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state is CardArtworkState.Loaded) {
            val ratio = state.bitmap.width.toFloat() / state.bitmap.height
            val width = minOf(333.dp, LocalConfiguration.current.screenHeightDp.dp * 0.65f * ratio)
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = width).fillMaxWidth().aspectRatio(ratio)) {
                Image(state.bitmap.asImageBitmap(), state.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        } else {
            SmartCardGlyph(Modifier.size(if (nfc) 112.dp else 88.dp, 56.dp), nfc = nfc,
                waveColor = MaterialTheme.colorScheme.primary, label = stringResource(R.string.artwork_generic))
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
internal fun SmartCardGlyph(modifier: Modifier = Modifier, nfc: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface, chip: Color = Color(0xFFBEA25B),
    waveColor: Color = color, label: String? = null) {
    androidx.compose.foundation.Canvas(if (label != null) modifier.semantics { contentDescription = label } else modifier) {
        val scale = size.width / (if (nfc) 112f else 88f)
        val cardWidth = 82f * scale
        val cardHeight = 50f * scale
        val top = (size.height - cardHeight) / 2f
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(2f * scale, top),
            androidx.compose.ui.geometry.Size(cardWidth, cardHeight),
            androidx.compose.ui.geometry.CornerRadius(5f * scale), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * scale))
        drawRoundRect(chip, androidx.compose.ui.geometry.Offset(12f * scale, top + 17f * scale),
            androidx.compose.ui.geometry.Size(14f * scale, 17f * scale), androidx.compose.ui.geometry.CornerRadius(2f * scale))
        drawLine(color, androidx.compose.ui.geometry.Offset(19f * scale, top + 17f * scale),
            androidx.compose.ui.geometry.Offset(19f * scale, top + 34f * scale), scale)
        drawLine(color, androidx.compose.ui.geometry.Offset(12f * scale, top + 25.5f * scale),
            androidx.compose.ui.geometry.Offset(26f * scale, top + 25.5f * scale), scale)
        if (nfc) for (radius in listOf(12f, 22f)) {
            drawArc(waveColor, -45f, 90f, false,
                androidx.compose.ui.geometry.Offset((78f - radius) * scale, size.height / 2f - radius * scale),
                androidx.compose.ui.geometry.Size(radius * 2f * scale, radius * 2f * scale),
                style = androidx.compose.ui.graphics.drawscope.Stroke(3f * scale, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
