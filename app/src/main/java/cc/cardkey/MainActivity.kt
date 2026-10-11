package cc.cardkey

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.CredentialManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import cc.cardkey.ui.*
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.OperationResult
import cc.cardkey.transport.UsbReaderDescriptor
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

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            imported = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(65537)
                        var length = 0
                        while (length < buffer.size) {
                            val read = input.read(buffer, length, buffer.size - length)
                            if (read < 0) break
                            if (read == 0) throw java.io.IOException()
                            length += read
                        }
                        val bytes = buffer.copyOf(length)
                        require(bytes.size in 1..65536)
                        if (bytes.take(27).toByteArray().toString(Charsets.US_ASCII).startsWith("-----BEGIN CERTIFICATE-----")) {
                            val text = bytes.toString(Charsets.US_ASCII)
                            require(text.trim().endsWith("-----END CERTIFICATE-----"))
                            Base64.getMimeDecoder().decode(text.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----"))
                        } else bytes
                    }
                }.getOrNull()
            }
        }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val report = export
        export = null
        if (uri != null && report?.export != null) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(report.export) } }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                requestedReader?.let { reader -> if (usb.hasPermission(reader.device)) model.selectReader(reader) }
                requestedReader = null
                model.permissionPending(false)
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
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        model.refreshReaders()
        setContent {
            val state by model.state.collectAsState()
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
                    { report -> export = report; exporter.launch(report.fileName ?: "cardkey.der") }, model::approveLastRetry,
                    { secure -> if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                    { showProviderSettings = true })
                if (showProviderSettings) AlertDialog(
                    onDismissRequest = { showProviderSettings = false },
                    title = { Text("凭据提供者") },
                    text = { Text(stringResource(R.string.provider_settings, stringResource(R.string.app_name))) },
                    confirmButton = { TextButton(onClick = {
                        showProviderSettings = false
                        providerSettings.launch(IntentSenderRequest.Builder(
                            CredentialManager.create(this@MainActivity).createSettingsPendingIntent()).build())
                    }) { Text("启用") } },
                    dismissButton = { TextButton(onClick = { showProviderSettings = false }) { Text("取消") } },
                )
            }
        }
    }

    private fun selectReader(reader: UsbReaderDescriptor?) {
        if (reader == null || usb.hasPermission(reader.device)) model.selectReader(reader)
        else {
            requestedReader = reader
            model.permissionPending(true)
            val intent = Intent(permissionAction).setPackage(packageName)
            usb.requestPermission(reader.device, PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    override fun onResume() {
        super.onResume()
        nfc?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        model.refreshReaders()
    }

    override fun onPause() {
        nfc?.disableReaderMode(this)
        model.cancel()
        clearInputs++
        super.onPause()
    }

    override fun onTagDiscovered(tag: android.nfc.Tag) {
        runOnUiThread { lastNfcTag = tag; model.onTag(tag) }
    }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
}
