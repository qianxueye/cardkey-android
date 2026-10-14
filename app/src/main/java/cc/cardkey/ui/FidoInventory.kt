package cc.cardkey.ui

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.fido.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InventoryRp(rp: RelyingParty) {
    val id = rp.id
    val name = rp.name
    private val hash = rp.idHash.copyOf()
    fun value() = RelyingParty(id, name, hash.copyOf())
    override fun equals(other: Any?) = other is InventoryRp && id == other.id && name == other.name && hash.contentEquals(other.hash)
    override fun hashCode() = 31 * id.hashCode() + hash.contentHashCode()
}

class InventoryCredential(val rp: InventoryRp, credential: Credential) {
    private val id = credential.id.copyOf()
    private val userId = credential.user.id.copyOf()
    private val encodedKey = CborCodec.encode(credential.publicKey)
    internal val counterIdentity = Cbor.Array(listOf(rp.id.cbor(), id.copyOf().cbor(), CborCodec.decode(encodedKey)))
    val name = credential.user.name
    val displayName = credential.user.displayName
    val protection = credential.protection
    val idHex get() = id.joinToString("") { "%02x".format(it) }
    fun value() = Credential(id.copyOf(), FidoUser(userId.copyOf(), name, displayName),
        CborCodec.decode(encodedKey).map(), protection)
    override fun equals(other: Any?) = other is InventoryCredential && rp == other.rp &&
        id.contentEquals(other.id) && encodedKey.contentEquals(other.encodedKey) && protection == other.protection
    override fun hashCode() = 31 * (31 * rp.hashCode() + id.contentHashCode()) + encodedKey.contentHashCode()
}

data class InventoryState(val generation: Long = 0, val rps: List<InventoryRp> = emptyList(),
    val rp: InventoryRp? = null, val credentials: List<InventoryCredential> = emptyList(),
    val credential: InventoryCredential? = null, val blobReady: Boolean = false, val fileSize: Int? = null, val action: ManagerOperation? = null)

/** The operation owns transferred keys; retirement never wipes a worker's active input. */
class InventoryRequest internal constructor(private val owner: FidoInventory, val generation: Long,
    val rp: InventoryRp?, val credential: InventoryCredential?, val previousCounter: Long?,
    internal val expectedKey: ByteArray?) : AutoCloseable {
    fun requireCurrent() { if (!owner.current(this)) throw CardFailure(FailureCode.CANCELLED) }
    fun verified(counter: Long, key: ByteArray?) = owner.verified(this, counter, key)
    override fun close() { expectedKey?.fill(0) }
}

class FidoInventory {
    private val mutable = MutableStateFlow(InventoryState())
    val state = mutable.asStateFlow()
    private val counters = mutableMapOf<Cbor.Array, Long>()
    private var key: ByteArray? = null
    private var file: ByteArray? = null
    private data class FileRequest(val id: String, val generation: Long, var received: Boolean = false)
    private var fileRequest: FileRequest? = null
    val fileRequestId get() = synchronized(this) { fileRequest?.takeUnless { it.received }?.id }
    @Synchronized fun requestFile(): String? {
        if (mutable.value.credential == null) return null
        retireKey(); clearFile()
        val request = FileRequest(java.util.UUID.randomUUID().toString(), mutable.value.generation)
        fileRequest = request
        return request.id
    }
    @Synchronized fun receiveFile(id: String): Long? {
        val request = fileRequest?.takeIf { it.id == id && !it.received } ?: return null
        request.received = true
        return request.generation
    }
    @Synchronized fun finishFile(id: String, generation: Long, bytes: ByteArray?): Boolean {
        if (fileRequest?.id != id || generation != mutable.value.generation) { bytes?.fill(0); return false }
        fileRequest = null
        file = bytes
        mutable.value = mutable.value.copy(fileSize = bytes?.size)
        return true
    }
    @Synchronized fun takeFile(): ByteArray {
        val bytes = file ?: throw CardFailure(FailureCode.INVALID_INPUT)
        file = null
        mutable.value = mutable.value.copy(fileSize = null)
        return bytes
    }
    private fun clearFile() {
        file?.fill(0); file = null; fileRequest = null
        mutable.value = mutable.value.copy(fileSize = null)
    }

    @Synchronized fun action(operation: ManagerOperation?) {
        val before = mutable.value
        if (before.action == operation) return
        if (fileRequest != null || operation !in setOf(ManagerOperation.FIDO_VERIFY_CREDENTIAL,
                ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)) {
            clearFile(); clearKey()
        }
        mutable.value = mutable.value.copy(generation = before.generation + 1,
            action = operation, blobReady = key != null)
    }
    @Synchronized fun selectRp(rp: InventoryRp) {
        if (rp !in mutable.value.rps) return
        clearKey(); clearFile()
        mutable.value = mutable.value.copy(generation = mutable.value.generation + 1,
            rp = rp, credentials = emptyList(), credential = null, blobReady = false)
    }
    @Synchronized fun selectCredential(credential: InventoryCredential) {
        if (credential !in mutable.value.credentials) return
        clearKey(); clearFile()
        mutable.value = mutable.value.copy(generation = mutable.value.generation + 1,
            credential = credential, blobReady = false)
    }
    @Synchronized fun begin(input: OperationInput): InventoryRequest {
        val before = mutable.value
        if (input.operation == ManagerOperation.FIDO_CREDENTIALS && input.inventoryGeneration != null && before.rp == null)
            throw CardFailure(FailureCode.INVALID_INPUT)
        if (input.inventoryGeneration != null && input.inventoryGeneration != before.generation)
            throw CardFailure(FailureCode.CANCELLED)
        val selected = input.operation == ManagerOperation.FIDO_VERIFY_CREDENTIAL ||
            input.operation in setOf(ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE) && before.credential != null
        val blob = selected && input.operation != ManagerOperation.FIDO_VERIFY_CREDENTIAL
        if (selected && (before.credential == null || input.inventoryGeneration == null || input.pin.isEmpty())) {
            clearKey(); mutable.value = before.copy(blobReady = false)
            throw CardFailure(FailureCode.INVALID_INPUT)
        }
        if (blob && key == null) throw CardFailure(FailureCode.INVALID_INPUT)
        val transferred = if (blob) key.also { key = null } else null
        clearKey()
        fileRequest = null
        if (input.operation !in setOf(ManagerOperation.FIDO_VERIFY_CREDENTIAL, ManagerOperation.FIDO_BLOB_WRITE)) clearFile()
        val generation = before.generation + 1
        mutable.value = mutable.value.copy(generation = generation, blobReady = false)
        return InventoryRequest(this, generation, before.rp, before.credential.takeIf { selected },
            before.credential.takeIf { selected }?.let { counters[it.counterIdentity] }, transferred)
    }
    @Synchronized internal fun current(request: InventoryRequest) = request.generation == mutable.value.generation
    @Synchronized internal fun verified(request: InventoryRequest, counter: Long, freshKey: ByteArray?) {
        request.requireCurrent()
        counters[requireNotNull(request.credential).counterIdentity] = counter
        clearKey()
        key = freshKey?.copyOf()
        mutable.value = mutable.value.copy(blobReady = key != null)
    }
    @Synchronized fun settle(request: InventoryRequest, report: ManagerReport) {
        if (!current(request)) return
        report.relyingParties?.let { rps ->
            mutable.value = mutable.value.copy(rps = rps.map(::InventoryRp), rp = null,
                credentials = emptyList(), credential = null)
        }
        report.credentials?.let { credentials ->
            if (request.rp != null) mutable.value = mutable.value.copy(rp = request.rp,
                credentials = credentials.map { InventoryCredential(request.rp, it) }, credential = null)
        }
    }
    @Synchronized fun retire() {
        clearKey(); clearFile(); counters.clear()
        mutable.value = InventoryState(generation = mutable.value.generation + 1, action = mutable.value.action)
    }
    @Synchronized fun retireKey() {
        clearKey()
        mutable.value = mutable.value.copy(generation = mutable.value.generation + 1, blobReady = false)
    }
    private fun clearKey() { key?.fill(0); key = null }
}
