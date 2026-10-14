package cc.cardkey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import cc.cardkey.R
import cc.cardkey.enrollment.*
import cc.cardkey.protocol.hexBytes
import cc.cardkey.protocol.piv.ManagementAlgorithm
import cc.cardkey.protocol.piv.KeyAlgorithm
import cc.cardkey.protocol.piv.PivClient

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnrollmentPanel(state: EnrollmentState, busy: Boolean, canUseCard: Boolean, clearInputs: Int,
                    onSettings: (String, String) -> Unit, onRoot: () -> Unit,
                    onSubmit: (OperationInput) -> Unit, onIssue: (CharArray, Long) -> Unit,
                    onExport: (ManagerReport) -> Unit, onCancel: () -> Unit, onAbandon: () -> Unit,
                    onSelection: (EnrollmentSelection) -> Unit, viewportHeight: Dp) {
    var pin by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(ManagementAlgorithm.TDES) }
    var pending by remember { mutableStateOf<PendingOperation?>(null) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(clearInputs, state.revision, canUseCard) {
        pin = ""; admin = ""; token = ""; pending?.close(); pending = null; invalid = false
    }
    LaunchedEffect(busy) { if (busy) { pin = ""; admin = ""; token = ""; pending?.close(); pending = null } }
    DisposableEffect(Unit) { onDispose { pending?.close() } }
    pending?.let { operation ->
        AlertDialog(onDismissRequest = { operation.close(); pending = null },
            title = { Text(operation.operation.title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                operation.messages.forEach { Text(stringResource(it)) }
            } },
            confirmButton = { TextButton(onClick = { pending = null; operation.dispatch(onSubmit) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { operation.close(); pending = null }) { Text("取消") } })
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.enrollment_title), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EnrollmentPurpose.entries.forEach { purpose ->
                    FilterChip(selected = state.selection.purpose == purpose, enabled = !busy,
                        onClick = { if (state.selection.purpose != purpose) onSelection(EnrollmentSelection(purpose)) },
                        label = { Text(stringResource(if (purpose == EnrollmentPurpose.TLS) R.string.enrollment_purpose else R.string.enrollment_document)) })
                }
            }
            if (state.selection.purpose == EnrollmentPurpose.DOCUMENT) {
                Choice("槽位", state.selection.slot, PivClient.slots, { "%02X".format(it) }, !busy) {
                    onSelection(state.selection.copy(slot = it))
                }
                Choice("算法", state.selection.algorithm, KeyAlgorithm.entries, { it.name }, !busy) {
                    onSelection(state.selection.copy(algorithm = it))
                }
                Text(stringResource(R.string.enrollment_documentScope), style = MaterialTheme.typography.bodySmall)
                if (state.selection.slot == 0x9a) Text(stringResource(R.string.enrollment_document9a), style = MaterialTheme.typography.bodySmall)
            } else Text("P-256 · 9A")
            Text(stringResource(R.string.workspace_enrollmentFlow), style = MaterialTheme.typography.bodySmall)
            val message = when (state.stage) {
                EnrollmentStage.CSR -> R.string.enrollment_csrReady
                EnrollmentStage.ISSUING -> R.string.enrollment_issuing
                EnrollmentStage.VERIFIED -> R.string.enrollment_verified
                EnrollmentStage.UNKNOWN -> R.string.enrollment_unknown
                EnrollmentStage.REJECTED -> R.string.enrollment_rejected
                EnrollmentStage.INVALID -> R.string.enrollment_invalid
                EnrollmentStage.IMPORTED -> R.string.enrollment_imported
                EnrollmentStage.EMPTY -> null
            }
            message?.let { Text(stringResource(R.string.enrollment_status), style = MaterialTheme.typography.titleMedium); Text(stringResource(it)) }
            state.unknownReceipts.filterNot { it.csr === state.receipt?.csr && state.stage in setOf(EnrollmentStage.ISSUING, EnrollmentStage.UNKNOWN) }.forEach { receipt ->
                Text(stringResource(R.string.enrollment_unknown))
                Text(stringResource(R.string.enrollment_cn), style = MaterialTheme.typography.labelMedium)
                Text(receipt.csr.cn)
                Text(stringResource(R.string.enrollment_address), style = MaterialTheme.typography.labelMedium)
                Text(receipt.endpoint)
            }
            OutlinedTextField(state.address, { onSettings(it, state.cn) }, enabled = !busy,
                label = { Text(stringResource(R.string.enrollment_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
            OutlinedTextField(state.cn, { onSettings(state.address, it) }, enabled = !busy,
                label = { Text(stringResource(R.string.enrollment_cn)) }, singleLine = true, modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
            OutlinedButton(onClick = onRoot, enabled = !busy) { Text(stringResource(R.string.enrollment_chooseRoot)) }
            if (state.hasRoot) Text(stringResource(R.string.enrollment_root))
            Text(stringResource(R.string.enrollment_steps), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(pin, { pin = it }, enabled = !busy, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
            Text("6–8 位数字", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = pin.toCharArray(), text = state.cn,
                    enrollmentSelection = state.selection, enrollmentRevision = state.revision)
                pin = ""; token = ""; admin = ""
                pending = PendingOperation(input)
            }, enabled = !busy && canUseCard && state.hasRoot && state.cn.isNotBlank() && state.address.isNotBlank()) {
                Text(stringResource(R.string.enrollment_createCsr))
            }
            state.csr?.let { csr ->
                OutlinedButton(onClick = { onExport(ManagerReport(emptyList(), csr.pem().toByteArray(), "piv-${csr.selection.slot.toString(16)}.csr", enrollmentCsr = csr)) }, enabled = !busy) {
                    Text(stringResource(R.string.enrollment_exportCsr))
                }
                Text(stringResource(R.string.enrollment_scope), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(token, { token = it }, enabled = !busy && !state.attempted,
                    label = { Text(stringResource(R.string.enrollment_token)) }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
                Button(onClick = { val secret = token.toCharArray(); token = ""; pin = ""; admin = ""; onIssue(secret, state.revision) },
                    enabled = !busy && canUseCard && !state.attempted && token.isNotBlank()) { Text(stringResource(R.string.enrollment_issue)) }
            }
            if (state.stage == EnrollmentStage.VERIFIED) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManagementAlgorithm.entries.forEach { value ->
                        FilterChip(selected = value == algorithm, onClick = { algorithm = value }, enabled = !busy, label = { Text(value.name) })
                    }
                }
                OutlinedTextField(admin, { admin = it }, enabled = !busy, label = { Text("管理密钥") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
                Button(onClick = {
                    try {
                        val key = admin.hexBytes()
                        if (key.size != algorithm.keySize) { key.fill(0); throw IllegalArgumentException() }
                        val input = OperationInput(ManagerOperation.PIV_ENROLL_IMPORT, adminKey = key, managementAlgorithm = algorithm,
                            enrollmentSelection = state.selection, enrollmentRevision = state.revision)
                        admin = ""; pin = ""; token = ""; pending = PendingOperation(input)
                    } catch (_: IllegalArgumentException) { admin = ""; invalid = true }
                }, enabled = !busy && canUseCard) { Text(stringResource(R.string.enrollment_import)) }
            }
            state.bundle?.let { bundle ->
                OutlinedButton(onClick = {
                    onExport(ManagerReport(emptyList(), bundle.pem().toByteArray(), "piv-${requireNotNull(state.csr).selection.slot.toString(16)}-chain.pem", enrollmentCsr = state.csr))
                }, enabled = !busy) { Text("导出") }
            }
            if (invalid) Text(stringResource(R.string.error_invalidInput), color = MaterialTheme.colorScheme.error)
            if (busy) OutlinedButton(onClick = onCancel) { Text("取消") }
            else if (state.csr != null) OutlinedButton(onClick = { pin = ""; admin = ""; token = ""; pending?.close(); pending = null; onAbandon() }) { Text("取消") }
        }
    }
}
