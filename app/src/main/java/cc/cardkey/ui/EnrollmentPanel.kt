package cc.cardkey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    Column(Modifier.fillMaxWidth().testTag("enrollment-panel"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.enrollment_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.workspace_enrollmentFlow), style = MaterialTheme.typography.bodyMedium)
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
        if (state.csr != null || message != null) {
            PlexoSection(stringResource(R.string.plexo_ca_currentRequest), Modifier.testTag("enrollment-request")) {
                state.csr?.let { csr ->
                    val receipt = state.receipt?.takeIf { it.csr === csr }
                    EnrollmentRequestFacts(csr, receipt?.endpoint ?: state.address, receipt?.ca)
                }
                message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
                if (state.stage == EnrollmentStage.ISSUING) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.unknownReceipts.filterNot { it.csr === state.receipt?.csr && state.stage in setOf(EnrollmentStage.ISSUING, EnrollmentStage.UNKNOWN) }.forEach { receipt ->
            PlexoSection(stringResource(R.string.plexo_ca_unknownRequest), Modifier.testTag("enrollment-unknown-receipt")) {
                EnrollmentRequestFacts(receipt.csr, receipt.endpoint, receipt.ca)
                Text(stringResource(R.string.enrollment_unknown), style = MaterialTheme.typography.bodyLarge)
            }
        }
        PlexoSection(stringResource(R.string.plexo_ca_prepare), Modifier.testTag("enrollment-preparation")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EnrollmentPurpose.entries.forEach { purpose ->
                    FilterChip(selected = state.selection.purpose == purpose, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
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
                Text(stringResource(R.string.enrollment_documentScope), style = MaterialTheme.typography.bodyMedium)
                if (state.selection.slot == 0x9a) Text(stringResource(R.string.enrollment_document9a), style = MaterialTheme.typography.bodyMedium)
            } else Text("P-256 · 9A")
            OutlinedTextField(state.address, { onSettings(it, state.cn) }, enabled = !busy,
                label = { Text(stringResource(R.string.enrollment_address)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight).testTag("enrollment-address"))
            OutlinedTextField(state.cn, { onSettings(state.address, it) }, enabled = !busy,
                label = { Text(stringResource(R.string.enrollment_cn)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight).testTag("enrollment-cn"))
            OutlinedButton(onClick = onRoot, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-root")) {
                Text(stringResource(R.string.enrollment_chooseRoot))
            }
            if (state.hasRoot) Text(stringResource(R.string.plexo_ca_rootReady))
            Text(stringResource(R.string.enrollment_steps), style = MaterialTheme.typography.bodyMedium)
        }
        if (canUseCard) {
            PlexoSection(stringResource(R.string.plexo_ca_csr), Modifier.testTag("enrollment-csr")) {
                OutlinedTextField(pin, { pin = it }, enabled = !busy, label = { Text("当前 PIV PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight).testTag("enrollment-pin"))
                Text("6–8 位数字", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = pin.toCharArray(), text = state.cn,
                        enrollmentSelection = state.selection, enrollmentRevision = state.revision)
                    pin = ""; token = ""; admin = ""
                    pending = PendingOperation(input)
                }, enabled = !busy && canUseCard && state.hasRoot && state.cn.isNotBlank() && state.address.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-create-csr")) {
                    Text(stringResource(R.string.enrollment_createCsr))
                }
            }
        }
        state.csr?.let { csr ->
            PlexoSection(stringResource(R.string.plexo_ca_issue), Modifier.testTag("enrollment-issuance")) {
                OutlinedButton(onClick = { onExport(ManagerReport(emptyList(), csr.pem().toByteArray(), "piv-${csr.selection.slot.toString(16)}.csr", enrollmentCsr = csr)) },
                    enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-export-csr")) {
                    Text(stringResource(R.string.enrollment_exportCsr))
                }
                Text(stringResource(R.string.enrollment_scope), style = MaterialTheme.typography.bodyMedium)
                if (!state.attempted) {
                    OutlinedTextField(token, { token = it }, enabled = !busy && !state.attempted,
                        label = { Text(stringResource(R.string.enrollment_token)) }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight).testTag("enrollment-token"))
                    Button(onClick = { val secret = token.toCharArray(); token = ""; pin = ""; admin = ""; onIssue(secret, state.revision) },
                        enabled = !busy && canUseCard && !state.attempted && token.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-issue")) { Text(stringResource(R.string.enrollment_issue)) }
                }
            }
        }
        if (state.stage == EnrollmentStage.VERIFIED || state.bundle != null) {
            PlexoSection(stringResource(R.string.plexo_ca_importExport), Modifier.testTag("enrollment-import-export")) {
                if (state.stage == EnrollmentStage.VERIFIED && canUseCard) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ManagementAlgorithm.entries.forEach { value ->
                            FilterChip(selected = value == algorithm, onClick = { algorithm = value }, enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp), label = { Text(value.name) })
                        }
                    }
                    OutlinedTextField(admin, { admin = it }, enabled = !busy, label = { Text("管理密钥") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight).testTag("enrollment-admin"))
                    Button(onClick = {
                        try {
                            val key = admin.hexBytes()
                            if (key.size != algorithm.keySize) { key.fill(0); throw IllegalArgumentException() }
                            val input = OperationInput(ManagerOperation.PIV_ENROLL_IMPORT, adminKey = key, managementAlgorithm = algorithm,
                                enrollmentSelection = state.selection, enrollmentRevision = state.revision)
                            admin = ""; pin = ""; token = ""; pending = PendingOperation(input)
                        } catch (_: IllegalArgumentException) { admin = ""; invalid = true }
                    }, enabled = !busy && canUseCard, modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-import")) {
                        Text(stringResource(R.string.plexo_ca_importReview))
                    }
                }
                state.bundle?.let { bundle ->
                    OutlinedButton(onClick = {
                        onExport(ManagerReport(emptyList(), bundle.pem().toByteArray(), "piv-${requireNotNull(state.csr).selection.slot.toString(16)}-chain.pem", enrollmentCsr = state.csr))
                    }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-export-chain")) {
                        Text(stringResource(R.string.plexo_ca_exportChain))
                    }
                }
            }
        }
        if (invalid) Text(stringResource(R.string.error_invalidInput), color = MaterialTheme.colorScheme.error)
        if (busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-cancel")) { Text("取消") }
        else if (state.csr != null) OutlinedButton(onClick = { pin = ""; admin = ""; token = ""; pending?.close(); pending = null; onAbandon() },
            modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-cancel")) { Text("取消") }
    }
}

@Composable
private fun EnrollmentRequestFacts(csr: EnrollmentCsr, address: String, ca: EnrollmentCA?) {
    PlexoFactRow(stringResource(R.string.plexo_ca_identity), csr.cn)
    PlexoFactRow(stringResource(R.string.plexo_ca_purpose), stringResource(
        if (csr.selection.purpose == EnrollmentPurpose.DOCUMENT) R.string.plexo_ca_documentIdentity else R.string.plexo_ca_tlsIdentity))
    PlexoFactRow(stringResource(R.string.plexo_ca_slotAlgorithm), "%02X · %s".format(csr.selection.slot, csr.selection.algorithm.name))
    PlexoFactRow(stringResource(R.string.plexo_ca_service), address)
    val rootSubject = remember(ca) { ca?.let { EnrollmentProfile.certificate(it.anchor).subjectX500Principal.name } }
    rootSubject?.let { PlexoFactRow(stringResource(R.string.plexo_ca_root), it) }
}
