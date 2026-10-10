package cc.cardkey.ui

import android.app.Application
import android.hardware.usb.UsbManager
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.cardkey.protocol.*
import cc.cardkey.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class ManagerState(
    val readers: List<UsbReaderDescriptor> = emptyList(),
    val selectedReader: UsbReaderDescriptor? = null,
    val nfc: Boolean = true,
    val busy: Boolean = false,
    val awaitingNfc: Boolean = false,
    val result: OperationResult<ManagerReport>? = null,
)

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val usb = application.getSystemService(UsbManager::class.java)
    private val mutable = MutableStateFlow(ManagerState())
    val state = mutable.asStateFlow()
    private val operations = ManagerOperations()
    private var pending: OperationInput? = null
    private var inProgress = false
    @Volatile private var cancelled = false
    private var presenceBlockedReader: String? = null
    private val currentChannel = AtomicReference<CardChannel?>(null)

    fun refreshReaders() {
        val readers = UsbCcidReader.discover(usb)
        val selected = mutable.value.selectedReader?.let { prior -> readers.firstOrNull {
            it.device.deviceName == prior.device.deviceName && it.interfaceId == prior.interfaceId
        } }
        if (presenceBlockedReader != null && readers.none { it.device.deviceName == presenceBlockedReader }) presenceBlockedReader = null
        mutable.value = mutable.value.copy(readers = readers, selectedReader = selected)
    }

    fun selectReader(reader: UsbReaderDescriptor?) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(selectedReader = reader, nfc = reader == null, result = null)
    }

    fun submit(input: OperationInput) {
        if (mutable.value.busy) { input.close(); return }
        cancelled = false
        mutable.value = mutable.value.copy(busy = true, result = null)
        if (mutable.value.nfc) {
            pending = input
            mutable.value = mutable.value.copy(awaitingNfc = true)
        } else {
            val reader = mutable.value.selectedReader
            if (reader == null || !usb.hasPermission(reader.device)) {
                input.close()
                finish(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE))
                return
            }
            if (reader.device.deviceName == presenceBlockedReader) {
                input.close()
                finish(OperationResult.Failed(FailureCode.PHYSICAL_ACTION_REQUIRED, Effect.NONE))
                return
            }
            run(input) { UsbCcidReader.connect(usb, reader, allowInitialPowerOn = true) }
        }
    }

    fun onTag(tag: Tag) {
        viewModelScope.launch {
            val input = pending ?: return@launch
            pending = null
            mutable.value = mutable.value.copy(awaitingNfc = false)
            run(input) { NfcCardChannel.connect(tag) }
        }
    }

    private fun run(input: OperationInput, connect: () -> CardChannel) {
        inProgress = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (cancelled) OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE)
                    else {
                        val channel = connect()
                        currentChannel.set(channel)
                        if (cancelled) {
                            channel.close()
                            OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE)
                        } else CardSession(channel).run { operations.execute(it, input) }
                    }
                } catch (e: CardFailure) {
                    OperationResult.Failed(e.code, Effect.NONE, e.status)
                } catch (e: TransportException) {
                    OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE, dispatch = e.dispatch)
                } catch (_: SecurityException) {
                    OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.NONE)
                } finally { currentChannel.set(null); input.close() }
            }
            inProgress = false
            if (result is OperationResult.Failed && result.code == FailureCode.PHYSICAL_ACTION_REQUIRED) {
                presenceBlockedReader = mutable.value.selectedReader?.device?.deviceName
            }
            finish(if (cancelled && result is OperationResult.Failed) result.copy(code = FailureCode.CANCELLED) else result)
        }
    }

    private fun finish(result: OperationResult<ManagerReport>) {
        mutable.value = mutable.value.copy(busy = false, awaitingNfc = false, result = result)
    }

    fun cancel() {
        cancelled = true
        pending?.close()
        pending = null
        currentChannel.get()?.let { channel -> viewModelScope.launch(Dispatchers.IO) { channel.close() } }
        if (!inProgress && mutable.value.busy) finish(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE))
    }

    override fun onCleared() { cancel(); super.onCleared() }
}
