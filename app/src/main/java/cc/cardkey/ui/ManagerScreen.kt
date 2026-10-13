package cc.cardkey.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.cardkey.protocol.*
import cc.cardkey.R
import cc.cardkey.protocol.piv.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.transport.UsbReaderDescriptor

@Composable
fun CardKeyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerScreen(
    state: ManagerState,
    clearInputs: Int,
    onReader: (UsbReaderDescriptor?) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: (OperationInput) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    imported: ByteArray?,
    onExport: (ManagerReport) -> Unit,
    onLastRetry: (Boolean) -> Unit,
    onSecureChange: (Boolean) -> Unit,
    onProviderSettings: () -> Unit,
) {
    val displayName = stringResource(R.string.app_name)
    var tab by remember { mutableIntStateOf(0) }
    var operation by remember { mutableStateOf(ManagerOperation.FIDO_INFO) }
    var pin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var adminKey by remember { mutableStateOf("") }
    var newAdminKey by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var secondary by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var extensionNames by remember { mutableStateOf(setOf<String>()) }
    var extensionValue by remember { mutableStateOf("") }
    var disclosureRps by remember { mutableStateOf("") }
    var enterpriseMode by remember { mutableIntStateOf(0) }
    var protection by remember { mutableIntStateOf(3) }
    var showSecretConfirmation by remember { mutableStateOf(false) }
    var showSecrets by remember { mutableStateOf(false) }
    var exportConfirmation by remember { mutableStateOf<ManagerReport?>(null) }
    var slot by remember { mutableIntStateOf(0x9a) }
    var algorithm by remember { mutableStateOf(KeyAlgorithm.P256) }
    var pinPolicy by remember { mutableStateOf(PinPolicy.ONCE) }
    var adminAlgorithm by remember { mutableStateOf(ManagementAlgorithm.TDES) }
    var newAdminAlgorithm by remember { mutableStateOf(ManagementAlgorithm.AES256) }
    var invalid by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<PendingOperation?>(null) }
    val report = (state.result as? OperationResult.Completed)?.value
    val secretResult = report?.secrets != null || report?.protectedExport == true
    val secretForm = tab != 2 && (needsPin(operation) || operation in setOf(
        ManagerOperation.FIDO_SET_PIN, ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_GENERATE,
        ManagerOperation.PIV_IMPORT, ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE))
    SideEffect { onSecureChange(secretForm || secretResult || state.lastRetry != null) }
    LaunchedEffect(state.result) { showSecrets = false; showSecretConfirmation = false; exportConfirmation = null }
    LaunchedEffect(clearInputs, operation, state.selectedReader?.routeKey, state.nfc) {
        pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""; invalid = false
        confirmation?.close(); confirmation = null
        showSecrets = false; showSecretConfirmation = false; exportConfirmation = null
    }
    LaunchedEffect(operation, enabled) {
        if (operation == ManagerOperation.FIDO_REGISTER && !enabled) extensionNames = extensionNames - "largeBlobKey"
    }
    LaunchedEffect(state.supportsPiv) {
        if (!state.supportsPiv && tab == 1) { tab = 0; operation = ManagerOperation.FIDO_INFO }
    }
    LaunchedEffect(state.supportsFidoReset) {
        if (!state.supportsFidoReset && operation == ManagerOperation.FIDO_RESET) {
            confirmation?.close(); confirmation = null
            operation = ManagerOperation.FIDO_INFO
        }
    }
    DisposableEffect(Unit) { onDispose { confirmation?.close() } }
    confirmation?.let { input ->
        AlertDialog(onDismissRequest = { input.close(); confirmation = null },
            title = { Text(input.operation.title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                input.messages.forEach { Text(stringResource(it)) }
            } },
            confirmButton = { TextButton(onClick = { confirmation = null; input.dispatch(onSubmit) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { input.close(); confirmation = null }) { Text("取消") } })
    }
    if (showSecretConfirmation) AlertDialog(onDismissRequest = { showSecretConfirmation = false },
        title = { Text(stringResource(R.string.secret_view)) }, text = { Text(stringResource(R.string.confirm_secretShow)) },
        confirmButton = { TextButton(onClick = { showSecretConfirmation = false; showSecrets = true }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { showSecretConfirmation = false }) { Text("取消") } })
    exportConfirmation?.let { pending -> AlertDialog(onDismissRequest = { exportConfirmation = null },
        title = { Text("导出") }, text = { Text(stringResource(R.string.confirm_blobExport)) },
        confirmButton = { TextButton(onClick = { exportConfirmation = null; onExport(pending) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { exportConfirmation = null }) { Text("取消") } }) }
    state.lastRetry?.let { (current, other) ->
        AlertDialog(onDismissRequest = { onLastRetry(false) },
            title = { Text(state.operation?.title ?: "PIV") },
            text = { Text(stringResource(R.string.confirm_lastRetryErasesKeys)
                .replace("{当前验证项}", current).replace("{另一项}", other)) },
            confirmButton = { TextButton(onClick = { onLastRetry(true) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { onLastRetry(false) }) { Text("取消") } })
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            TextButton(onClick = onProviderSettings, enabled = !state.busy) { Text("凭据提供者") }
        }) },
        bottomBar = {
            NavigationBar {
                listOf("FIDO2", "PIV", "读卡器").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, enabled = index != 1 || state.supportsPiv, onClick = {
                        tab = index
                        if (index < 2) operation = if (index == 0) ManagerOperation.FIDO_INFO else ManagerOperation.PIV_INFO
                    }, icon = { Text(if (index == 0) "F" else if (index == 1) "P" else "↔") }, label = { Text(title) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tab == 2) {
                    Text("读卡器", style = MaterialTheme.typography.headlineSmall)
                    if (state.readers.isEmpty()) Text(stringResource(R.string.reader_empty))
                    if (state.permissionPending) Text(stringResource(R.string.reader_usbPermission, stringResource(R.string.app_name)))
                    OutlinedCard(onClick = { if (!state.busy) onReader(null) }, Modifier.fillMaxWidth()) {
                        ListItem(headlineContent = { Text("NFC") }, trailingContent = { RadioButton(state.nfc, onClick = null) })
                    }
                    state.readers.forEach { reader ->
                        OutlinedCard(onClick = { if (!state.busy) onReader(reader) }, Modifier.fillMaxWidth()) {
                            ListItem(headlineContent = { Text(reader.device.productName ?: "USB") },
                                supportingContent = { Text("USB · %04x:%04x".format(reader.device.vendorId, reader.device.productId)) },
                                trailingContent = { RadioButton(!state.nfc && state.selectedReader?.routeKey == reader.routeKey, onClick = null) })
                        }
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("读取") }
                } else {
                    Text(if (tab == 0) "FIDO2" else "PIV", style = MaterialTheme.typography.headlineSmall)
                    AssistChip(onClick = { tab = 2 }, label = { Text(if (state.nfc) "NFC" else state.selectedReader?.device?.productName ?: "读卡器") })
                    if (state.nfc) Text(stringResource(R.string.reader_nfc), style = MaterialTheme.typography.bodyMedium)
                    Choice("操作", operation, ManagerOperation.entries.filter { it.piv == (tab == 1) && (it != ManagerOperation.FIDO_RESET || state.supportsFidoReset) }, { it.title }, !state.busy) { operation = it }
                    val op = operation
                    if (op.piv && op !in setOf(ManagerOperation.PIV_INFO, ManagerOperation.PIV_CHANGE_PIN, ManagerOperation.PIV_CHANGE_PUK,
                            ManagerOperation.PIV_UNBLOCK, ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_RESET)) {
                        Choice("槽位", slot, PivClient.slots, { "%02X".format(it) }, !state.busy) { slot = it }
                    }
                    if (needsPin(op)) SecretField(if (op == ManagerOperation.PIV_CHANGE_PUK || op == ManagerOperation.PIV_UNBLOCK) "PUK" else "PIN", pin, !state.busy) { pin = it }
                    if (op.piv && needsPin(op)) Text("6–8 位数字", style = MaterialTheme.typography.bodySmall)
                    if (op in setOf(ManagerOperation.FIDO_SET_PIN, ManagerOperation.FIDO_CHANGE_PIN, ManagerOperation.PIV_CHANGE_PIN,
                            ManagerOperation.PIV_CHANGE_PUK, ManagerOperation.PIV_UNBLOCK)) {
                        SecretField(if (op == ManagerOperation.PIV_CHANGE_PUK) "新 PUK" else "新 PIN", newPin, !state.busy) { newPin = it }
                    }
                    if (op in setOf(ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_GENERATE, ManagerOperation.PIV_IMPORT)) {
                        Choice("管理密钥 · 算法", adminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { adminAlgorithm = it }
                        SecretField("管理密钥 · HEX", adminKey, !state.busy) { adminKey = it }
                    }
                    if (op in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)) {
                        Text(stringResource(R.string.blob_help))
                        if (op == ManagerOperation.FIDO_BLOB_WRITE) {
                            OutlinedButton(onClick = onImport, enabled = !state.busy) { Text("导入") }
                            imported?.let { Text("${it.size} bytes", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (op == ManagerOperation.PIV_ADMIN) {
                        Choice("新管理密钥 · 算法", newAdminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { newAdminAlgorithm = it }
                        SecretField("新管理密钥 · HEX", newAdminKey, !state.busy) { newAdminKey = it }
                    }
                    if (op == ManagerOperation.PIV_GENERATE) {
                        Choice("算法", algorithm, KeyAlgorithm.entries, { it.name }, !state.busy) { algorithm = it }
                        Choice("PIN", pinPolicy, PinPolicy.entries, { it.name }, !state.busy) { pinPolicy = it }
                    }
                    if (op in setOf(ManagerOperation.FIDO_CREDENTIALS, ManagerOperation.FIDO_DELETE, ManagerOperation.FIDO_UPDATE,
                            ManagerOperation.FIDO_MIN_PIN, ManagerOperation.PIV_CSR)) {
                        val label = when (op) {
                            ManagerOperation.FIDO_CREDENTIALS -> "RP SHA-256 · HEX"
                            ManagerOperation.FIDO_MIN_PIN -> "PIN · 最短长度"
                            ManagerOperation.PIV_CSR -> "Subject · CN=${stringResource(R.string.app_name)}"
                            else -> "凭据 ID · HEX"
                        }
                        OutlinedTextField(text, { text = it }, label = { Text(label) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    }
                    if (op == ManagerOperation.FIDO_MIN_PIN) {
                        OutlinedTextField(disclosureRps, { disclosureRps = it }, label = { Text(stringResource(R.string.minPin_rps)) },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.minPin_rpsHelp))
                    }
                    if (op == ManagerOperation.FIDO_UPDATE) {
                        Text(stringResource(R.string.user_updateHelp))
                        OutlinedTextField(secondary, { secondary = it }, label = { Text("名称") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    }
                    if (op in setOf(ManagerOperation.FIDO_ALWAYS_UV, ManagerOperation.FIDO_MIN_PIN, ManagerOperation.FIDO_REGISTER)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (op) { ManagerOperation.FIDO_ALWAYS_UV -> "始终验证用户"; ManagerOperation.FIDO_MIN_PIN -> "修改 PIN"; else -> "驻留凭据" }, Modifier.weight(1f))
                            Switch(enabled, {
                                enabled = it
                                if (op == ManagerOperation.FIDO_REGISTER && !it) extensionNames = extensionNames - "largeBlobKey"
                            }, enabled = !state.busy)
                        }
                    }
                    if (op == ManagerOperation.FIDO_REGISTER || op == ManagerOperation.FIDO_ASSERT) {
                        if (op == ManagerOperation.FIDO_REGISTER) Choice(stringResource(R.string.enterprise_mode), enterpriseMode,
                            listOf(0, 1, 2), { when (it) { 1 -> "厂商预设（1）"; 2 -> "平台管理（2）"; else -> "不请求" } }, !state.busy) { enterpriseMode = it }
                        Text("扩展", style = MaterialTheme.typography.labelLarge)
                        val names = if (op == ManagerOperation.FIDO_REGISTER)
                            listOf("hmac-secret", "largeBlobKey", "minPinLength", "credBlob", "credProtect", "uvm")
                        else listOf("hmac-secret", "largeBlobKey", "credBlob", "uvm")
                        names.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val available = name != "largeBlobKey" || op != ManagerOperation.FIDO_REGISTER || enabled
                                Checkbox(available && name in extensionNames, { checked -> extensionNames = if (checked) extensionNames + name else extensionNames - name }, enabled = !state.busy && available)
                                Text(if (name == "uvm") "用户验证方法" else name)
                            }
                        }
                        if ("credProtect" in extensionNames && op == ManagerOperation.FIDO_REGISTER)
                            Choice("credProtect", protection, listOf(1, 2, 3), { it.toString() }, !state.busy) { protection = it }
                        if ("credBlob" in extensionNames && op == ManagerOperation.FIDO_REGISTER || "hmac-secret" in extensionNames && op == ManagerOperation.FIDO_ASSERT) {
                            SecretField(if (op == ManagerOperation.FIDO_REGISTER) "credBlob · HEX" else "hmac-secret · salt · HEX", extensionValue, !state.busy) { extensionValue = it }
                        }
                    }
                    if (op == ManagerOperation.PIV_IMPORT) {
                        OutlinedButton(onClick = onImport, enabled = !state.busy) { Text("导入证书") }
                        imported?.let { Text("${it.size} bytes", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (invalid) Text(stringResource(R.string.error_invalidInput), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            try {
                                require(op != ManagerOperation.FIDO_BLOB_WRITE || imported != null)
                                val input = OperationInput(op, pin.toCharArray(), newPin.toCharArray(), adminKey.hexBytes(), newAdminKey.hexBytes(),
                                    adminAlgorithm, newAdminAlgorithm, slot, algorithm, pinPolicy, text, secondary, enabled,
                                    imported?.copyOf() ?: byteArrayOf(),
                                    makeExtensions = MakeExtensions(if ("credProtect" in extensionNames) protection else null,
                                        if ("credBlob" in extensionNames && op == ManagerOperation.FIDO_REGISTER) extensionValue.hexBytes() else null,
                                        "hmac-secret" in extensionNames, "minPinLength" in extensionNames, enabled && "largeBlobKey" in extensionNames, uvm = "uvm" in extensionNames),
                                    assertionExtensions = AssertionExtensions("credBlob" in extensionNames,
                                        if ("hmac-secret" in extensionNames && op == ManagerOperation.FIDO_ASSERT) extensionValue.hexBytes() else null,
                                        "largeBlobKey" in extensionNames, uvm = "uvm" in extensionNames),
                                    rpIds = if (op == ManagerOperation.FIDO_MIN_PIN) parseDisclosureRps(disclosureRps) else emptyList(),
                                    enterpriseMode = enterpriseMode.takeIf { it != 0 }, displayName = displayName)
                                pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""
                                if (ConfirmationPolicy.messages(input).isEmpty()) onSubmit(input)
                                else confirmation = PendingOperation(input)
                            } catch (_: IllegalArgumentException) { invalid = true }
                        }, enabled = !state.busy && (!op.piv || state.supportsPiv) && (op != ManagerOperation.FIDO_RESET || state.supportsFidoReset) && (!state.requiresInspection || ConfirmationPolicy.canInspect(op))) { Text(op.title.substringAfter("·").trim()) }
                        if (state.busy) OutlinedButton(onClick = onCancel) { Text("取消") }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.presenceMessage?.let { Text(stringResource(it)) }
                when (val result = state.result) {
                    is OperationResult.Completed -> {
                        ConfirmationPolicy.resultMessages(result, state.resultWire, state.operation).forEach {
                            Text(stringResource(it), style = MaterialTheme.typography.titleMedium)
                        }
                        result.value.messages.forEach { Text(stringResource(it)) }
                        if (state.operation in setOf(ManagerOperation.FIDO_REGISTER, ManagerOperation.FIDO_ASSERT,
                                ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT, ManagerOperation.PIV_CSR,
                                ManagerOperation.PIV_SIGN, ManagerOperation.PIV_ECDH, ManagerOperation.PIV_DECRYPT)) {
                            Text(stringResource(R.string.status_verification), style = MaterialTheme.typography.bodySmall)
                        }
                        result.value.rows.forEach { row -> OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.label, style = MaterialTheme.typography.labelLarge)
                                SelectionContainer { Text(row.value, style = MaterialTheme.typography.bodyMedium) }
                            }
                        } }
                        result.value.secrets?.let { outputs ->
                            OutlinedButton(onClick = { showSecretConfirmation = true }) { Text(stringResource(R.string.secret_view)) }
                            if (showSecrets) {
                                outputs.hmac?.let { Text("hmac-secret: " + it.hex()) }
                                outputs.blobKey?.let { Text("largeBlobKey: " + it.hex()) }
                                outputs.credBlob?.let { Text("credBlob: " + it.hex()) }
                            }
                        }
                        if (result.value.export != null) OutlinedButton(onClick = {
                            if (result.value.protectedExport) exportConfirmation = result.value else onExport(result.value)
                        }) { Text("导出") }
                    }
                    is OperationResult.Failed -> {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                ConfirmationPolicy.resultMessages(result, state.resultWire, state.operation, state.requiresInspection).forEach {
                                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                                }
                                result.status?.let { Text("0x%04X".format(it)) }
                            }
                        }
                    }
                    null -> Unit
                }
                if (tab == 0 && state.supportsPiv) Text(stringResource(R.string.restriction_fidoReset), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun <T> Choice(label: String, value: T, values: List<T>, title: (T) -> String, enabled: Boolean, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(title(value)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            values.forEach { option -> DropdownMenuItem(text = { Text(title(option)) }, onClick = { expanded = false; onChange(option) }) }
        }
    }
}

private fun needsPin(op: ManagerOperation): Boolean = op !in setOf(
    ManagerOperation.FIDO_INFO, ManagerOperation.FIDO_RETRIES, ManagerOperation.FIDO_SET_PIN, ManagerOperation.FIDO_RESET,
    ManagerOperation.U2F_INFO, ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT,
    ManagerOperation.PIV_INFO, ManagerOperation.PIV_SLOT, ManagerOperation.PIV_ADMIN,
    ManagerOperation.PIV_GENERATE, ManagerOperation.PIV_IMPORT, ManagerOperation.PIV_RESET,
)
