package cc.cardkey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.cardkey.protectedpiv.*
import cc.cardkey.protocol.piv.PivClient
import androidx.compose.ui.res.stringResource
import cc.cardkey.R

@Composable
fun ProtectedPivPanel(
    state: ProtectedPivState,
    blocked: Boolean,
    inspectionRequired: Boolean,
    clearInputs: Int,
    onDocument: (ProtectedPivDocument) -> Unit,
    onClear: () -> Unit,
    onRecognize: (ProtectedPivAction, Int, String) -> Unit,
    onExecute: (CharArray) -> Unit,
    protectedInspectionRequired: Boolean = false,
) {
    var action by remember { mutableStateOf(ProtectedPivAction.STATUS) }
    var slot by remember { mutableIntStateOf(0x9a) }
    var subject by remember { mutableStateOf("") }
    var pin by remember(state.generation, state.awaitingPin, inspectionRequired, clearInputs) { mutableStateOf("") }
    var confirm by remember(state.generation, state.awaitingPin, inspectionRequired, clearInputs) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state.generation, state.awaitingPin, inspectionRequired, clearInputs) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { pin = ""; confirm = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pin = ""; confirm = false }
    }
    val actionTitles = ProtectedPivAction.entries.associateWith { stringResource(protectedPivActionTitle(it)) }
    val pendingAction = state.action?.let(actionTitles::getValue).orEmpty()
    val pendingSlot = "%02X".format(state.slot)
    val erasureWarning = stringResource(R.string.protectedPiv_erasure)
    val enabled = !blocked && state.documentId == null
    val selecting = enabled && (!state.awaitingPin || inspectionRequired)
    val selectedAction = if (inspectionRequired) ProtectedPivAction.STATUS else action
    Surface(Modifier.fillMaxWidth().testTag("protected-piv-panel"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.protectedPiv_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.protectedPiv_introduction),
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { pin = ""; confirm = false; onDocument(ProtectedPivDocument.ISSUER) },
                enabled = enabled, modifier = Modifier.testTag("protected-piv-issuer")) { Text(stringResource(R.string.protectedPiv_chooseIssuer)) }
            state.issuerSubject?.let { Text(stringResource(R.string.protectedPiv_issuer, protectedPivDisplayText(it))) }
            state.issuerFingerprint?.let { Text(stringResource(R.string.protectedPiv_fingerprint, it), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.protectedPiv_issuerScope),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pin = ""; confirm = false; onDocument(ProtectedPivDocument.SIGNING_FILE) },
                enabled = enabled && state.issuerSubject != null, modifier = Modifier.testTag("protected-piv-file")) { Text(stringResource(R.string.protectedPiv_chooseFile)) }
            state.fileName?.let { Text(stringResource(R.string.protectedPiv_file, it, state.fileSize ?: 0)) }
            OutlinedButton(onClick = { pin = ""; confirm = false; onDocument(ProtectedPivDocument.PROFILE) },
                enabled = enabled && state.issuerSubject != null, modifier = Modifier.testTag("protected-piv-profile")) { Text(stringResource(R.string.protectedPiv_chooseProfile)) }
            Text(stringResource(R.string.protectedPiv_pickerScope), style = MaterialTheme.typography.bodySmall)
            if (state.profileReady) {
                Text(stringResource(R.string.protectedPiv_ready), modifier = Modifier.testTag("protected-piv-ready"))
                state.guid?.let { Text(stringResource(R.string.protectedPiv_guid, it), style = MaterialTheme.typography.bodySmall) }
            }
            if (inspectionRequired) Text(if (protectedInspectionRequired)
                stringResource(R.string.protectedPiv_inspection)
                else stringResource(R.string.protectedPiv_ordinaryInspection), color = MaterialTheme.colorScheme.error)
            Choice(stringResource(R.string.protectedPiv_operation), selectedAction, if (inspectionRequired) listOf(ProtectedPivAction.STATUS) else ProtectedPivAction.entries, actionTitles::getValue, selecting) { action = it; pin = ""; confirm = false }
            Choice(stringResource(R.string.protectedPiv_slot), slot, PivClient.slots, { "%02X".format(it) }, selecting) { slot = it; pin = ""; confirm = false }
            if (selectedAction == ProtectedPivAction.CSR) OutlinedTextField(subject, { subject = it },
                label = { Text(stringResource(R.string.protectedPiv_csrSubject)) }, enabled = selecting, modifier = Modifier.fillMaxWidth())
            if (!state.awaitingPin || inspectionRequired) {
                Text(stringResource(R.string.protectedPiv_recognitionScope), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pin = ""; confirm = false; onRecognize(selectedAction, slot, subject) },
                    enabled = selecting && state.profileReady && (selectedAction != ProtectedPivAction.RAW_SIGN || state.fileSize != null) &&
                        (selectedAction != ProtectedPivAction.CSR || subject.isNotBlank()), modifier = Modifier.testTag("protected-piv-recognize")) {
                    Text(stringResource(R.string.protectedPiv_recognize))
                }
            } else {
                Text(stringResource(R.string.protectedPiv_recognized))
                Text(stringResource(R.string.protectedPiv_pending, pendingAction, pendingSlot))
                state.retries?.let { Text(stringResource(R.string.protectedPiv_retries, it)) }
                if (state.erasesKeysOnFailure) Text(erasureWarning, color = MaterialTheme.colorScheme.error)
                if (state.retries == 1) Text(stringResource(R.string.protectedPiv_lastRetry), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(pin, { pin = it }, label = { Text(stringResource(R.string.protectedPiv_pin)) }, singleLine = true,
                    enabled = enabled, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("protected-piv-pin"))
                Text(stringResource(R.string.protectedPiv_pinScope), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirm = true }, enabled = enabled && pin.length in 6..8 && pin.all { it in '0'..'9' },
                    modifier = Modifier.testTag("protected-piv-execute")) { Text(stringResource(R.string.protectedPiv_execute)) }
            }
            OutlinedButton(onClick = { pin = ""; confirm = false; onClear() }, enabled = !blocked,
                modifier = Modifier.testTag("protected-piv-clear")) { Text(stringResource(R.string.protectedPiv_clear)) }
            Text(stringResource(R.string.protectedPiv_contactScope),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm && state.awaitingPin && enabled && !inspectionRequired) AlertDialog(
        onDismissRequest = { confirm = false; pin = "" },
        title = { Text(stringResource(R.string.protectedPiv_confirmTitle)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.protectedPiv_confirmOperation, pendingAction, pendingSlot))
            Text(stringResource(R.string.protectedPiv_confirmRisk))
            if (state.erasesKeysOnFailure) Text(erasureWarning)
            if (state.retries == 1) Text(stringResource(R.string.protectedPiv_confirmLastRetry))
        } },
        confirmButton = { TextButton(onClick = {
            val entered = pin.toCharArray()
            pin = ""; confirm = false
            onExecute(entered)
        }) { Text(stringResource(R.string.protectedPiv_scanAgain)) } },
        dismissButton = { TextButton(onClick = { pin = ""; confirm = false }) { Text(stringResource(R.string.protectedPiv_cancel)) } },
    )
}

internal fun protectedPivActionTitle(action: ProtectedPivAction): Int = when (action) {
    ProtectedPivAction.STATUS -> R.string.protectedPiv_actionStatus
    ProtectedPivAction.SLOT -> R.string.protectedPiv_actionSlot
    ProtectedPivAction.SSH -> R.string.protectedPiv_actionSsh
    ProtectedPivAction.VERIFY_PIN -> R.string.protectedPiv_actionPin
    ProtectedPivAction.CSR -> R.string.protectedPiv_actionCsr
    ProtectedPivAction.RAW_SIGN -> R.string.protectedPiv_actionSign
    ProtectedPivAction.ECDH -> R.string.protectedPiv_actionEcdh
    ProtectedPivAction.RSA -> R.string.protectedPiv_actionRsa
}
