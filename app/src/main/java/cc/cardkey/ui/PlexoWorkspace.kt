package cc.cardkey.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.cardkey.R
import cc.cardkey.protocol.piv.PivClient

internal fun workspaceUsesTwoPanes(width: Dp, fontScale: Float, height: Dp): Boolean =
    width >= (280 + 420 * fontScale).dp && height >= 600.dp

internal fun workspaceOperationGroups(operations: List<ManagerOperation>): List<List<ManagerOperation>> {
    val advanced = setOf(ManagerOperation.FIDO_RESET, ManagerOperation.FIDO_ALWAYS_UV,
        ManagerOperation.FIDO_MIN_PIN, ManagerOperation.FIDO_ENTERPRISE, ManagerOperation.FIDO_REGISTER,
        ManagerOperation.FIDO_ASSERT, ManagerOperation.U2F_INFO, ManagerOperation.U2F_REGISTER,
        ManagerOperation.U2F_ASSERT, ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_GENERATE,
        ManagerOperation.PIV_ECDH, ManagerOperation.PIV_DECRYPT, ManagerOperation.PIV_RESET)
    return listOf(operations.filterNot { it in advanced }, operations.filter { it in advanced })
}

@Composable
internal fun PlexoObjects(
    destination: Int,
    slot: Int,
    inventory: InventoryState,
    enabled: Boolean,
    scroll: ScrollState,
    deviceContext: @Composable () -> Unit,
    onOverview: () -> Unit,
    onSlot: (Int) -> Unit,
    onRp: (InventoryRp) -> Unit,
    onCredential: (InventoryCredential) -> Unit,
    onFidoPin: () -> Unit,
    onReadCredentials: () -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("workspace-objects").verticalScroll(scroll).padding(16.dp)
        .selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        deviceContext()
        Text(stringResource(if (destination == 1) R.string.navigation_certificates else R.string.navigation_passkeys), style = MaterialTheme.typography.headlineSmall)
        Text(if (destination == 1) "PIV" else "FIDO2", style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.workspace_selectionOnly), style = MaterialTheme.typography.bodyMedium)
        ObjectOption(stringResource(if (destination == 1) R.string.workspace_pivSecurity else R.string.workspace_authenticator),
            null, false, enabled, "object-overview", onOverview)
        if (destination == 1) {
            Text(stringResource(R.string.workspace_slotCatalog), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.workspace_slotContext), style = MaterialTheme.typography.bodyMedium)
            PivClient.slots.forEach { candidate ->
                ObjectOption("%02X".format(candidate), stringResource(when (candidate) {
                    0x9a -> R.string.workspace_slotAuthentication
                    0x9c -> R.string.workspace_slotSignature
                    0x9d -> R.string.workspace_slotManagement
                    0x9e -> R.string.workspace_slotCardAuthentication
                    else -> R.string.workspace_slotRetired
                }), slot == candidate, enabled, "object-slot-%02X".format(candidate)) { onSlot(candidate) }
            }
        } else {
            Text(stringResource(R.string.workspace_sites), style = MaterialTheme.typography.titleSmall)
            if (inventory.rps.isEmpty()) Text(stringResource(R.string.workspace_credentialsUnread))
            inventory.rps.forEach { rp ->
                ObjectOption(rp.id, rp.name, inventory.rp == rp, enabled, "object-rp-${rp.id}") { onRp(rp) }
            }
            if (inventory.rp != null) {
                Text(stringResource(R.string.workspace_credentials), style = MaterialTheme.typography.titleSmall)
                if (inventory.credentials.isEmpty()) Text(stringResource(R.string.workspace_credentialReadRequired))
                val rows = inventory.credentials.map { credential ->
                    val name = credential.name?.takeIf { it.isNotBlank() }
                    val title = credential.displayName?.takeIf { it.isNotBlank() } ?: name ?: credential.idHex
                    Triple(credential, title, name)
                }
                val labelCounts = rows.groupingBy { (credential, title, name) ->
                    Triple(credential.rp.id, title, name)
                }.eachCount()
                rows.forEach { (credential, title, name) ->
                    val ambiguous = name == null || labelCounts.getValue(Triple(credential.rp.id, title, name)) > 1
                    val details = buildList {
                        if (name != null && name != title) add(name)
                        add(credential.rp.id)
                        if (ambiguous && title != credential.idHex) add("凭据 ID · ${credential.idHex}")
                    }.joinToString("\n")
                    ObjectOption(title, details, inventory.credential == credential, enabled,
                        "object-credential-${credential.idHex}") { onCredential(credential) }
                }
            }
            Text(stringResource(R.string.workspace_security), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onFidoPin, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.workspace_fidoPin))
            }
            Text(stringResource(R.string.workspace_createOnWebsite), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onReadCredentials, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.workspace_readSites))
            }
        }
    }
}

@Composable
private fun ObjectOption(title: String, detail: String?, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
