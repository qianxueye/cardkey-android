package cc.cardkey.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.cardkey.R

@Composable
internal fun CardArtworkHero(state: CardArtworkState, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 333.dp).fillMaxWidth().aspectRatio(1.586f)) {
            if (state is CardArtworkState.Loaded) {
                Image(state.bitmap.asImageBitmap(), state.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                val description = stringResource(R.string.artwork_generic)
                Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF33413D), Color(0xFF182622))))
                    .semantics { contentDescription = description }.padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("CardKey", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Surface(color = Color(0xFFD8B87A), shape = RoundedCornerShape(5.dp), modifier = Modifier.size(38.dp, 30.dp)) { }
                    Text("FIDO2 / PIV", color = Color(0xFFE3E9E4), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        when (state) {
            CardArtworkState.Default -> Text(stringResource(R.string.artwork_generic), style = MaterialTheme.typography.bodySmall)
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
