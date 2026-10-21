package cc.cardkey.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import cc.cardkey.protocol.*
import cc.cardkey.R
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.signing.SigningFileState
import cc.cardkey.signing.SignatureExportStatus
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.cardkey.protocol.piv.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.transport.UsbReaderDescriptor

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
    enrollment: EnrollmentState,
    onEnrollmentSettings: (String, String) -> Unit,
    onEnrollmentRoot: () -> Unit,
    onEnrollmentSubmit: (OperationInput) -> Unit,
    onEnrollmentIssue: (CharArray, Long) -> Unit,
    onEnrollmentAbandon: () -> Unit,
    tls: TlsQualificationState,
    onTlsSettings: (String) -> Unit,
    onTlsRoot: () -> Unit,
    onTlsSubmit: (TlsQualificationInput) -> Unit,
    signingFiles: SigningFileState,
    onSigningAction: (ManagerOperation?, Int) -> Unit,
    onSigningFile: () -> Unit,
    inventory: InventoryState,
    onInventoryRp: (Long, InventoryRp) -> Unit,
    onInventoryCredential: (Long, InventoryCredential) -> Unit,
    onInventoryFile: (Long) -> Unit,
    onInventoryAction: (ManagerOperation?) -> Unit,
    onEnrollmentSelection: (cc.cardkey.enrollment.EnrollmentSelection) -> Unit = {},
    artworkReference: CardArtworkReference? = null,
    artworkSource: CardArtworkSource = UnconfiguredCardArtworkSource,
) {
    val displayName = stringResource(R.string.app_name)
    val artworkScope = rememberCoroutineScope()
    val artworkLoader = remember(artworkSource) { CardArtworkLoader(artworkSource, artworkScope) }
    val artwork by artworkLoader.state.collectAsState()
    LaunchedEffect(artworkLoader, artworkReference, state.selectedReader?.routeKey, state.nfc) { artworkLoader.load(artworkReference) }
    DisposableEffect(artworkLoader) { onDispose { artworkLoader.clear() } }
    val feedbackRequester = remember { BringIntoViewRequester() }
    val validationRequester = remember { BringIntoViewRequester() }
    var tab by remember { mutableIntStateOf(when {
        signingFiles.restoringSave && signingFiles.restoreOperation == null -> 2
        signingFiles.restoreOperation?.piv == true || signingFiles.active -> 1
        inventory.action != null -> 0
        else -> 0
    }) }
    var objectDetail by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    val objectListScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    var operation by remember { mutableStateOf(signingFiles.restoreOperation ?: if (signingFiles.active) signingFiles.operation else inventory.action ?: ManagerOperation.FIDO_INVENTORY) }
    var pin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var adminKey by remember { mutableStateOf("") }
    var newAdminKey by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var secondary by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var extensionsExpanded by remember(operation) { mutableStateOf(false) }
    var extensionNames by remember { mutableStateOf(setOf<String>()) }
    var extensionValue by remember { mutableStateOf("") }
    var disclosureRps by remember { mutableStateOf("") }
    var enterpriseMode by remember { mutableIntStateOf(0) }
    var protection by remember { mutableIntStateOf(3) }
    var showSecretConfirmation by remember { mutableStateOf(false) }
    var showSecrets by remember { mutableStateOf(false) }
    var exportConfirmation by remember { mutableStateOf<ManagerReport?>(null) }
    var slot by remember { mutableIntStateOf(if (signingFiles.active || signingFiles.restoringSave) signingFiles.slot else 0x9a) }
    var algorithm by remember { mutableStateOf(KeyAlgorithm.P256) }
    var pinPolicy by remember { mutableStateOf(PinPolicy.ONCE) }
    var adminAlgorithm by remember { mutableStateOf(ManagementAlgorithm.TDES) }
    var newAdminAlgorithm by remember { mutableStateOf(ManagementAlgorithm.AES256) }
    var invalid by remember { mutableStateOf(false) }
    var validationFailures by remember { mutableIntStateOf(0) }
    var confirmation by remember { mutableStateOf<PendingOperation?>(null) }
    var confirmationRecognition by remember { mutableStateOf<CardRecognition?>(null) }
    var recognitionInputRetirements by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.recognition) { recognitionInputRetirements++ }
    LaunchedEffect(tab, operation, slot) {
        onSigningAction(operation.takeIf { tab < 2 }, slot)
        onInventoryAction(operation.takeIf { tab == 0 })
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, operation, inventory.credential) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                pin = ""; confirmation?.close(); confirmation = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val report = (state.result as? OperationResult.Completed)?.value
    val secretResult = report?.secrets != null || report?.protectedExport == true
    val secretForm = tab < 2 && needsSecretInput(operation)
    val secretInputReady = !state.permissionPending
    SideEffect { onSecureChange(tab == 1 || tab == 3 || secretForm || secretResult || state.lastRetry != null || state.fidoFinalAttempt) }
    LaunchedEffect(state.busy, state.result, state.requiresInspection) {
        if (!state.busy && (state.result as? OperationResult.Completed)?.value?.inventory != null) objectDetail = false
        else if (state.busy || state.result != null || state.requiresInspection) objectDetail = true
    }
    LaunchedEffect(state.result) { showSecrets = false; showSecretConfirmation = false; exportConfirmation = null }
    LaunchedEffect(clearInputs, operation, state.selectedReader?.routeKey, state.nfc, inventory.generation, state.recognition, state.permissionPending) {
        pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""; invalid = false
        confirmation?.close(); confirmation = null
        confirmationRecognition = null
        showSecrets = false; showSecretConfirmation = false; exportConfirmation = null
    }
    LaunchedEffect(operation, enabled) {
        if (operation == ManagerOperation.FIDO_REGISTER && !enabled) extensionNames = extensionNames - "largeBlobKey"
    }
    LaunchedEffect(state.supportsPiv) {
        if (!state.supportsPiv && (tab == 1 || tab == 3)) { tab = 0; operation = ManagerOperation.FIDO_INFO }
    }
    LaunchedEffect(state.supportsFidoReset) {
        if (!state.supportsFidoReset && operation == ManagerOperation.FIDO_RESET) {
            confirmation?.close(); confirmation = null
            operation = ManagerOperation.FIDO_INFO
        }
    }
    DisposableEffect(Unit) { onDispose { confirmation?.close() } }

    confirmation?.let { input ->
        val dismiss = { input.close(); confirmation = null; if (input.inventorySelection != null) onCancel() }
        AlertDialog(onDismissRequest = dismiss,
            title = { Text(input.operation.title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (input.operation == ManagerOperation.FIDO_VERIFY_CREDENTIAL ||
                    input.operation in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)) {
                    input.inventorySelection?.let { Text("网站 · ${it.rp.id}"); Text("账户 · ${it.name ?: it.displayName ?: "未命名账户"}") }
                }
                input.messages.forEach { Text(stringResource(it)) }
            } },
            confirmButton = { TextButton(onClick = {
                val recognitionCurrent = !needsSecretInput(input.operation) ||
                    confirmationRecognition == state.recognition &&
                    !state.permissionPending
                confirmation = null; confirmationRecognition = null
                if (recognitionCurrent) input.dispatch(onSubmit) else input.close()
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
    }
    if (showSecretConfirmation) AlertDialog(onDismissRequest = { showSecretConfirmation = false },
        title = { Text(stringResource(R.string.secret_view)) }, text = { Text(stringResource(R.string.confirm_secretShow)) },
        confirmButton = { TextButton(onClick = { showSecretConfirmation = false; showSecrets = true }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { showSecretConfirmation = false }) { Text("取消") } })
    exportConfirmation?.let { pending -> AlertDialog(onDismissRequest = { exportConfirmation = null },
        title = { Text("导出") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pending.inventoryGeneration != null) pending.rows.filter { it.label in setOf("RP", "凭据 ID") }
                .forEach { Text("${it.label} · ${it.value}") }
            Text(stringResource(R.string.confirm_blobExport))
        } },
        confirmButton = { TextButton(onClick = { exportConfirmation = null; onExport(pending) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { exportConfirmation = null }) { Text("取消") } }) }
    if (state.fidoFinalAttempt) AlertDialog(onDismissRequest = { onLastRetry(false) },
        title = { Text("仅剩一次 PIN 尝试") },
        text = { Text(stringResource(R.string.confirm_fidoLastRetry)) },
        confirmButton = { TextButton(onClick = { onLastRetry(true) }) { Text("验证一次") } },
        dismissButton = { TextButton(onClick = { onLastRetry(false) }) { Text("取消") } })
    state.lastRetry?.let { (current, other) ->
        AlertDialog(onDismissRequest = { onLastRetry(false) },
            title = { Text(state.operation?.title ?: "PIV") },
            text = { Text(stringResource(R.string.confirm_lastRetryErasesKeys)
                .replace("{当前验证项}", current).replace("{另一项}", other)) },
            confirmButton = { TextButton(onClick = { onLastRetry(true) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { onLastRetry(false) }) { Text("取消") } })
    }
    val topBar: @Composable () -> Unit = {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })
    }
    val navigationBar: @Composable () -> Unit = {
        NavigationBar(Modifier.testTag("workspace-navigation")) {
            listOf(2 to R.string.navigation_device, 0 to R.string.navigation_passkeys,
                1 to R.string.navigation_certificates, 4 to R.string.navigation_settings).forEach { (index, label) ->
                NavigationBarItem(selected = tab == index || index == 4 && tab == 3,
                    enabled = !state.busy && (index != 1 || state.supportsPiv), onClick = {
                    confirmation?.close(); confirmation = null
                    tab = index
                    advanced = false
                    objectDetail = true
                    if (index < 2) {
                        objectDetail = false
                        operation = if (index == 0) ManagerOperation.FIDO_INVENTORY else ManagerOperation.PIV_INFO
                    }
                }, icon = { Icon(managerDestinationIcons[when (index) { 0 -> 0; 1 -> 1; 4 -> 3; else -> 2 }], contentDescription = null) },
                    label = { Text(stringResource(label)) })
            }
        }
    }
    val readerContext: @Composable () -> Unit = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(managerDestinationIcons[2], contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(if (state.nfc) "NFC" else state.selectedReader?.device?.productName ?: "读卡器",
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when {
                            state.requiresInspection -> R.string.workspace_inspection
                            state.busy -> R.string.workspace_busy
                            !state.nfc && state.selectedReader == null -> R.string.workspace_disconnected
                            state.result != null -> R.string.workspace_lastResult
                            else -> R.string.workspace_unread
                        }), style = MaterialTheme.typography.bodySmall,
                            color = if (state.requiresInspection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.nfc && state.busy) Text(stringResource(R.string.reader_nfc), style = MaterialTheme.typography.bodySmall)
                    }
                    if (tab != 2) TextButton(onClick = {
                        if (!state.busy) {
                            confirmation?.close(); confirmation = null; tab = 2
                        }
                    }, enabled = !state.busy) { Text("读卡器") }
                }
                if (!state.supportsPiv) Text(stringResource(R.string.workspace_pivReaderRequired),
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                if (state.permissionPending) Text(stringResource(R.string.reader_usbPermission, displayName),
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        val twoPanes = workspaceUsesTwoPanes(maxWidth, LocalDensity.current.fontScale, viewportHeight)
        val detailVisible = tab >= 2 || twoPanes || objectDetail || state.busy
        BackHandler(tab < 2 && objectDetail && !twoPanes && !state.busy) {
            confirmation?.close(); confirmation = null
            pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""
            objectDetail = false
        }
        val objectPane: @Composable () -> Unit = {
            PlexoObjects(tab, slot, inventory, !state.busy, objectListScroll,
                deviceContext = { if (viewportHeight < 480.dp) { topBar(); readerContext() } },
                onOverview = {
                    confirmation?.close(); confirmation = null
                    operation = if (tab == 1) ManagerOperation.PIV_INFO else ManagerOperation.FIDO_INVENTORY
                    objectDetail = true
                },
                onSlot = {
                    confirmation?.close(); confirmation = null
                    pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""
                    slot = it; operation = ManagerOperation.PIV_SLOT; objectDetail = true
                },
                onRp = {
                    confirmation?.close(); confirmation = null
                    onInventoryRp(inventory.generation, it)
                    operation = ManagerOperation.FIDO_CREDENTIALS; objectDetail = true
                },
                onCredential = {
                    confirmation?.close(); confirmation = null
                    onInventoryCredential(inventory.generation, it)
                    operation = ManagerOperation.FIDO_UPDATE; objectDetail = true
                },
                onFidoPin = { operation = ManagerOperation.FIDO_CHANGE_PIN; objectDetail = true },
                onReadCredentials = { operation = ManagerOperation.FIDO_INVENTORY; objectDetail = true })
        }
        val compact = viewportHeight < 480.dp
        LaunchedEffect(state.busy, state.presenceMessage, state.result, state.awaitingNfc, viewportHeight.takeIf { state.busy }) {
            if (state.busy || state.result != null) {
                withFrameNanos { }
                feedbackRequester.bringIntoView()
            }
        }
        LaunchedEffect(validationFailures, invalid) {
            if (invalid) {
                withFrameNanos { }
                validationRequester.bringIntoView()
            }
        }
        Scaffold(
            topBar = { if (!compact) topBar() },
            bottomBar = {
                val actionControls: @Composable () -> Unit = {
                    if (tab < 2 && detailVisible) {
                        val op = operation
                        Row(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                if (secretForm && !secretInputReady) return@Button
                                try {
                                    require(op != ManagerOperation.FIDO_BLOB_WRITE || if (inventory.credential != null) inventory.fileSize != null else imported != null)
                                    require(op != ManagerOperation.FIDO_CREDENTIALS || inventory.rp != null)
                                    require(op != ManagerOperation.FIDO_VERIFY_CREDENTIAL || inventory.credential != null && pin.isNotEmpty())
                                    if (op in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE) && inventory.credential != null)
                                        require(inventory.blobReady && pin.isNotEmpty())
                                    require(!op.isFileSigning || signingFiles.selection != null)
                                    val input = OperationInput(op, pin.toCharArray(), newPin.toCharArray(), adminKey.hexBytes(), newAdminKey.hexBytes(),
                                        adminAlgorithm, newAdminAlgorithm, slot, algorithm, pinPolicy, text, secondary, enabled,
                                        if (op.isFileSigning || inventory.credential != null && op == ManagerOperation.FIDO_BLOB_WRITE) byteArrayOf() else imported?.copyOf() ?: byteArrayOf(),
                                        makeExtensions = MakeExtensions(if ("credProtect" in extensionNames) protection else null,
                                            if ("credBlob" in extensionNames && op == ManagerOperation.FIDO_REGISTER) extensionValue.hexBytes() else null,
                                            "hmac-secret" in extensionNames, "minPinLength" in extensionNames, enabled && "largeBlobKey" in extensionNames, uvm = "uvm" in extensionNames),
                                        assertionExtensions = AssertionExtensions("credBlob" in extensionNames,
                                            if ("hmac-secret" in extensionNames && op == ManagerOperation.FIDO_ASSERT) extensionValue.hexBytes() else null,
                                            "largeBlobKey" in extensionNames, uvm = "uvm" in extensionNames),
                                        rpIds = if (op == ManagerOperation.FIDO_MIN_PIN) parseDisclosureRps(disclosureRps) else emptyList(),
                                        enterpriseMode = enterpriseMode.takeIf { it != 0 }, displayName = displayName,
                                        signingFileGeneration = signingFiles.selection?.generation.takeIf { op.isFileSigning },
                                        inventoryGeneration = inventory.generation, inventorySelection = inventory.credential.takeIf {
                                            op in setOf(ManagerOperation.FIDO_VERIFY_CREDENTIAL, ManagerOperation.FIDO_DELETE, ManagerOperation.FIDO_UPDATE, ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE) })
                                    pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""
                                    if (ConfirmationPolicy.messages(input).isEmpty()) onSubmit(input)
                                    else {
                                        confirmationRecognition = state.recognition
                                        confirmation = PendingOperation(input)
                                    }
                                } catch (_: IllegalArgumentException) { invalid = true; validationFailures++ }
                            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("manager-run"), enabled = !state.busy && (op != ManagerOperation.FIDO_INVENTORY || pin.isNotEmpty()) && (!needsSecretInput(op) || secretInputReady) && (!op.piv || state.supportsPiv) && (op != ManagerOperation.FIDO_RESET || state.supportsFidoReset) && (!state.requiresInspection || ConfirmationPolicy.canInspect(op)) &&
                                (op != ManagerOperation.FIDO_CREDENTIALS || inventory.rp != null) &&
                                (op != ManagerOperation.FIDO_VERIFY_CREDENTIAL || inventory.credential != null && pin.isNotEmpty()) &&
                                (op !in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE) || inventory.credential == null || inventory.blobReady && pin.isNotEmpty()) &&
                                (!op.isFileSigning || (signingFiles.selection != null && !state.nfc && state.selectedReader?.wire == CardWire.APDU))) { Text(if (op == ManagerOperation.FIDO_INVENTORY) "继续" else op.title.substringAfter("·").trim()) }
                            if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                        }
                    }
                    if (tab == 3 && state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                }
                Column(modifier = if (compact) Modifier.navigationBarsPadding() else Modifier,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (compact && viewportWidth >= 600.dp && tab < 2 && detailVisible) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { actionControls() }
                            Box(Modifier.width(360.dp)) { navigationBar() }
                        }
                    } else {
                        actionControls()
                        navigationBar()
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!compact) readerContext()
                state.documentMessage?.let { Text(stringResource(it), Modifier.padding(12.dp)) }
                Row(Modifier.weight(1f).widthIn(max = 1200.dp).fillMaxWidth()) {
                    if (tab < 2 && (twoPanes || !detailVisible)) {
                        Box(if (twoPanes) Modifier.width(280.dp).fillMaxHeight() else Modifier.fillMaxSize()) { objectPane() }
                        if (twoPanes) VerticalDivider()
                    }
                    if (detailVisible) Column(Modifier.weight(1f).fillMaxHeight()
                    .testTag("workspace-detail").verticalScroll(detailScroll).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (compact) { topBar(); readerContext() }
                    if (tab < 2) {
                        if (!twoPanes) TextButton(onClick = {
                            confirmation?.close(); confirmation = null
                            pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""
                            objectDetail = false
                        }, enabled = !state.busy, modifier = Modifier.testTag("workspace-object-back")) {
                            Text(stringResource(R.string.workspace_objects))
                        }
                        PlexoSection(stringResource(R.string.workspace_selectedObject)) {
                            if (tab == 1) {
                                if (operation in setOf(ManagerOperation.PIV_INFO, ManagerOperation.PIV_CHANGE_PIN,
                                        ManagerOperation.PIV_CHANGE_PUK, ManagerOperation.PIV_UNBLOCK, ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_RESET)) {
                                    Text(stringResource(R.string.workspace_pivSecurity), style = MaterialTheme.typography.titleMedium)
                                } else {
                                    Text(stringResource(R.string.workspace_selectedSlot, "%02X".format(slot)), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.workspace_slotContext), style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                inventory.credential?.let { credential ->
                                    PlexoFactRow("RP", credential.rp.id)
                                    credential.displayName?.let { PlexoFactRow("名称", it) }
                                    PlexoFactRow("账户", credential.name ?: credential.displayName ?: "未命名账户")
                                } ?: inventory.rp?.let { PlexoFactRow("RP", it.id) }
                                    ?: Text(stringResource(R.string.workspace_authenticator))
                            }
                        }
                    }
                    if (state.busy || state.result != null || state.presenceMessage != null) {
                        Surface(Modifier.fillMaxWidth().bringIntoViewRequester(feedbackRequester), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.operation?.let { Text(it.title, style = MaterialTheme.typography.titleMedium) }
                                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (state.busy && state.nfc) Text(stringResource(R.string.reader_nfc))
                                state.presenceMessage?.let { Text(stringResource(it)) }
                                when (val result = state.result) {
                                    is OperationResult.Completed -> {
                                        Text(stringResource(R.string.workspace_receipt), style = MaterialTheme.typography.labelLarge)
                                        ConfirmationPolicy.resultMessages(result, state.resultWire, state.operation).forEach {
                                            Text(stringResource(it), style = MaterialTheme.typography.titleMedium)
                                        }
                                        result.value.messages.forEach { Text(stringResource(it)) }
                                        if (state.operation in setOf(ManagerOperation.FIDO_REGISTER, ManagerOperation.FIDO_ASSERT,
                                                ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT, ManagerOperation.PIV_CSR,
                                                ManagerOperation.PIV_SIGN, ManagerOperation.PIV_CMS_SIGN, ManagerOperation.PIV_ECDH, ManagerOperation.PIV_DECRYPT)) {
                                            Text(stringResource(R.string.status_verification), style = MaterialTheme.typography.bodySmall)
                                        }
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
                                        }, enabled = (!result.value.sshPublicKey && result.value.signatureFormat == null) || signingFiles.exportStatus != SignatureExportStatus.WRITING) { Text("导出") }
                                        if (signingFiles.exportOwner === result.value) when (signingFiles.exportStatus) {
                                            SignatureExportStatus.SAVED -> Text(stringResource(if (result.value.sshPublicKey) R.string.ssh_publicKeyExported else R.string.signing_exported))
                                            SignatureExportStatus.CANCELLED -> Text(stringResource(R.string.signing_exportCancelled))
                                            SignatureExportStatus.UNKNOWN -> Text(stringResource(R.string.signing_exportUnknown))
                                            SignatureExportStatus.WRITING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                                            null -> Unit
                                        }
                                        if (result.value.sshPublicKey) result.value.fileName?.let {
                                            PlexoFactRow(stringResource(R.string.workspace_outputFile), it)
                                        }
                                        val technicalLabels = setOf("公钥", "RP SHA-256", "用户 ID", "证明", "计数",
                                            "支持的协议", "支持的扩展", "PIN · 协议", "算法", "版本", "序列号",
                                            "credProtect", "credBlob", "用户验证方法")
                                        val technicalRows = result.value.rows.filter { it.label in technicalLabels }
                                        result.value.rows.filterNot { it.label in technicalLabels }.forEach { row ->
                                            PlexoFactRow(row.label, row.value)
                                        }
                                        var detailsExpanded by remember(result.value) { mutableStateOf(false) }
                                        if (technicalRows.isNotEmpty() || result.value.sshPublicKey && result.value.export != null) {
                                            TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth().testTag("result-details")) {
                                                Text(stringResource(R.string.workspace_details), Modifier.weight(1f))
                                                Icon(if (detailsExpanded) expandLessIcon else expandMoreIcon, contentDescription = null)
                                            }
                                            if (detailsExpanded) {
                                                if (result.value.sshPublicKey) result.value.export?.let { publicKey ->
                                                    PlexoFactRow("OpenSSH", publicKey.decodeToString())
                                                }
                                                technicalRows.forEach { row ->
                                                    HorizontalDivider()
                                                    PlexoFactRow(row.label, row.value)
                                                }
                                            }
                                        }
                                    }
                                    is OperationResult.Failed -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            ConfirmationPolicy.resultMessages(result, state.resultWire, state.operation, state.requiresInspection).forEach {
                                                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                                            }
                                            result.status?.let { Text("0x%04X".format(it)) }
                                        }
                                    }
                                    null -> Unit
                                }
                            }
                        }
                    }
                    if (tab == 2) {
                        Text(stringResource(R.string.navigation_device), style = MaterialTheme.typography.headlineSmall)
                        CardArtworkHero(artwork) { artworkLoader.load(artworkReference) }
                        Text(stringResource(R.string.device_reader), style = MaterialTheme.typography.titleMedium)
                        if (state.readers.isEmpty()) Text(stringResource(R.string.reader_empty))
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReaderOption("NFC", null, state.nfc, !state.busy && !state.permissionPending) { onReader(null) }
                            state.readers.forEach { reader ->
                                ReaderOption(reader.device.productName ?: "USB",
                                    "USB · %04x:%04x".format(reader.device.vendorId, reader.device.productId),
                                    !state.nfc && state.selectedReader?.routeKey == reader.routeKey,
                                    !state.busy && !state.permissionPending) { onReader(reader) }
                            }
                        }
                        OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新读卡器") }
                    } else if (tab < 2) {
                        Text(stringResource(if (tab == 0) R.string.navigation_passkeys else R.string.navigation_certificates), style = MaterialTheme.typography.headlineSmall)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                val op = operation
                                if (op.piv && op !in setOf(ManagerOperation.PIV_INFO, ManagerOperation.PIV_CHANGE_PIN, ManagerOperation.PIV_CHANGE_PUK,
                                        ManagerOperation.PIV_UNBLOCK, ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_RESET)) {
                                    Text(stringResource(R.string.workspace_slotContext), style = MaterialTheme.typography.bodySmall)
                                    Choice("槽位", slot, PivClient.slots, { "%02X".format(it) }, !state.busy) { slot = it }
                                }
                                val tasks = if (advanced) ManagerOperation.entries.filter { it.piv == (tab == 1) && it !in setOf(ManagerOperation.PIV_ENROLL_CSR, ManagerOperation.PIV_ENROLL_IMPORT, ManagerOperation.FIDO_RPS, ManagerOperation.FIDO_CREDENTIALS) && (it != ManagerOperation.FIDO_RESET || state.supportsFidoReset) }
                                else if (tab == 1) listOf(ManagerOperation.PIV_INFO, ManagerOperation.PIV_SLOT, ManagerOperation.PIV_SSH_EXPORT, ManagerOperation.PIV_CMS_SIGN, ManagerOperation.PIV_CHANGE_PIN, ManagerOperation.PIV_UNBLOCK)
                                else listOf(ManagerOperation.FIDO_INVENTORY, ManagerOperation.FIDO_SET_PIN, ManagerOperation.FIDO_CHANGE_PIN) +
                                    if (inventory.credential != null) listOf(ManagerOperation.FIDO_UPDATE, ManagerOperation.FIDO_DELETE) else emptyList()
                                OperationChoice(operation, tasks, !state.busy) { operation = it }
                                Text(op.title, style = MaterialTheme.typography.titleLarge)
                                if (state.permissionPending) Text(stringResource(R.string.reader_usbPermission, displayName))
                                if (advanced && op in setOf(ManagerOperation.FIDO_INFO, ManagerOperation.FIDO_CAPACITY, ManagerOperation.FIDO_RPS,
                                        ManagerOperation.FIDO_CREDENTIALS, ManagerOperation.FIDO_DELETE, ManagerOperation.FIDO_UPDATE,
                                        ManagerOperation.FIDO_VERIFY_CREDENTIAL, ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE) && inventory.rps.isEmpty())
                                    Text(stringResource(R.string.workspace_credentialsUnread), style = MaterialTheme.typography.bodySmall)
                                if (op.isFileSigning) {
                                    if (op == ManagerOperation.PIV_CMS_SIGN) Text(stringResource(R.string.signing_cmsRequirements), style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        pin = ""; confirmation?.close(); confirmation = null; onSigningFile()
                                    }, enabled = !state.busy && !state.nfc && state.selectedReader?.wire == CardWire.APDU &&
                                        signingFiles.exportStatus != SignatureExportStatus.WRITING,
                                        modifier = Modifier.testTag("signing-file-picker")) { Text(stringResource(R.string.signing_chooseFile)) }
                                    Text(stringResource(R.string.signing_limit), style = MaterialTheme.typography.bodySmall)
                                    signingFiles.selection?.let { file -> Text(stringResource(R.string.signing_selectedFile, file.name, file.size)) }
                                }
                                if (secretInputReady && needsPin(op)) SecretField(when { op == ManagerOperation.PIV_CHANGE_PUK || op == ManagerOperation.PIV_UNBLOCK -> "当前 PUK"; op.piv -> "当前 PIV PIN"; else -> "FIDO PIN" }, pin, !state.busy, viewportHeight) { pin = it }
                                if (secretInputReady && !op.piv && needsPin(op)) Text("PIN 输入错误会减少重试次数。", style = MaterialTheme.typography.bodySmall)
                                if (secretInputReady && op.piv && needsPin(op)) Text("6–8 位数字", style = MaterialTheme.typography.bodySmall)
                                if (op in setOf(ManagerOperation.FIDO_SET_PIN, ManagerOperation.FIDO_CHANGE_PIN, ManagerOperation.PIV_CHANGE_PIN,
                                        ManagerOperation.PIV_CHANGE_PUK, ManagerOperation.PIV_UNBLOCK)) {
                                    if (secretInputReady) SecretField(when { op == ManagerOperation.PIV_CHANGE_PUK -> "新 PUK"; op.piv -> "新 PIV PIN"; else -> "新 FIDO PIN" }, newPin, !state.busy, viewportHeight) { newPin = it }
                                }
                                if (op in setOf(ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_GENERATE, ManagerOperation.PIV_IMPORT)) {
                                    Choice("管理密钥 · 算法", adminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { adminAlgorithm = it }
                                    if (secretInputReady) SecretField("管理密钥 · HEX", adminKey, !state.busy, viewportHeight) { adminKey = it }
                                }
                                if (op in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)) {
                                    Text(stringResource(R.string.blob_help))
                                    if (op == ManagerOperation.FIDO_BLOB_WRITE) {
                                        OutlinedButton(onClick = { if (inventory.credential != null) onInventoryFile(inventory.generation) else onImport() }, enabled = !state.busy) { Text("导入") }
                                        (if (inventory.credential != null) inventory.fileSize else imported?.size)?.let {
                                            Text("$it 字节", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                if (op == ManagerOperation.PIV_ADMIN) {
                                    Choice("新管理密钥 · 算法", newAdminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { newAdminAlgorithm = it }
                                    if (secretInputReady) SecretField("新管理密钥 · HEX", newAdminKey, !state.busy, viewportHeight) { newAdminKey = it }
                                }
                                if (op == ManagerOperation.PIV_GENERATE) {
                                    Choice("算法", algorithm, KeyAlgorithm.entries, { it.name }, !state.busy) { algorithm = it }
                                    Choice("PIN 验证策略", pinPolicy, PinPolicy.entries, { it.name }, !state.busy) { pinPolicy = it }
                                }
                                if (op in setOf(ManagerOperation.FIDO_MIN_PIN, ManagerOperation.PIV_CSR)) {
                                    val label = when (op) {
                                        ManagerOperation.FIDO_MIN_PIN -> "PIN · 最短长度"
                                        ManagerOperation.PIV_CSR -> "Subject · CN=${stringResource(R.string.app_name)}"
                                        else -> "主题名称"
                                    }
                                    OutlinedTextField(text, { text = it }, label = { Text(label) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
                                }
                                if (op == ManagerOperation.FIDO_MIN_PIN) {
                                    OutlinedTextField(disclosureRps, { disclosureRps = it }, label = { Text(stringResource(R.string.minPin_rps)) },
                                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
                                    Text(stringResource(R.string.minPin_rpsHelp))
                                }
                                if (op == ManagerOperation.FIDO_UPDATE) {
                                    Text(stringResource(R.string.user_updateHelp))
                                    OutlinedTextField(secondary, { secondary = it }, label = { Text("新用户名") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
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
                                if (op == ManagerOperation.FIDO_VERIFY_CREDENTIAL) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("largeBlobKey", Modifier.weight(1f))
                                        Checkbox("largeBlobKey" in extensionNames, { checked -> extensionNames = if (checked) extensionNames + "largeBlobKey" else extensionNames - "largeBlobKey" }, enabled = !state.busy)
                                    }
                                }
                                if (op == ManagerOperation.FIDO_REGISTER || op == ManagerOperation.FIDO_ASSERT) {
                                    if (op == ManagerOperation.FIDO_REGISTER) Choice(stringResource(R.string.enterprise_mode), enterpriseMode,
                                        listOf(0, 1, 2), { when (it) { 1 -> "厂商预设（1）"; 2 -> "平台管理（2）"; else -> "不请求" } }, !state.busy) { enterpriseMode = it }
                                    HorizontalDivider()
                                    TextButton(onClick = { extensionsExpanded = !extensionsExpanded }, modifier = Modifier.fillMaxWidth()) {
                                        Text("扩展", Modifier.weight(1f))
                                        Icon(if (extensionsExpanded) expandLessIcon else expandMoreIcon, contentDescription = null)
                                    }
                                    if (extensionsExpanded) {
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
                                            if (secretInputReady) SecretField(if (op == ManagerOperation.FIDO_REGISTER) "credBlob · HEX" else "hmac-secret · salt · HEX", extensionValue, !state.busy, viewportHeight) { extensionValue = it }
                                        }
                                    }
                                }
                                if (op == ManagerOperation.PIV_IMPORT) {
                                    OutlinedButton(onClick = onImport, enabled = !state.busy) { Text("导入证书") }
                                    imported?.let { Text("${it.size} bytes", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (invalid) Text(stringResource(R.string.error_invalidInput), color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.bringIntoViewRequester(validationRequester))
                            }
                        }
                    }
                    if (tab == 4) {
                        Text(stringResource(R.string.navigation_settings), style = MaterialTheme.typography.headlineSmall)
                        PlexoSection(stringResource(R.string.settings_services)) {
                            OutlinedButton(onClick = onProviderSettings, enabled = !state.busy) { Text(stringResource(R.string.settings_provider)) }
                            OutlinedButton(onClick = { tab = 3 }, enabled = !state.busy && state.supportsPiv) { Text(stringResource(R.string.settings_enrollment)) }
                            OutlinedButton(onClick = { advanced = true; tab = 0; operation = ManagerOperation.FIDO_INFO }, enabled = !state.busy) { Text("高级 FIDO 设置与诊断") }
                            OutlinedButton(onClick = { advanced = true; tab = 1; operation = ManagerOperation.PIV_INFO }, enabled = !state.busy && state.supportsPiv) { Text("高级 PIV 管理与诊断") }
                        }
                    }
                    if (tab == 3) key(state.selectedReader?.routeKey) { EnrollmentPanel(enrollment, state.busy,
                        !state.nfc && state.selectedReader?.wire == CardWire.APDU && !state.requiresInspection,
                        clearInputs + recognitionInputRetirements, onEnrollmentSettings, onEnrollmentRoot, { input ->
                            pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""
                            onEnrollmentSubmit(input)
                        }, { token, revision ->
                            pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""
                            onEnrollmentIssue(token, revision)
                        }, onExport, onCancel, onEnrollmentAbandon, onEnrollmentSelection, viewportHeight) }
                    if (tab == 3 && enrollment.stage == cc.cardkey.enrollment.EnrollmentStage.IMPORTED &&
                        enrollment.selection == cc.cardkey.enrollment.EnrollmentSelection()) {
                        key(state.selectedReader?.routeKey, enrollment.bundle) {
                            TlsQualificationPanel(tls, enrollment, state.busy,
                                !state.nfc && state.selectedReader?.wire == CardWire.APDU && !state.requiresInspection,
                                clearInputs + recognitionInputRetirements, onTlsSettings, onTlsRoot, { input ->
                                    pin = ""; newPin = ""; adminKey = ""; newAdminKey = ""; extensionValue = ""
                                    onTlsSubmit(input)
                                }, onCancel, viewportHeight)
                        }
                    }
                    if (advanced && tab == 0 && state.supportsPiv) Text(stringResource(R.string.restriction_fidoReset), style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, enabled: Boolean, viewportHeight: Dp, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(label: String, value: T, values: List<T>, title: (T) -> String, enabled: Boolean, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(title(value), {}, readOnly = true, label = { Text(label) }, enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled))
        ExposedDropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            values.forEach { option -> DropdownMenuItem(text = { Text(title(option)) }, onClick = { expanded = false; onChange(option) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OperationChoice(value: ManagerOperation, values: List<ManagerOperation>, enabled: Boolean, onChange: (ManagerOperation) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text("操作", style = MaterialTheme.typography.labelLarge)
            Text(value.title, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(expandMoreIcon, contentDescription = null)
    }
    if (expanded && enabled) AlertDialog(onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.workspace_tasks)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_selectionOnly))
                workspaceOperationGroups(values).forEachIndexed { index, actions ->
                    if (actions.isNotEmpty()) {
                        Text(stringResource(if (index == 1) R.string.workspace_advanced else R.string.workspace_common),
                            style = MaterialTheme.typography.titleSmall)
                        actions.forEach { option ->
                            TextButton(onClick = { expanded = false; onChange(option) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task-${option.name}")) {
                                Text(option.title, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } })
}

@Composable
private fun ReaderOption(title: String, detail: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(managerDestinationIcons[2], contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

private val managerDestinationIcons = listOf(
    ImageVector.Builder("Key", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 14f); arcTo(3f, 3f, 0f, true, true, 7f, 8f); arcTo(3f, 3f, 0f, false, true, 7f, 14f); close()
            moveTo(7f, 6f); arcTo(5f, 5f, 0f, true, false, 11.58f, 13f)
            lineTo(15f, 13f); lineTo(15f, 16f); lineTo(18f, 16f); lineTo(18f, 13f); lineTo(21f, 13f)
            lineTo(21f, 9f); lineTo(11.58f, 9f); arcTo(5f, 5f, 0f, false, false, 7f, 6f); close()
        }
    }.build(),
    ImageVector.Builder("IdentityCard", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
            moveTo(5f, 6f); lineTo(5f, 18f); lineTo(19f, 18f); lineTo(19f, 6f); close()
            moveTo(8f, 12f); arcTo(2f, 2f, 0f, true, true, 8f, 8f); arcTo(2f, 2f, 0f, false, true, 8f, 12f); close()
            moveTo(5.5f, 16f); lineTo(10.5f, 16f); lineTo(10.5f, 14f); lineTo(5.5f, 14f); close()
            moveTo(13f, 8f); lineTo(17f, 8f); lineTo(17f, 10f); lineTo(13f, 10f); close()
            moveTo(13f, 13f); lineTo(17f, 13f); lineTo(17f, 15f); lineTo(13f, 15f); close()
        }
    }.build(),
    ImageVector.Builder("Reader", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 3f); lineTo(20f, 3f); lineTo(20f, 21f); lineTo(4f, 21f); close()
            moveTo(6f, 5f); lineTo(6f, 19f); lineTo(18f, 19f); lineTo(18f, 5f); close()
            moveTo(8f, 7f); lineTo(16f, 7f); lineTo(16f, 9f); lineTo(8f, 9f); close()
            moveTo(8f, 12f); lineTo(12f, 12f); lineTo(12f, 16f); lineTo(8f, 16f); close()
        }
    }.build(),
    ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 7f); lineTo(4f, 7f); close()
            moveTo(4f, 11f); lineTo(20f, 11f); lineTo(20f, 13f); lineTo(4f, 13f); close()
            moveTo(4f, 17f); lineTo(20f, 17f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(8f, 3f); lineTo(10f, 3f); lineTo(10f, 9f); lineTo(8f, 9f); close()
            moveTo(14f, 9f); lineTo(16f, 9f); lineTo(16f, 15f); lineTo(14f, 15f); close()
            moveTo(8f, 15f); lineTo(10f, 15f); lineTo(10f, 21f); lineTo(8f, 21f); close()
        }
    }.build(),
)

private val expandMoreIcon = ImageVector.Builder("ExpandMore", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(7f, 9f); lineTo(12f, 14f); lineTo(17f, 9f); lineTo(18.4f, 10.4f)
        lineTo(12f, 16.8f); lineTo(5.6f, 10.4f); close()
    }
}.build()

private val expandLessIcon = ImageVector.Builder("ExpandLess", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(7f, 15f); lineTo(12f, 10f); lineTo(17f, 15f); lineTo(18.4f, 13.6f)
        lineTo(12f, 7.2f); lineTo(5.6f, 13.6f); close()
    }
}.build()

private fun needsPin(op: ManagerOperation): Boolean = op !in setOf(
    ManagerOperation.FIDO_INFO, ManagerOperation.FIDO_RETRIES, ManagerOperation.FIDO_SET_PIN, ManagerOperation.FIDO_RESET,
    ManagerOperation.U2F_INFO, ManagerOperation.U2F_REGISTER, ManagerOperation.U2F_ASSERT,
    ManagerOperation.PIV_INFO, ManagerOperation.PIV_SLOT, ManagerOperation.PIV_SSH_EXPORT, ManagerOperation.PIV_ADMIN,
    ManagerOperation.PIV_GENERATE, ManagerOperation.PIV_IMPORT, ManagerOperation.PIV_RESET,
)

private fun needsSecretInput(op: ManagerOperation): Boolean = needsPin(op) || op in setOf(
    ManagerOperation.FIDO_SET_PIN, ManagerOperation.PIV_ADMIN, ManagerOperation.PIV_GENERATE, ManagerOperation.PIV_IMPORT,
)
