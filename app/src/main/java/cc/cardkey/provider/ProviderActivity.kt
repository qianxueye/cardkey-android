package cc.cardkey.provider

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import cc.cardkey.R
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.Assertion
import cc.cardkey.transport.NfcCardChannel
import cc.cardkey.transport.UsbCcidReader
import cc.cardkey.transport.UsbReaderDescriptor
import cc.cardkey.ui.CardKeyTheme
import cc.cardkey.ui.ConfirmationPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProviderActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val runner = ProviderOperationRunner()
    private var extracted: ExtractedRequest? = null
    private var create: ParsedCreate? = null
    private var get: ParsedGet? = null
    private var clientData: ClientData? = null
    private var loading by mutableStateOf(true)
    private var rejected by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var awaitingNfc by mutableStateOf(false)
    private var failure by mutableStateOf<OperationResult.Failed?>(null)
    private var assertions by mutableStateOf<List<Assertion>>(emptyList())
    private var readers by mutableStateOf<List<UsbReaderDescriptor>>(emptyList())
    private var selected by mutableStateOf<UsbReaderDescriptor?>(null)
    private var permissionPending by mutableStateOf(false)
    private var pendingReader: UsbReaderDescriptor? = null
    private var pin by mutableStateOf("")
    private var pendingPin: CharArray? = null
    private var requestCancelled = false
    private var presenceWatch: Job? = null
    private var completed = false
    private var active = false
    private var lastTag: Tag? = null
    private var receiverRegistered = false
    private val permissionAction get() = "$packageName.PROVIDER_USB_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                pendingReader?.let { if (usb.hasPermission(it.device)) selected = it }
                pendingReader = null
                permissionPending = false
            }
            refreshReaders()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (savedInstanceState != null) { finishException(cancelled = true); return }
        try { extracted = ProviderRequests.extract(intent) }
        catch (_: Exception) { finishException(cancelled = false); return }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelRequest()
        })
        setContent { CardKeyTheme { ProviderContent() } }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = requireNotNull(extracted)
                    if (request.kind == ProviderRequestKind.CREATE) create = RequestParser.parseCreate(request.json)
                    else get = RequestParser.parseGet(request.json)
                    val rp = create?.rpId ?: requireNotNull(get).rpId
                    val challenge = create?.challenge ?: requireNotNull(get).challenge
                    clientData = CallerVerifier().verify(request.caller, rp, challenge,
                        if (create != null) "webauthn.create" else "webauthn.get", request.hash)
                }
            } catch (_: Exception) { rejected = true }
            loading = false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ProviderContent() {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
            Column(Modifier.padding(padding).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("凭据提供者", style = MaterialTheme.typography.headlineSmall)
                if (loading) CircularProgressIndicator()
                else if (rejected) {
                    Text(stringResource(R.string.provider_request_rejected))
                    Button(onClick = { finishException(false) }) { Text("关闭") }
                } else if (failure != null) {
                    ConfirmationPolicy.resultMessages(requireNotNull(failure)).forEach { Text(stringResource(it)) }
                    Button(onClick = { finishException(requestCancelled || failure?.code == FailureCode.CANCELLED) }) { Text("关闭") }
                } else if (assertions.isNotEmpty()) {
                    Text(stringResource(R.string.provider_choose_account, requireNotNull(get).rpId))
                    assertions.forEachIndexed { index, assertion ->
                        OutlinedButton(onClick = { completeAssertion(assertion) }, modifier = Modifier.fillMaxWidth()) {
                            Text(assertion.user?.displayName ?: assertion.user?.name ?: "${index + 1}")
                        }
                    }
                } else {
                    val rp = create?.rpId ?: requireNotNull(get).rpId
                    val caller = requireNotNull(extracted).caller.packageName
                    Text(stringResource(if (create != null) R.string.provider_create_confirmation else R.string.provider_get_confirmation, rp, caller))
                    create?.let {
                        Text(stringResource(if (it.attestation == AttestationPreference.DIRECT)
                            R.string.provider_attestation_direct else R.string.provider_attestation_none))
                        Text(it.user.displayName ?: it.user.name ?: "")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected == null, { if (!busy) selected = null }, label = { Text("NFC") }, enabled = !busy)
                        readers.forEach { reader ->
                            FilterChip(selected === reader, { selectReader(reader) },
                                label = { Text(reader.device.productName ?: "USB") }, enabled = !busy && !permissionPending)
                        }
                    }
                    if (permissionPending) Text(stringResource(R.string.reader_usbPermission, stringResource(R.string.app_name)))
                    if (selected == null) Text(stringResource(R.string.reader_nfc))
                    OutlinedTextField(pin, { pin = it }, label = { Text("PIN") }, singleLine = true,
                        enabled = !busy, visualTransformation = PasswordVisualTransformation())
                    if (pin.isNotEmpty()) Text(stringResource(R.string.confirm_authenticate))
                    if (busy) CircularProgressIndicator()
                    Button(onClick = ::confirm, enabled = !busy && !permissionPending) { Text("确认") }
                }
                OutlinedButton(onClick = ::cancelRequest) { Text("取消") }
            }
        }
    }

    private fun selectReader(reader: UsbReaderDescriptor) {
        if (usb.hasPermission(reader.device)) { selected = reader; observeRemoval(reader) }
        else {
            pendingReader = reader
            permissionPending = true
            usb.requestPermission(reader.device, PendingIntent.getBroadcast(this, 1,
                Intent(permissionAction).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    private fun refreshReaders() {
        val current = UsbCcidReader.discover(usb)
        readers.filter { old -> current.none { it.device.deviceName == old.device.deviceName } }
            .forEach { CardOperationGate.observedRemoval(it.device.deviceName) }
        selected = selected?.let { prior -> current.firstOrNull {
            it.device.deviceName == prior.device.deviceName && it.interfaceId == prior.interfaceId
        } }
        readers = current
    }

    private fun confirm() {
        if (busy || loading || rejected || failure != null || clientData == null) return
        busy = true
        pendingPin = pin.takeIf { it.isNotEmpty() }?.toCharArray()
        pin = ""
        val reader = selected
        if (reader == null) {
            if (nfc?.isEnabled != true) {
                pendingPin?.fill('\u0000')
                pendingPin = null
                busy = false
                failure = OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED)
                return
            }
            awaitingNfc = true
            nfc?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        } else execute {
            if (CardOperationGate.isRemovalRequired(reader.device.deviceName)) {
                if (UsbCcidReader.isCardAbsent(usb, reader)) CardOperationGate.observedRemoval(reader.device.deviceName)
                throw CardFailure(FailureCode.PHYSICAL_ACTION_REQUIRED)
            }
            UsbCcidReader.connect(usb, reader, allowInitialPowerOn = true)
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        runOnUiThread {
            if (!active || !awaitingNfc || completed) return@runOnUiThread
            awaitingNfc = false
            lastTag = tag
            execute { NfcCardChannel.connect(tag) }
        }
    }

    private fun execute(connect: () -> CardChannel) {
        val secret = pendingPin
        pendingPin = null
        lifecycleScope.launch {
            val cardResult = withContext(Dispatchers.IO) {
                runner.execute(create, get, requireNotNull(clientData), secret, selected?.device?.deviceName, connect)
            }
            val result = if (requestCancelled && cardResult is OperationResult.Completed)
                OperationResult.Failed(FailureCode.CANCELLED, cardResult.effect) else cardResult
            busy = false
            if (completed) return@launch
            when (result) {
                is OperationResult.Completed -> when (val output = result.value) {
                    is ProviderOutput.Created -> completeCreate(output.credential)
                    is ProviderOutput.Assertions -> {
                        if (output.values.size == 1) completeAssertion(output.values.single())
                        else assertions = output.values
                    }
                }
                is OperationResult.Failed -> {
                    failure = result
                    if (result.code == FailureCode.PHYSICAL_ACTION_REQUIRED) {
                        selected?.let { CardOperationGate.requireRemoval(it.device.deviceName); observeRemoval(it) }
                        lastTag?.let { nfc?.ignore(it, 250, { lastTag = null }, Handler(mainLooper)) }
                    }
                }
            }
        }
    }

    private fun completeCreate(credential: CreatedCredential) {
        if (completed) return
        try {
            val result = Intent()
            PendingIntentHandler.setCreateCredentialResponse(result,
                CreatePublicKeyCredentialResponse(ResponseEncoder.create(credential, requireNotNull(clientData))))
            completed = true
            setResult(RESULT_OK, result)
            finish()
        } catch (_: Exception) {
            failure = OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED, writeAccepted = true)
        }
    }

    private fun completeAssertion(assertion: Assertion) {
        if (completed) return
        try {
            val result = Intent()
            PendingIntentHandler.setGetCredentialResponse(result,
                GetCredentialResponse(PublicKeyCredential(ResponseEncoder.get(assertion, requireNotNull(clientData)))))
            completed = true
            assertions = emptyList()
            setResult(RESULT_OK, result)
            finish()
        } catch (_: Exception) { failure = OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED) }
    }

    private fun cancelRequest() {
        if (completed) return
        requestCancelled = true
        pin = ""
        pendingPin?.fill('\u0000')
        pendingPin = null
        awaitingNfc = false
        assertions = emptyList()
        runner.cancel()
        if (busy) {
            // Keep the result screen until an in-flight command's outcome is known.
            if (lastTag == null && selected == null) { busy = false; finishException(true) }
        } else finishException(true)
    }

    private fun finishException(cancelled: Boolean) {
        if (completed) return
        completed = true
        val result = Intent()
        if (intent.action == ProviderRequests.ACTION_CREATE) {
            PendingIntentHandler.setCreateCredentialException(result,
                if (cancelled) CreateCredentialCancellationException() else CreateCredentialUnknownException())
        } else {
            PendingIntentHandler.setGetCredentialException(result,
                if (cancelled) GetCredentialCancellationException() else GetCredentialUnknownException())
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun observeRemoval(reader: UsbReaderDescriptor) {
        if (!CardOperationGate.isRemovalRequired(reader.device.deviceName)) return
        presenceWatch?.cancel()
        presenceWatch = lifecycleScope.launch {
            while (active && CardOperationGate.isRemovalRequired(reader.device.deviceName)) {
                val absent = withContext(Dispatchers.IO) {
                    CardOperationGate.tryAcquire()?.use { UsbCcidReader.isCardAbsent(usb, reader) } == true
                }
                if (absent) { CardOperationGate.observedRemoval(reader.device.deviceName); break }
                delay(750)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (!completed) {
            refreshReaders()
            selected?.let(::observeRemoval)
        }
    }

    override fun onPause() {
        active = false
        presenceWatch?.cancel()
        nfc?.disableReaderMode(this)
        pin = ""
        if (busy) cancelRequest()
        super.onPause()
    }

    override fun onDestroy() {
        pendingPin?.fill('\u0000')
        runner.cancel()
        if (receiverRegistered) unregisterReceiver(receiver)
        super.onDestroy()
    }
}
