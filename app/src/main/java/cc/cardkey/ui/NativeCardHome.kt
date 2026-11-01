package cc.cardkey.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
        Text(stringResource(R.string.native_card), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (artwork !is CardArtworkState.Loaded) Box(Modifier.width(112.dp)) {
                        CardArtworkHero(artwork, onRetry)
                    }
                    Column(Modifier.weight(1f)) {
                        val observed = state.canEnterFidoSecrets || state.canEnterPivSecrets
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusMark(if (!state.hasSelectedReader) StatusKind.WARNING else if (observed) StatusKind.OK else StatusKind.UNKNOWN)
                            Text(stringResource(if (!state.hasSelectedReader) R.string.native_reader_missing
                                else if (observed) R.string.native_observed else R.string.native_unread), style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(onClick = onReaders, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                            Text(if (!state.hasSelectedReader) stringResource(R.string.native_choose_reader)
                                else if (state.nfc) "NFC" else state.selectedReader?.device?.productName ?: "USB")
                        }
                    }
                }
                if (artwork is CardArtworkState.Loaded) CardArtworkHero(artwork, onRetry)
                Text(stringResource(if (!state.hasSelectedReader) R.string.native_choose_reader_hint
                    else if (state.nfc) R.string.native_tap else R.string.native_usb),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onRead, enabled = enabled && state.hasSelectedReader && (!state.requiresInspection || ConfirmationPolicy.canInspect(ManagerOperation.FIDO_INFO)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-read-card")) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_download), null, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.native_read))
                }
                Text(stringResource(R.string.native_read_scope), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            HomeTask(stringResource(R.string.navigation_passkeys), "FIDO2", 0, enabled, onFido,
                status = stringResource(if (state.canEnterFidoSecrets) R.string.native_app_read else R.string.native_app_unread),
                prepare = onPrepare)
            HorizontalDivider()
            HomeTask(stringResource(R.string.native_certificates), "PIV", 1, enabled && state.supportsPiv, onPiv,
                status = stringResource(if (state.canEnterPivSecrets) R.string.native_app_read else R.string.native_app_unread))
            HorizontalDivider()
            HomeTask(stringResource(R.string.native_technical), null, 3, enabled, onTechnical)
        }
        if (!state.supportsPiv) Text(stringResource(R.string.workspace_pivReaderRequired),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeTask(title: String, subtitle: String?, icon: Int, enabled: Boolean, onClick: () -> Unit,
    status: String? = null, prepare: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(managerDestinationIcons[icon], null, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(if (status != null) "$it · $status" else it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (prepare != null) TextButton(onClick = prepare, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.native_prepare))
            }
        }
        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_right), null,
            Modifier.clearAndSetSemantics {}, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
