package cc.cardkey.signing

import cc.cardkey.protocol.piv.PivClient
import cc.cardkey.ui.ManagerReport
import cc.cardkey.ui.ManagerOperation
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SignatureFormat(val fileName: String, val mimeType: String) {
    RAW("signature.bin", "application/octet-stream"),
    CMS("signature.p7s", "application/pkcs7-signature")
}

data class SigningFileSelection(val name: String, val size: Int, val generation: Long)
enum class SignatureExportStatus { WRITING, SAVED, CANCELLED, UNKNOWN }
data class SigningFileState(val selection: SigningFileSelection? = null,
                            val exportStatus: SignatureExportStatus? = null, val exportOwner: ManagerReport? = null,
                            val active: Boolean = false, val slot: Int = 0x9a, val restoreOperation: ManagerOperation? = null,
                            val operation: ManagerOperation = ManagerOperation.PIV_SIGN) {
    val restoringSave get() = exportOwner != null && (exportStatus == null || exportStatus == SignatureExportStatus.WRITING)
}
internal data class SigningFileRequest(val generation: Long, val route: String, val id: String = java.util.UUID.randomUUID().toString())
internal class SignatureSave(val source: ManagerReport, val bytes: ByteArray) : Closeable {
    val id: String = java.util.UUID.randomUUID().toString()
    private var taken = false
    private var closed = false
    @Synchronized fun takeBytes(): ByteArray? {
        if (taken || closed) return null
        taken = true
        return bytes
    }
    @Synchronized override fun close() { if (!taken) bytes.fill(0); closed = true }
}

class PivSigningFiles {
    private val mutable = MutableStateFlow(SigningFileState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var selected: ByteArray? = null
    private var route: String? = null
    private var picker: SigningFileRequest? = null
    private var save: SignatureSave? = null
    private var writing = false
    private var activeSave: SignatureSave? = null
    internal val hasDocumentRequest get() = picker != null || save != null || writing
    internal val hasSaveOwner get() = save != null || writing
    internal val fileRequestId get() = picker?.id
    internal val saveRequestId get() = save?.id
    internal val saveFormat get() = save?.source?.signatureFormat
    internal val saveMimeType get() = if (save?.source?.sshPublicKey == true) "text/plain" else saveFormat?.mimeType
        ?: "application/octet-stream".takeIf { save?.source?.protectedPivSlot != null }
    internal fun current(request: SigningFileRequest, currentRoute: String?) =
        request.generation == generation && request.route == route && request.route == currentRoute

    internal fun action(active: Boolean, slot: Int, operation: ManagerOperation = ManagerOperation.PIV_SIGN) {
        mutable.value = mutable.value.copy(active = active, slot = slot, operation = operation)
    }
    internal fun request(route: String): SigningFileRequest {
        retireSelection()
        this.route = route
        return SigningFileRequest(generation, route).also { picker = it }
    }
    internal fun takeRequest(id: String): SigningFileRequest? {
        val request = picker?.takeIf { it.id == id } ?: return null
        picker = null
        return request
    }
    internal fun accept(request: SigningFileRequest, currentRoute: String?, name: String, bytes: ByteArray): Boolean {
        try {
            if (!current(request, currentRoute)) return false
            require(bytes.size in 1..PivClient.MAX_SIGNING_FILE_BYTES)
            require(name.isNotBlank())
            selected = bytes.copyOf()
            mutable.value = mutable.value.copy(selection = SigningFileSelection(name, bytes.size, generation))
            return true
        } finally { bytes.fill(0) }
    }
    internal fun take(generation: Long?, currentRoute: String?): ByteArray {
        require(generation == this.generation && currentRoute == route)
        val owned = requireNotNull(selected).copyOf()
        retireSelection()
        return owned
    }
    fun retireSelection() {
        generation++
        picker = null
        selected?.fill(0); selected = null
        route = null
        mutable.value = mutable.value.copy(selection = null)
    }
    internal fun requestSave(report: ManagerReport, operation: ManagerOperation? = null): Boolean {
        if (save != null || writing) return false
        val signature = requireNotNull(report.export)
        val protectedDocument = report.protectedPivSlot?.let { slot ->
            report.protectedPivIdentity != null && slot in PivClient.slots && when (operation) {
                ManagerOperation.PIV_CSR -> report.fileName == "piv-${slot.toString(16)}.csr"
                ManagerOperation.PIV_SLOT -> report.fileName == "piv-${slot.toString(16)}.cer"
                ManagerOperation.PIV_SSH_EXPORT -> report.sshPublicKey && report.fileName == "piv-${slot.toString(16)}-ssh.pub"
                ManagerOperation.PIV_SIGN -> report.signatureFormat == SignatureFormat.RAW && report.fileName == SignatureFormat.RAW.fileName
                else -> false
            }
        } == true
        require(signature.isNotEmpty() && (protectedDocument || (report.sshPublicKey && operation == ManagerOperation.PIV_SSH_EXPORT &&
            report.fileName == "piv-${mutable.value.slot.toString(16)}-ssh.pub") ||
            (report.signatureFormat != null && report.fileName == report.signatureFormat.fileName)))
        save = SignatureSave(report, signature.copyOf())
        mutable.value = mutable.value.copy(exportOwner = report, exportStatus = null, restoreOperation = operation,
            slot = report.protectedPivSlot ?: mutable.value.slot)
        return true
    }
    internal fun takeSave(id: String): SignatureSave? {
        val snapshot = save?.takeIf { it.id == id } ?: return null
        save = null
        writing = true
        activeSave = snapshot
        return snapshot
    }
    internal fun finishSave(snapshot: SignatureSave, status: SignatureExportStatus) {
        snapshot.close()
        if (activeSave !== snapshot) return
        activeSave = null
        writing = false
        if (mutable.value.exportOwner === snapshot.source) mutable.value = mutable.value.copy(exportStatus = status, restoreOperation = null)
    }
    internal fun writing(snapshot: SignatureSave) {
        if (mutable.value.exportOwner === snapshot.source) mutable.value = mutable.value.copy(exportStatus = SignatureExportStatus.WRITING)
    }
    fun retire() {
        retireSelection()
        save?.close(); save = null
        activeSave?.close()
        mutable.value = mutable.value.copy(exportOwner = null, exportStatus = null, restoreOperation = null)
    }
    companion object {
        internal fun readRaw(input: InputStream): ByteArray {
            val buffer = ByteArray(PivClient.MAX_SIGNING_FILE_BYTES + 1)
            try {
                val size = input.use {
                    var count = 0
                    while (count < buffer.size) {
                        val read = it.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        if (read == 0) throw IOException("empty read")
                        count += read
                    }
                    require(count in 1..PivClient.MAX_SIGNING_FILE_BYTES)
                    count
                }
                return buffer.copyOf(size)
            } finally { buffer.fill(0) }
        }
        internal fun writeSignature(snapshot: SignatureSave, open: () -> OutputStream?): SignatureExportStatus {
            val bytes = snapshot.takeBytes() ?: return SignatureExportStatus.UNKNOWN
            return try {
                (open() ?: throw IOException("missing output")).use { it.write(bytes) }
                SignatureExportStatus.SAVED
            } catch (_: Exception) { SignatureExportStatus.UNKNOWN }
            finally { bytes.fill(0); snapshot.close() }
        }
    }
}
