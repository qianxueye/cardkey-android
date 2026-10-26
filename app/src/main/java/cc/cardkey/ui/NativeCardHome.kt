package cc.cardkey.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.cardkey.R

@Composable
internal fun NativeCardHome(
    state: ManagerState, artwork: CardArtworkState,
    onRead: () -> Unit, onPrepare: () -> Unit, onFido: () -> Unit,
    onPiv: () -> Unit, onTechnical: () -> Unit, onReaders: () -> Unit, onRetry: () -> Unit,
) {
    val enabled = !state.busy && !state.permissionPending
    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.testTag("native-card-home")) {
        Text(stringResource(R.string.native_card), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (artwork !is CardArtworkState.Loaded) Box(Modifier.width(112.dp)) {
                        CardArtworkHero(artwork, onRetry, nfc = state.nfc)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (state.canEnterFidoSecrets || state.canEnterPivSecrets)
                            R.string.native_observed else R.string.native_unread), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onReaders, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                            Text(if (state.nfc) "NFC" else state.selectedReader?.device?.productName ?: "USB")
                        }
                    }
                }
                if (artwork is CardArtworkState.Loaded) CardArtworkHero(artwork, onRetry, nfc = state.nfc)
                Text(stringResource(if (state.nfc) R.string.native_tap else R.string.native_usb),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onRead, enabled = enabled && (!state.requiresInspection || ConfirmationPolicy.canInspect(ManagerOperation.FIDO_INFO)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2358C4), contentColor = Color.White),
                    shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.CenterHorizontally)
                        .widthIn(min = 200.dp, max = 260.dp).heightIn(min = 48.dp).testTag("native-read-card")) {
                    SmartCardGlyph(Modifier.size(32.dp, 24.dp), nfc = state.nfc,
                        color = Color.White, chip = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.native_read))
                }
                Text(stringResource(R.string.native_read_scope), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HomeTask(stringResource(R.string.navigation_passkeys), "FIDO2", 0, enabled, onFido,
            status = stringResource(if (state.canEnterFidoSecrets) R.string.native_app_read else R.string.native_app_unread),
            prepare = onPrepare)
        HorizontalDivider()
        HomeTask(stringResource(R.string.native_certificates), "PIV", 1, enabled && state.supportsPiv, onPiv,
            status = stringResource(if (state.canEnterPivSecrets) R.string.native_app_read else R.string.native_app_unread))
        if (!state.supportsPiv) Text(stringResource(R.string.workspace_pivReaderRequired),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        HomeTask(stringResource(R.string.native_technical), null, 3, enabled, onTechnical)
    }
}

@Composable
private fun HomeTask(title: String, subtitle: String?, icon: Int, enabled: Boolean, onClick: () -> Unit,
    status: String? = null, prepare: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(managerDestinationIcons[icon], null, Modifier.padding(top = 4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(if (status != null) "$it · $status" else it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (prepare != null) TextButton(onClick = prepare, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.native_prepare))
            }
        }
        Text("›", modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
