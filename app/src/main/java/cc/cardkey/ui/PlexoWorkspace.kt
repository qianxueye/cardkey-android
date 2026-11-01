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
        if (destination == 1) {
            ObjectGroup(listOf<@Composable () -> Unit>({
                ObjectOption(stringResource(R.string.workspace_pivSecurity), null, false, enabled, "object-overview", icon, onOverview)
            }) + PivClient.slots.map { candidate ->
                {
                    ObjectOption("%02X".format(candidate), stringResource(when (candidate) {
                        0x9a -> R.string.workspace_slotAuthentication
                        0x9c -> R.string.workspace_slotSignature
                        0x9d -> R.string.workspace_slotManagement
                        0x9e -> R.string.workspace_slotCardAuthentication
                        else -> R.string.workspace_slotRetired
                    }), slot == candidate, enabled, "object-slot-%02X".format(candidate), icon) { onSlot(candidate) }
                }
            })
        } else {
            if (inventory.load != InventoryLoad.UNREAD && !(inventory.load == InventoryLoad.LOADED && inventory.credentials.isNotEmpty()))
                Text(stringResource(when (inventory.load) {
                    InventoryLoad.UNREAD, InventoryLoad.LOADED -> if (inventory.load == InventoryLoad.LOADED) R.string.inventory_empty else R.string.inventory_unread
                    InventoryLoad.LOADING -> R.string.inventory_loading
                    InventoryLoad.FAILED -> R.string.inventory_failed
                }))
            val names = inventory.credentials.map { it.name?.takeIf { name -> name.isNotBlank() } }
            val counts = inventory.credentials.indices.groupingBy { inventory.credentials[it].rp.id to names[it] }.eachCount()
            val shortIds = shortCredentialIds(inventory.credentials)
            if (inventory.credentials.isNotEmpty()) ObjectGroup(inventory.credentials.mapIndexed { index, credential ->
                {
                    val name = names[index]
                    val ambiguous = name == null || counts.getValue(credential.rp.id to name) > 1
                    val account = name ?: credential.displayName?.takeIf { it.isNotBlank() }
                    val shortId = shortIds.getValue(credential)
                    val detail = listOfNotNull(account, shortId.takeIf { ambiguous || account == null }).joinToString("  ")
                    ObjectOption(credential.rp.id, detail.ifEmpty { null }, inventory.credential == credential, enabled,
                        "object-credential-${credential.idHex}", icon) { onCredential(credential) }
                }
            })
            ObjectGroup(listOf<@Composable () -> Unit>({
                ObjectOption(stringResource(R.string.workspace_fidoPin), null, false, enabled, "object-fido-pin", R.drawable.ic_lock) { onFidoPin() }
            }))
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onReadCredentials, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.workspace_readSites))
            }
        }
    }
}

@Composable
private fun ObjectGroup(rows: List<@Composable () -> Unit>) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                row()
            }
        }
    }
}

@Composable
private fun ObjectOption(title: String, detail: String?, selected: Boolean, enabled: Boolean, tag: String, icon: Int, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon), null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.78f)) }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.clearAndSetSemantics {})
        }
    }
}

/** Shortens each credential id to the shortest prefix…suffix that is unique among credentials with the same site and account; falls back to the full id. */
internal fun shortCredentialIds(credentials: List<InventoryCredential>): Map<InventoryCredential, String> {
    val result = HashMap<InventoryCredential, String>()
    credentials.groupBy { it.rp.id to (it.name?.takeIf { name -> name.isNotBlank() } ?: it.displayName?.takeIf { name -> name.isNotBlank() }) }
        .values.forEach { group ->
            var edge = 4
            var shortened = group.map { it.idHex }
            while (edge * 2 < (group.maxOfOrNull { it.idHex.length } ?: 0)) {
                val candidate = group.map { "${it.idHex.take(edge)}…${it.idHex.takeLast(edge)}" }
                if (candidate.toSet().size == candidate.size) { shortened = candidate; break }
                edge += 2
            }
            group.forEachIndexed { index, credential -> result[credential] = shortened[index] }
        }
    return result
}
