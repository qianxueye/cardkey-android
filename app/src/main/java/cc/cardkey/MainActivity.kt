package cc.cardkey

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.credentials.CredentialManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import cc.cardkey.ui.*
import cc.cardkey.protectedpiv.ProtectedPivDocument
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.OperationResult
import cc.cardkey.transport.UsbReaderDescriptor
import cc.cardkey.transport.UsbAuthenticatorReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val model: ManagerViewModel by viewModels()
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    private var showProviderSettings by mutableStateOf(false)
    private val providerSettings = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    private var clearInputs by mutableIntStateOf(0)
    private var imported by mutableStateOf<ByteArray?>(null)
    private var export: ManagerReport? = null
    private var requestedReader: UsbReaderDescriptor? = null
    private var lastNfcTag: android.nfc.Tag? = null
    private val permissionAction get() = "$packageName.USB_PERMISSION"

    private val inventoryExporters = mutableMapOf<String, ActivityResultLauncher<String>>()
    private fun inventoryExporter(id: String): ActivityResultLauncher<String> {
        inventoryExporters[id]?.let { return it }
        val launcher = activityResultRegistry.register("inventory-blob-export:$id", ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            model.receiveInventoryExport(id, uri)
            inventoryExporters.remove(id)?.unregister()
        }
        inventoryExporters[id] = launcher
        if (model.inventoryExportId != id) { inventoryExporters.remove(id); launcher.unregister() }
        return launcher
    }
    private val inventoryFilePickers = mutableMapOf<String, ActivityResultLauncher<Array<String>>>()
    private fun inventoryFilePicker(id: String): ActivityResultLauncher<Array<String>> {
        inventoryFilePickers[id]?.let { return it }
        val launcher = activityResultRegistry.register("inventory-blob-file:$id", ActivityResultContracts.OpenDocument()) { uri ->
            model.receiveInventoryFile(id, uri)
            inventoryFilePickers.remove(id)?.unregister()
        }
        inventoryFilePickers[id] = launcher
        if (model.inventory.fileRequestId != id) { inventoryFilePickers.remove(id); launcher.unregister() }
        return launcher
    }
    private fun chooseInventoryFile(generation: Long) {
        model.requestInventoryFile(generation)?.let { inventoryFilePicker(it).launch(arrayOf("*/*")) }
    }

    private val signingFilePickers = mutableMapOf<String, ActivityResultLauncher<Array<String>>>()
    private val signatureExporters = mutableMapOf<String, ActivityResultLauncher<String>>()
    private fun signingFilePicker(id: String): ActivityResultLauncher<Array<String>> {
        signingFilePickers[id]?.let { return it }
        val launcher = activityResultRegistry.register("piv-signing-file:$id", ActivityResultContracts.OpenDocument()) { uri ->
            model.receiveSigningFile(id, uri)
            signingFilePickers.remove(id)?.unregister()
        }
        signingFilePickers[id] = launcher
        if (model.signingFileRequestId != id) { signingFilePickers.remove(id); launcher.unregister() }
        return launcher
    }
    private fun signatureExporter(id: String): ActivityResultLauncher<String> {
        signatureExporters[id]?.let { return it }
        val launcher = activityResultRegistry.register("piv-signature-save:$id", ActivityResultContracts.CreateDocument(requireNotNull(model.signatureSaveMimeType))) { uri ->
            model.receiveSignatureSave(id, uri)
            signatureExporters.remove(id)?.unregister()
        }
        signatureExporters[id] = launcher
        if (model.signatureSaveRequestId != id) { signatureExporters.remove(id); launcher.unregister() }
        return launcher
    }
    private fun chooseSigningFile() {
        if (model.requestSigningFile()) signingFilePicker(requireNotNull(model.signingFileRequestId)).launch(arrayOf("*/*"))
    }
    private val protectedPivPickers = mutableMapOf<String, ActivityResultLauncher<Array<String>>>()
    private fun protectedPivPicker(id: String): ActivityResultLauncher<Array<String>> {
        protectedPivPickers[id]?.let { return it }
        val launcher = activityResultRegistry.register("protected-piv-document:$id", ActivityResultContracts.OpenDocument()) { uri ->
            model.receiveProtectedPivDocument(id, uri)
            protectedPivPickers.remove(id)?.unregister()
        }
        protectedPivPickers[id] = launcher
        if (model.protectedPiv.value.documentId != id) { protectedPivPickers.remove(id); launcher.unregister() }
        return launcher
    }
    private fun chooseProtectedPivDocument(kind: ProtectedPivDocument) {
        model.requestProtectedPivDocument(kind)?.let { protectedPivPicker(it).launch(arrayOf("*/*")) }
    }
    private val tlsRootImporter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> model.receiveTlsRoot(uri) }
    private fun chooseTlsRoot() { model.requestTlsRoot(); tlsRootImporter.launch(arrayOf("*/*")) }
    private val rootImporter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> model.receiveEnrollmentRoot(uri) }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val generation = model.beginDocument()
        imported?.fill(0)
        imported = null
        if (uri == null) model.documentResult(generation, R.string.document_cancelled)
        else lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val raw = cc.cardkey.ui.DocumentIo.read { contentResolver.openInputStream(uri) }
                    if (raw.take(27).toByteArray().toString(Charsets.US_ASCII).startsWith("-----BEGIN CERTIFICATE-----")) {
                        try {
                            val text = raw.toString(Charsets.US_ASCII)
                            require(text.trim().endsWith("-----END CERTIFICATE-----"))
                            Base64.getMimeDecoder().decode(text.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----"))
                        } finally { raw.fill(0) }
                    } else raw
                } catch (_: Exception) { null }
            }
            if (!model.ownsDocument(generation)) bytes?.fill(0)
            else {
                imported = bytes
                if (bytes == null) model.documentResult(generation, R.string.document_readFailed)
            }
        }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val report = export
        export = null
        val generation = model.beginDocument()
        if (uri == null) {
            if (report?.protectedExport == true) report.export?.fill(0)
            model.documentResult(generation, R.string.document_cancelled)
        } else if (report?.export != null && model.currentEnrollmentExport(report)) {
            val bytes = report.export.copyOf()
            if (report.protectedExport) report.export.fill(0)
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try { cc.cardkey.ui.DocumentIo.write(bytes) { contentResolver.openOutputStream(uri, "wt") } }
                    finally { bytes.fill(0) }
                }
                model.documentResult(generation, if (saved) R.string.document_saved else R.string.document_unknown)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let { device ->
                    (model.state.value.readers + listOfNotNull(model.state.value.selectedReader, requestedReader))
                        .filter { it.device.deviceName == device.deviceName }.forEach(UsbAuthenticatorReader::invalidate)
                    if (requestedReader?.device?.deviceName == device.deviceName) {
                        requestedReader = null
                        model.permissionPending(false)
                    }
                }
            }
            if (intent.action == permissionAction) {
                requestedReader?.takeIf { intent.identifier == it.routeKey }?.let { reader ->
                    if (UsbAuthenticatorReader.isCurrent(usb, reader) && usb.hasPermission(reader.device)) model.selectReader(reader)
                    else model.documentResult(model.beginDocument(), R.string.reader_permissionDenied)
                    requestedReader = null
                    model.permissionPending(false)
                }
            }
            if (intent.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                model.refreshNfc()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) enableNfcReader()
            }
            model.refreshReaders()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        model.refreshReaders()
        model.inventoryExportId?.let(::inventoryExporter)
        model.inventory.fileRequestId?.let(::inventoryFilePicker)
        model.signingFileRequestId?.let(::signingFilePicker)
        model.signatureSaveRequestId?.let(::signatureExporter)
        model.protectedPiv.value.documentId?.let(::protectedPivPicker)
        setContent {
            val state by model.state.collectAsState()
            val enrollment by model.enrollment.state.collectAsState()
            val tls by model.tls.collectAsState()
            val signingFiles by model.signingFiles.state.collectAsState()
            val inventory by model.inventory.state.collectAsState()
            val protectedPiv by model.protectedPiv.collectAsState()
            LaunchedEffect(state.result) {
                val failure = state.result as? OperationResult.Failed
                if (state.nfc && failure?.code == FailureCode.PHYSICAL_ACTION_REQUIRED) {
                    lastNfcTag?.let { tag -> nfc?.ignore(tag, 250,
                        { lastNfcTag = null }, Handler(mainLooper)) }
                }
            }
            CardKeyTheme {
                ManagerScreen(state, clearInputs, ::selectReader, model::refreshReaders, model::submit, model::cancel,
                    { importer.launch(arrayOf("*/*")) }, imported,
                    { report ->
                        if (report.signatureFormat != null || report.sshPublicKey) {
                            if (model.requestSignatureSave(report)) signatureExporter(requireNotNull(model.signatureSaveRequestId)).launch(requireNotNull(report.fileName))
                        } else if (report.inventoryGeneration != null) {
                            model.requestInventoryExport(report)?.let { inventoryExporter(it).launch("credential-blob.bin") }
                        } else if (model.currentEnrollmentExport(report)) {
                            export = if (report.protectedExport) report.copy(export = report.export?.copyOf()) else report
                            exporter.launch(report.fileName ?: "cardkey.der")
                        }
                    }, model::approveLastRetry,
                    { secure -> if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                    { showProviderSettings = true }, enrollment, model::enrollmentSettings,
                    { model.requestEnrollmentRoot(); rootImporter.launch(arrayOf("*/*")) },
                    model::submitEnrollment, model::issueEnrollment, model::abandonEnrollment,
                    tls, model::tlsSettings, ::chooseTlsRoot, model::submitTls,
                    signingFiles, model::fileSigningAction, ::chooseSigningFile,
                    inventory, model::selectInventoryRp, model::selectInventoryCredential, ::chooseInventoryFile, model::inventoryAction, model::enrollmentSelection,
                    protectedPiv, ::chooseProtectedPivDocument, model::clearProtectedPiv, model::recognizeProtectedPiv, model::executeProtectedPiv)
                if (showProviderSettings) AlertDialog(
                    onDismissRequest = { showProviderSettings = false },
                    title = { Text("凭据提供者") },
                    text = { Text(stringResource(R.string.provider_settings, stringResource(R.string.app_name))) },
                    confirmButton = { TextButton(onClick = {
                        showProviderSettings = false
                        providerSettings.launch(IntentSenderRequest.Builder(
                            CredentialManager.create(this@MainActivity).createSettingsPendingIntent()).build())
                    }) { Text("打开设置") } },
                    dismissButton = { TextButton(onClick = { showProviderSettings = false }) { Text("取消") } },
                )
            }
        }
    }

    private fun selectReader(reader: UsbReaderDescriptor?) {
        if (reader != null && !UsbAuthenticatorReader.isCurrent(usb, reader)) { model.refreshReaders(); return }
        if (reader == null || usb.hasPermission(reader.device)) model.selectReader(reader)
        else {
            requestedReader = reader
            model.permissionPending(true)
            val intent = Intent(permissionAction).setPackage(packageName).setIdentifier(reader.routeKey)
            usb.requestPermission(reader.device, PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcReader()
        model.refreshReaders()
        model.resumeProtectedPiv()
    }

    private fun enableNfcReader() {
        model.refreshNfc()
        if (nfc?.isEnabled == true) nfc?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        nfc?.disableReaderMode(this)
        model.pauseForActivity(isChangingConfigurations)
        clearInputs++
        super.onPause()
    }

    override fun onTagDiscovered(tag: android.nfc.Tag) {
        val owner = model.pendingNfcOwner ?: return
        runOnUiThread { if (model.onTag(tag, owner)) lastNfcTag = tag }
    }
    override fun onDestroy() {
        inventoryExporters.values.toList().forEach { it.unregister() }
        inventoryExporters.clear()
        inventoryFilePickers.values.toList().forEach { it.unregister() }
        inventoryFilePickers.clear()
        signingFilePickers.values.toList().forEach { it.unregister() }
        signingFilePickers.clear()
        signatureExporters.values.toList().forEach { it.unregister() }
        signatureExporters.clear()
        protectedPivPickers.values.toList().forEach { it.unregister() }
        protectedPivPickers.clear()
        if (export?.protectedExport == true) export?.export?.fill(0)
        export = null
        unregisterReceiver(receiver); super.onDestroy()
    }
}
