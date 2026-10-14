package cc.cardkey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.cardkey.R
import cc.cardkey.enrollment.EnrollmentProfile
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.tls.PivTlsOutcome
import java.text.DateFormat

@Composable
fun TlsQualificationPanel(state: TlsQualificationState, enrollment: EnrollmentState, busy: Boolean,
                          canUseCard: Boolean, clearInputs: Int, onSettings: (String) -> Unit,
                          onRoot: () -> Unit, onSubmit: (TlsQualificationInput) -> Unit, onCancel: () -> Unit, viewportHeight: Dp) {
    var pin by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<TlsQualificationInput?>(null) }
    val bundle = requireNotNull(enrollment.bundle)
    val expiry = remember(bundle) { DateFormat.getDateTimeInstance().format(EnrollmentProfile.certificate(bundle.leaf).notAfter) }
    LaunchedEffect(clearInputs, state.revision, enrollment.bundle, canUseCard) {
        pin = ""; pending?.close(); pending = null
    }
    LaunchedEffect(busy) { if (busy) { pin = ""; pending?.close(); pending = null } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { pin = ""; pending?.close(); pending = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pending?.close() }
    }
    pending?.let { input ->
        AlertDialog(onDismissRequest = { input.close(); pending = null },
            title = { Text(stringResource(R.string.tls_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tls_confirm, input.address))
                Text(stringResource(R.string.tls_identity), style = MaterialTheme.typography.labelMedium)
                Text(enrollment.cn)
                Text("P-256 · 9A")
                Text(stringResource(R.string.tls_serverRoot), style = MaterialTheme.typography.labelMedium)
                Text(state.serverRootSubject.orEmpty())
            } },
            confirmButton = { TextButton(onClick = {
                pending = null
                if (!busy && canUseCard && input.revision == state.revision && input.bundle === bundle) onSubmit(input)
                else input.close()
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { input.close(); pending = null }) { Text("取消") } })
    }
    Column(Modifier.fillMaxWidth().testTag("tls-panel"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.tls_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.tls_scope), style = MaterialTheme.typography.bodyMedium)
        state.result?.let { result ->
            PlexoSection(stringResource(R.string.plexo_tls_result), Modifier.testTag("tls-result")) {
                PlexoFactRow(stringResource(R.string.tls_endpoint), state.address)
                Text(stringResource(when (result.outcome) {
                    PivTlsOutcome.QUALIFIED -> R.string.tls_success
                    PivTlsOutcome.FAILED -> R.string.tls_failed
                    PivTlsOutcome.CANCELLED -> R.string.tls_cancelled
                    PivTlsOutcome.TIMED_OUT -> R.string.tls_timeout
                }), style = MaterialTheme.typography.bodyLarge)
                if (result.authenticationMayHaveBeenSubmitted && result.outcome in setOf(PivTlsOutcome.CANCELLED, PivTlsOutcome.TIMED_OUT))
                    Text(stringResource(R.string.tls_mayHaveSubmitted), style = MaterialTheme.typography.bodyLarge)
                else if (result.authenticationMayHaveBeenSubmitted && result.outcome == PivTlsOutcome.FAILED)
                    Text(stringResource(R.string.tls_unknown), style = MaterialTheme.typography.bodyLarge)
            }
        }
        PlexoSection(stringResource(R.string.plexo_tls_certificate), Modifier.testTag("tls-identity")) {
            PlexoFactRow(stringResource(R.string.tls_identity), enrollment.cn)
            Text("P-256 · 9A")
            PlexoFactRow(stringResource(R.string.tls_validUntil), expiry)
        }
        PlexoSection(stringResource(R.string.plexo_tls_service), Modifier.testTag("tls-service")) {
            OutlinedTextField(state.address, onSettings, enabled = !busy,
                label = { Text(stringResource(R.string.tls_endpoint)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight).testTag("tls-endpoint"))
            Text(stringResource(R.string.tls_endpointHelp), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { pin = ""; pending?.close(); pending = null; onRoot() }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("tls-root")) {
                Text(stringResource(R.string.tls_chooseServerRoot))
            }
            state.serverRootSubject?.let {
                Text(stringResource(R.string.tls_serverRootReady))
                PlexoFactRow(stringResource(R.string.tls_serverRoot), it)
            }
            Text(stringResource(R.string.tls_serverRootHelp), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(pin, { pin = it }, enabled = !busy, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight).testTag("tls-pin"))
            Text("6–8 位数字", style = MaterialTheme.typography.bodyMedium)
            Button(modifier = Modifier.heightIn(min = 48.dp).testTag("tls-start"), onClick = {
                pending = TlsQualificationInput(state.revision, bundle, state.address, pin.toCharArray())
                pin = ""
            }, enabled = !busy && canUseCard && state.serverRootSubject != null && state.address.isNotBlank() &&
                pin.length in 6..8 && pin.all { it in '0'..'9' }) { Text(stringResource(R.string.tls_start)) }
            if (state.running) {
                Text(stringResource(R.string.tls_running))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(modifier = Modifier.heightIn(min = 48.dp).testTag("tls-cancel"),
                    onClick = { pin = ""; pending?.close(); pending = null; onCancel() }) { Text("取消") }
            }
        }
    }
}
