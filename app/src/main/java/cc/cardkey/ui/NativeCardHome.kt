package cc.cardkey.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.cardkey.R

@Composable
internal fun NativeCardHome(
    state: ManagerState, artwork: CardArtworkState, passkeyCount: Int?,
    onPrepare: () -> Unit, onFido: () -> Unit, onPiv: () -> Unit, onReaders: () -> Unit, onRetry: () -> Unit,
) {
    val enabled = !state.busy && !state.permissionPending
    val observed = state.canEnterFidoSecrets || state.canEnterPivSecrets
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("native-card-home")) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onReaders).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(if (state.nfc) R.drawable.ic_contactless else R.drawable.ic_usb), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(if (!state.hasSelectedReader) stringResource(R.string.native_choose_reader)
                    else if (state.nfc) "NFC" else state.selectedReader?.device?.productName ?: "USB",
                    style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusMark(if (!state.hasSelectedReader) StatusKind.WARNING else if (observed) StatusKind.OK else StatusKind.PENDING)
                    Text(stringResource(if (!state.hasSelectedReader) R.string.native_reader_missing
                        else if (observed) R.string.native_observed else R.string.native_unread),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.clearAndSetSemantics {},
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardArtworkHero(artwork, onRetry, fullWidth = true)
        Text(stringResource(R.string.native_card), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            HomeTask(stringResource(R.string.navigation_passkeys), passkeyCount?.let { stringResource(R.string.native_count, it) },
                0, enabled, onFido, prepare = onPrepare)
            HorizontalDivider()
            HomeTask(stringResource(R.string.native_certificates), null, 1, enabled && state.supportsPiv, onPiv)
        }
        if (!state.supportsPiv) Text(stringResource(R.string.workspace_pivReaderRequired),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NativeReadButton(state: ManagerState, onRead: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = !state.busy && !state.permissionPending
    Button(onClick = onRead, enabled = enabled && state.hasSelectedReader &&
        (!state.requiresInspection || ConfirmationPolicy.canInspect(ManagerOperation.FIDO_INFO)),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-read-card")) {
        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.native_read))
    }
}

@Composable
private fun HomeTask(title: String, count: String?, icon: Int, enabled: Boolean, onClick: () -> Unit,
    prepare: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(managerDestinationIcons[icon], null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (prepare != null) TextButton(onClick = prepare, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.native_prepare))
            }
        }
        count?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.clearAndSetSemantics {},
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
