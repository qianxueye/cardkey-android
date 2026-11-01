package cc.cardkey.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
        .selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val icon = if (destination == 1) R.drawable.ic_license else R.drawable.ic_passkey
        deviceContext()
        Text(stringResource(if (destination == 1) R.string.navigation_certificates else R.string.navigation_passkeys), style = MaterialTheme.typography.headlineSmall)
        ObjectOption(stringResource(if (destination == 1) R.string.workspace_pivSecurity else R.string.workspace_authenticator),
            null, false, enabled, "object-overview", icon, onOverview)
        if (destination == 1) {
            Text(stringResource(R.string.workspace_slotCatalog), style = MaterialTheme.typography.titleSmall)
            PivClient.slots.forEach { candidate ->
                ObjectOption("%02X".format(candidate), stringResource(when (candidate) {
                    0x9a -> R.string.workspace_slotAuthentication
                    0x9c -> R.string.workspace_slotSignature
                    0x9d -> R.string.workspace_slotManagement
                    0x9e -> R.string.workspace_slotCardAuthentication
                    else -> R.string.workspace_slotRetired
                }), slot == candidate, enabled, "object-slot-%02X".format(candidate), icon) { onSlot(candidate) }
            }
        } else {
            Text(stringResource(R.string.workspace_sites), style = MaterialTheme.typography.titleSmall)
            if (inventory.load != InventoryLoad.UNREAD) Text(stringResource(when (inventory.load) {
                InventoryLoad.UNREAD -> R.string.inventory_unread
                InventoryLoad.LOADING -> R.string.inventory_loading
                InventoryLoad.FAILED -> R.string.inventory_failed
                InventoryLoad.LOADED -> if (inventory.credentials.isEmpty()) R.string.inventory_empty else R.string.inventory_loaded
            }))
            val rows = inventory.credentials.map { credential ->
                val name = credential.name?.takeIf { it.isNotBlank() }
                Triple(credential, credential.displayName?.takeIf { it.isNotBlank() } ?: name ?: credential.idHex, name)
            }
            val labels = rows.groupingBy { (credential, title, name) -> Triple(credential.rp.id, title, name) }.eachCount()
            rows.forEach { (credential, title, name) ->
                val ambiguous = name == null || labels.getValue(Triple(credential.rp.id, title, name)) > 1
                val detail = buildList {
                    if (name != null && name != title) add(name)
                    add(credential.rp.id)
                    if (ambiguous && title != credential.idHex) add("凭据 ID · ${credential.idHex}")
                }.joinToString("\n")
                ObjectOption(title, detail, inventory.credential == credential, enabled,
                    "object-credential-${credential.idHex}", icon) { onCredential(credential) }
            }
            Text(stringResource(R.string.workspace_security), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onFidoPin, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
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
private fun ObjectOption(title: String, detail: String?, selected: Boolean, enabled: Boolean, tag: String, icon: Int, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon), null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.clearAndSetSemantics {})
        }
    }
}
