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
    val signal = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF8EAFFF) else Color(0xFF2358C4)
    androidx.compose.foundation.Canvas(if (label != null) modifier.semantics { contentDescription = label } else modifier) {
        val scale = minOf(size.width / 160f, size.height / 100f)
        val left = (size.width - 160f * scale) / 2f
        val top = (size.height - 100f * scale) / 2f
        with(androidx.compose.ui.graphics.drawscope.Stroke(2.5f * scale)) {
            val origin = androidx.compose.ui.geometry.Offset(left + 2f * scale, top + 2f * scale)
            val body = androidx.compose.ui.geometry.Size(156f * scale, 96f * scale)
            val radius = androidx.compose.ui.geometry.CornerRadius(11f * scale)
            drawRoundRect(signal.copy(alpha = 0.10f), origin, body, radius)
            drawRoundRect(signal, origin, body, radius, style = this)
        }
        drawRoundRect(Color(0xFFB28C39), androidx.compose.ui.geometry.Offset(left + 25f * scale, top + 33f * scale),
            androidx.compose.ui.geometry.Size(27f * scale, 30f * scale), androidx.compose.ui.geometry.CornerRadius(4f * scale))
        val division = Color(0xFF725B2B)
        drawLine(division, androidx.compose.ui.geometry.Offset(left + 38.5f * scale, top + 33f * scale),
            androidx.compose.ui.geometry.Offset(left + 38.5f * scale, top + 63f * scale), 1.6f * scale)
        drawLine(division, androidx.compose.ui.geometry.Offset(left + 25f * scale, top + 48f * scale),
            androidx.compose.ui.geometry.Offset(left + 52f * scale, top + 48f * scale), 1.6f * scale)
    }
}
