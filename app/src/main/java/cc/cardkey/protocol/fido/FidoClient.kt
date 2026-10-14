package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.Apdu
import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.CardSession
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException

class FidoClient(private val session: CardSession) {
    private var selected = false

    fun select() {
        if (session.wire == CardWire.CTAP) {
            if (!session.supportsCtap2) throw CardFailure(FailureCode.UNSUPPORTED)
            selected = true
            return
        }
        val result = session.exchange(Apdu(ins = 0xa4, p1 = 4, data = byteArrayOf(0xa0.toByte(), 0, 0, 6, 0x47, 0x2f, 0, 1))).requireSuccess()
        if (!result.contentEquals("FIDO_2_0".toByteArray()) && !result.contentEquals("U2F_V2".toByteArray())) malformed()
        selected = true
    }

    internal fun command(code: Int, parameters: Cbor.Map? = null, effectful: Boolean = false, applied: Boolean = false, securityState: Boolean = false): Cbor.Map {
        require(code in setOf(1, 2, 4, 6, 7, 8, 0x0a, 0x0c, 0x0d))
        check(selected)
        val bytes = byteArrayOf(code.toByte()) + (parameters?.let(CborCodec::encode) ?: byteArrayOf())
        val response = session.exchangeCtap2(bytes, effectful)
        if (response.isEmpty()) {
            if (effectful) session.securityStateUncertain()
            malformed()
        }
        val status = response[0].toInt() and 255
        if (status != 0 && response.size > 1) {
            val encoded = response.copyOfRange(1, response.size)
            try { CborCodec.decode(encoded).map() }
            catch (failure: CardFailure) {
                if (effectful) session.securityStateUncertain()
                throw failure
            } finally { encoded.fill(0); response.fill(0) }
        }
        if (effectful && status in setOf(0x7f, 0x2d)) session.securityStateUncertain()
        if (effectful && code == 6) when (status) {
            0x31, 0x34 -> session.securityStateChanged()
            0x32 -> session.securityStateUncertain()
            0x37 -> if (parameters?.get(2)?.number() == 9L) session.securityStateChanged()
        }
        if (status != 0) throw CardFailure(when (status) {
            0x23, 0x2f, 0x3b -> FailureCode.PHYSICAL_ACTION_REQUIRED
            0x31 -> FailureCode.PIN_INVALID
            0x32, 0x34 -> FailureCode.PIN_BLOCKED
            0x33, 0x35, 0x36, 0x3c, 0x3f, 0x40, 0x27 -> FailureCode.AUTHORIZATION_DENIED
            0x01, 0x26, 0x2b, 0x3e -> FailureCode.UNSUPPORTED
            0x15, 0x17, 0x18, 0x28, 0x39 -> FailureCode.CAPACITY_EXCEEDED
            0x06, 0x21, 0x24 -> FailureCode.BUSY
            0x2d -> FailureCode.CANCELLED
            else -> FailureCode.CARD_REJECTED
        }, status)
        if (securityState) session.securityStateChanged()
        if (applied) session.applied()
        if (code == 7 && response.size != 1) {
            // The success status acknowledges reset even when its forbidden body is malformed.
            response.fill(0)
            malformed()
        }
        if (response.size == 1) { response.fill(0); return cborMap() }
        val encoded = response.copyOfRange(1, response.size)
        return try { CborCodec.decode(encoded).map() }
        catch (failure: CardFailure) {
            if (effectful) session.securityStateUncertain()
            throw failure
        } finally { encoded.fill(0); response.fill(0) }
    }

    fun reset(): FidoInfo {
        try { command(7, effectful = true, applied = true) }
        catch (failure: CardFailure) {
            if (failure.status == 0x6f00) session.securityStateUncertain()
            throw failure
        }
        return getInfo()
    }

    fun getInfo(): FidoInfo {
        val map = command(4)
        val versions = map[1].array().map { it.text() }
        if (versions.isEmpty() || map[3].bytes().size != 16) malformed()
        val options = map[4]?.map()?.value?.map { (key, value) -> key.text() to value.bool() }?.toMap() ?: emptyMap()
        val algorithms = map[10]?.array()?.map { entry ->
            val parameter = entry.map()
            val type = parameter["type"].text()
            val algorithm = parameter["alg"].number()
            if (algorithm !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) malformed()
            FidoAlgorithmParameter(type, algorithm.toInt())
        }?.also { if (it.isEmpty() || it.distinct().size != it.size) malformed() }
        val info = FidoInfo(versions, map[2]?.array()?.map { it.text() }?.toSet() ?: emptySet(), options,
            map[6]?.array()?.map { it.count(255) } ?: emptyList(), map[13]?.count(63) ?: 4,
            map[15]?.count(65535), map, algorithms, map[12]?.bool())
        session.setCtap2MaxCommandBytes(map[5]?.count(CborCodec.MAX_BYTES) ?: 1024)
        return info
    }

    fun retries(): PinRetries {
        val info = getInfo(); val protocol = info.protocol()
        val pin = command(6, cborMap(1 to protocol.cbor(), 2 to 1.cbor()))
        val uv = if (info.options.containsKey("uv")) command(6, cborMap(1 to protocol.cbor(), 2 to 7.cbor()))[5]?.count() else null
        return PinRetries(pin[3].count(), pin[4]?.bool(), uv)
    }

    private fun agreement(protocol: Int): PinProtocol {
        val response = command(6, cborMap(1 to protocol.cbor(), 2 to 2.cbor()))
        return PinProtocol(protocol, response[1].map())
    }

    fun setPin(newPin: CharArray) = try {
        val info = getInfo()
        if (info.options["clientPin"] != false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        val plain = pinBytes(newPin, info.minPinLength)
        try { agreement(info.protocol()).use { protocol ->
            val padded = plain.copyOf(64)
            val encrypted = try { protocol.encrypt(padded) } finally { padded.fill(0) }
            command(6, cborMap(1 to protocol.version.cbor(), 2 to 3.cbor(), 3 to protocol.publicKey,
                4 to protocol.authenticate(encrypted).cbor(), 5 to encrypted.cbor()), effectful = true, applied = true)
            Unit
        } } finally { plain.fill(0) }
    } finally { newPin.fill('\u0000') }

    fun changePin(oldPin: CharArray, newPin: CharArray) = try {
        val info = getInfo(); val new = pinBytes(newPin, info.minPinLength); var old: ByteArray? = null
        try {
            old = pinBytes(oldPin, 4)
            agreement(info.protocol()).use { protocol ->
                val padded = new.copyOf(64)
                val encrypted = try { protocol.encrypt(padded) } finally { padded.fill(0) }
                val hash = sha256(old).let { full -> full.copyOf(16).also { full.fill(0) } }
                val oldEncrypted = try { protocol.encrypt(hash) } finally { hash.fill(0) }
                command(6, cborMap(1 to protocol.version.cbor(), 2 to 4.cbor(), 3 to protocol.publicKey,
                    4 to protocol.authenticate(encrypted + oldEncrypted).cbor(), 5 to encrypted.cbor(),
                    6 to oldEncrypted.cbor()), effectful = true, applied = true)
                Unit
            }
        } finally { new.fill(0); old?.fill(0) }
    } finally { oldPin.fill('\u0000'); newPin.fill('\u0000') }

    private fun <T> withToken(pin: CharArray, permission: Int, rpId: String? = null, info: FidoInfo, block: (PinToken) -> T): T {
        try {
            info.requireOption("pinUvAuthToken")
            require(permission in setOf(1, 2, 4, 16, 32))
            require((permission in setOf(1, 2)) == (rpId != null))
            val plain = pinBytes(pin, 4)
            try { agreement(info.protocol()).use { protocol ->
                val hash = sha256(plain).let { full -> full.copyOf(16).also { full.fill(0) } }
                val encrypted = try { protocol.encrypt(hash) } finally { hash.fill(0) }
                val response = command(6, cborMap(1 to protocol.version.cbor(), 2 to 9.cbor(), 3 to protocol.publicKey,
                    6 to encrypted.cbor(), 9 to permission.cbor(), 10 to rpId?.cbor()), effectful = true, securityState = true)
                PinToken(protocol.version, protocol.decrypt(response[2].bytes())).use { token -> return block(token) }
            } } finally { plain.fill(0) }
        } finally { pin.fill('\u0000') }
    }

    private fun management(subCommand: Int, params: Cbor.Map? = null, token: PinToken? = null, mutate: Boolean = false): Cbor.Map {
        val auth = token?.authenticate(byteArrayOf(subCommand.toByte()) + (params?.let(CborCodec::encode) ?: byteArrayOf()))
        return command(0x0a, cborMap(1 to subCommand.cbor(), 2 to params, 3 to token?.version?.cbor(), 4 to auth?.cbor()), mutate, mutate)
    }

    private fun <T> managed(pin: CharArray, block: (PinToken) -> T): T = try {
        val info = getInfo(); info.requireOption("credMgmt")
        withToken(pin, 4, info = info, block = block)
    } finally { pin.fill('\u0000') }

    fun capacity(pin: CharArray): CredentialCapacity = managed(pin) { token ->
        val map = management(1, token = token)
        CredentialCapacity(map[1].count(), map[2].count())
    }

    fun enumerateRelyingParties(pin: CharArray): List<RelyingParty> = managed(pin, ::relyingParties)

    private fun relyingParties(token: PinToken): List<RelyingParty> {
        val first = try { management(2, token = token) } catch (failure: CardFailure) {
            if (failure.status == 0x2e) return emptyList() else throw failure
        }
        val count = first[5].count(); if (count == 0) malformed()
        return List(count) { index ->
            val map = if (index == 0) first else management(3)
            val rp = map[3].map(); val hash = map[4].bytes()
            if (hash.size != 32) malformed()
            RelyingParty(rp["id"].text(), rp["name"]?.text(), hash)
        }
    }

    fun enumerateCredentials(pin: CharArray, rpIdHash: ByteArray): List<Credential> = try {
        require(rpIdHash.size == 32)
        managed(pin) { credentials(it, rpIdHash) }
    } finally { pin.fill('\u0000') }

    private fun credentials(token: PinToken, rpIdHash: ByteArray): List<Credential> {
        val first = try { management(4, cborMap(1 to rpIdHash.cbor()), token) } catch (failure: CardFailure) {
            if (failure.status == 0x2e) return emptyList() else throw failure
        }
        val count = first[9].count(); if (count == 0) malformed()
        return List(count) { index ->
            val map = if (index == 0) first else management(5)
            // Large-blob keys are intentionally not retained during enumeration.
            map[11]?.bytes()?.fill(0)
            Credential(parseDescriptor(map[7]), parseUser(map[6]), map[8].map(), map[10]?.count(3))
        }
    }

    private fun credential(token: PinToken, id: ByteArray): Pair<RelyingParty, Credential> {
        require(id.isNotEmpty())
        for (rp in relyingParties(token)) {
            credentials(token, rp.idHash).firstOrNull { it.id.contentEquals(id) }?.let { return rp to it }
        }
        throw CardFailure(FailureCode.INVALID_INPUT)
    }

    fun deleteCredential(pin: CharArray, id: ByteArray) = managed(pin) { token ->
        val (rp, _) = credential(token, id)
        management(6, cborMap(2 to descriptor(id)), token, true)
        if (credentials(token, rp.idHash).any { it.id.contentEquals(id) }) throw CardFailure(FailureCode.RECOVERY_REQUIRED)
    }

    fun updateUser(pin: CharArray, id: ByteArray, name: String?, displayName: String?) = managed(pin) { token ->
        val (rp, existing) = credential(token, id)
        val user = existing.user.copy(name = name, displayName = displayName)
        management(7, cborMap(2 to descriptor(id), 3 to user.encode()), token, true)
        val updated = credentials(token, rp.idHash).singleOrNull { it.id.contentEquals(id) }?.user
        if (updated == null || !updated.id.contentEquals(user.id) || updated.name.orEmpty() != name.orEmpty() || updated.displayName.orEmpty() != displayName.orEmpty())
            throw CardFailure(FailureCode.RECOVERY_REQUIRED)
    }

    private fun configure(subCommand: Int, params: Cbor.Map?, token: PinToken?) {
        val message = ByteArray(32) { 0xff.toByte() } + byteArrayOf(0x0d, subCommand.toByte()) +
            (params?.let(CborCodec::encode) ?: byteArrayOf())
        command(0x0d, cborMap(1 to subCommand.cbor(), 2 to params, 3 to token?.version?.cbor(),
            4 to token?.authenticate(message)?.cbor()), effectful = true, applied = true)
    }

    private fun configuration(pin: CharArray?, info: FidoInfo, subCommand: Int, params: Cbor.Map? = null) {
        info.requireOption("authnrCfg")
        val protected = info.options["clientPin"] == true || info.options["uv"] == true || info.options["alwaysUv"] == true
        if (protected && !(subCommand == 2 && info.options["alwaysUv"] == true &&
                    info.options["clientPin"] != true && info.options["uv"] != true)) {
            if (pin == null) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            withToken(pin, 32, info = info) { configure(subCommand, params, it) }
        } else configure(subCommand, params, null)
    }

    fun setAlwaysUv(pin: CharArray?, enabled: Boolean): FidoInfo = try {
        val before = getInfo()
        val current = before.options["alwaysUv"] ?: throw CardFailure(FailureCode.UNSUPPORTED)
        if (current == enabled) before else {
            configuration(pin, before, 2)
            getInfo().also { if (it.options["alwaysUv"] != enabled) throw CardFailure(FailureCode.RECOVERY_REQUIRED) }
        }
    } finally { pin?.fill('\u0000') }

    fun enableEnterpriseAttestation(pin: CharArray?): FidoInfo = try {
        val before = getInfo()
        if (!before.options.containsKey("ep")) throw CardFailure(FailureCode.UNSUPPORTED)
        if (before.options["ep"] == true) before else {
            configuration(pin, before, 1)
            getInfo().also { if (it.options["ep"] != true) throw CardFailure(FailureCode.RECOVERY_REQUIRED) }
        }
    } finally { pin?.fill('\u0000') }

    fun setMinPinLength(pin: CharArray?, length: Int, rpIds: List<String>? = null, forceChange: Boolean = false): FidoInfo = try {
        val info = getInfo(); info.requireOption("setMinPINLength")
        require(length in info.minPinLength..63 && (rpIds == null ||
            (rpIds.distinct().size == rpIds.size && rpIds.all { it.isNotBlank() })))
        if (rpIds != null && "minPinLength" !in info.extensions) throw CardFailure(FailureCode.UNSUPPORTED)
        val maxRps = info.raw[16]?.count() ?: 0
        require(rpIds == null || rpIds.size <= maxRps)
        configuration(pin, info, 3, cborMap(1 to length.cbor(),
            2 to rpIds?.let { Cbor.Array(it.map { rp -> rp.cbor() }) }, 3 to forceChange.cbor()))
        getInfo().also { if (it.minPinLength != length || (forceChange && it.forcePinChange != true)) throw CardFailure(FailureCode.RECOVERY_REQUIRED) }
    } finally { pin?.fill('\u0000') }

    fun makeCredential(request: MakeCredentialRequest, pin: CharArray? = null): MakeCredentialResult = try {
        require(request.rpId.isNotBlank() && request.clientDataHash.size == 32 && request.algorithms.isNotEmpty())
        require(request.enterpriseAttestation == null || request.enterpriseAttestation in 1..2)
        val info = getInfo()
        if (request.residentKey) info.requireOption("rk")
        require(!request.extensions.largeBlobKey || request.residentKey)
        if (request.enterpriseAttestation != null) info.requireOption("ep")
        val ext = makeExtensions(request.extensions, info)
        val parameters = cborMap(1 to request.clientDataHash.cbor(),
            2 to textMap("id" to request.rpId.cbor(), "name" to request.rpName.cbor()), 3 to request.user.encode(),
            4 to Cbor.Array(request.algorithms.map { textMap("alg" to it.cbor(), "type" to "public-key".cbor()) }),
            5 to request.excludeIds.takeIf { it.isNotEmpty() }?.let { Cbor.Array(it.map(::descriptor)) },
            6 to ext.takeIf { it.value.isNotEmpty() },
            7 to textMap("rk" to request.residentKey.cbor(), "uv" to if (pin == null) request.requireUv.cbor() else null),
            10 to request.enterpriseAttestation?.cbor())
        fun execute(token: PinToken?): MakeCredentialResult {
            val params = Cbor.Map(parameters.value + if (token != null) cborMap(
                8 to token.authenticate(request.clientDataHash).cbor(), 9 to token.version.cbor()).value else emptyMap())
            val result = command(1, params, effectful = true, applied = true)
            val response = MakeCredentialResult(result[1].text(), result[2].bytes(), result[3].map(),
                result[5]?.bytes(), result[4]?.bool())
            try {
                val authData = AuthenticatorData.parse(response.authData)
                if (authData.credentialId == null || !authData.rpIdHash.contentEquals(sha256(request.rpId.toByteArray(Charsets.UTF_8))) ||
                    !authData.userPresent || (request.requireUv && !authData.userVerified) ||
                    authData.publicKey?.get(3)?.number()?.toInt() !in request.algorithms) malformed()
                if (response.largeBlobKey != null && (!request.extensions.largeBlobKey || response.largeBlobKey.size != 32)) malformed()
                validateMakeExtensions(request.extensions, authData.extensions)
                return response
            } catch (failure: Exception) { response.largeBlobKey?.fill(0); throw failure }
        }
        if (pin != null) withToken(pin, 1, request.rpId, info, ::execute) else execute(null)
    } finally { pin?.fill('\u0000') }

    fun getAssertion(request: GetAssertionRequest, pin: CharArray? = null): List<Assertion> = try {
        require(request.rpId.isNotBlank() && request.clientDataHash.size == 32)
        val info = getInfo()
        val extensionPairs = linkedMapOf<Cbor, Cbor>()
        fun addExtension(name: String, value: Cbor) {
            if (name !in info.extensions) throw CardFailure(FailureCode.UNSUPPORTED)
            extensionPairs[Cbor.Text(name)] = value
        }
        if (request.extensions.uvm) addExtension("uvm", true.cbor())
        if (request.extensions.credentialBlob) addExtension("credBlob", true.cbor())
        if (request.extensions.largeBlobKey) addExtension("largeBlobKey", true.cbor())
        val salts = request.extensions.hmacSalts
        var hmacProtocol: PinProtocol? = null
        try {
            if (salts != null) {
                require(salts.size == 32 || salts.size == 64)
                if ("hmac-secret" !in info.extensions) throw CardFailure(FailureCode.UNSUPPORTED)
                hmacProtocol = agreement(info.protocol())
                val encrypted = hmacProtocol.encrypt(salts)
                addExtension("hmac-secret", cborMap(1 to hmacProtocol.publicKey, 2 to encrypted.cbor(),
                    3 to hmacProtocol.authenticate(encrypted).cbor(), 4 to hmacProtocol.version.cbor()))
            }
            val params = cborMap(1 to request.rpId.cbor(), 2 to request.clientDataHash.cbor(),
                3 to request.allowIds.takeIf { it.isNotEmpty() }?.let { Cbor.Array(it.map(::descriptor)) },
                4 to Cbor.Map(extensionPairs).takeIf { it.value.isNotEmpty() },
                5 to textMap("up" to request.userPresence.cbor(), "uv" to if (pin == null) request.requireUv.cbor() else null))
            fun execute(token: PinToken?): List<Assertion> {
                val authenticated = Cbor.Map(params.value + if (token == null) emptyMap() else cborMap(
                    6 to token.authenticate(request.clientDataHash).cbor(), 7 to token.version.cbor()).value)
                val first = command(2, authenticated, effectful = true, applied = true)
                val assertions = mutableListOf<Assertion>()
                try {
                    val count = first[5]?.count() ?: 1
                    if (count == 0 || (request.allowIds.isNotEmpty() && count != 1)) malformed()
                    repeat(count) { index ->
                        val response = if (index == 0) first else command(8, effectful = true, applied = true)
                        val id = response[1]?.let(::parseDescriptor) ?: request.allowIds.singleOrNull() ?: malformed()
                        if (request.allowIds.isNotEmpty() && request.allowIds.none { it.contentEquals(id) }) malformed()
                        val data = response[2].bytes(); val auth = AuthenticatorData.parse(data)
                        if (auth.credentialId != null || !auth.rpIdHash.contentEquals(sha256(request.rpId.toByteArray(Charsets.UTF_8))) ||
                            (request.userPresence && !auth.userPresent) || (request.requireUv && !auth.userVerified)) malformed()
                        val hmacOutput = auth.extensions?.get("hmac-secret")?.let {
                            val protocol = hmacProtocol ?: malformed()
                            protocol.decrypt(it.bytes()).also { plain -> if (plain.size != salts?.size) { plain.fill(0); malformed() } }
                        }
                        try {
                            if (salts != null && hmacOutput == null) malformed()
                            val largeBlobKey = response[7]?.bytes()
                            if (largeBlobKey != null && (!request.extensions.largeBlobKey || largeBlobKey.size != 32)) malformed()
                            auth.extensions?.get("credBlob")?.let { value ->
                                if (!request.extensions.credentialBlob) malformed()
                                value.bytes()
                            }
                            assertions += Assertion(id, data, response[3].bytes(), response[4]?.let(::parseUser), hmacOutput, largeBlobKey)
                        } catch (failure: Exception) {
                            hmacOutput?.fill(0); response[7]?.let { (it as? Cbor.Bytes)?.value?.fill(0) }; throw failure
                        }
                    }
                    return assertions
                } catch (failure: Exception) {
                    (first[7] as? Cbor.Bytes)?.value?.fill(0)
                    assertions.forEach { it.hmacSecret?.fill(0); it.largeBlobKey?.fill(0) }
                    throw failure
                }
            }
            if (pin != null) withToken(pin, 2, request.rpId, info, ::execute) else execute(null)
        } finally { hmacProtocol?.close(); salts?.fill(0) }
    } finally { pin?.fill('\u0000'); request.extensions.hmacSalts?.fill(0) }

    fun readLargeBlob(key: ByteArray): ByteArray? = try {
        require(key.size == 32)
        val entries = LargeBlobs.decode(readLargeBlobBytes(getInfo()))
        var plaintext: ByteArray? = null
        for (entry in entries) {
            plaintext = LargeBlobs.decrypt(key, entry)
            if (plaintext != null) break
        }
        plaintext
    } finally { key.fill(0) }

    fun writeLargeBlob(pin: CharArray?, key: ByteArray, plaintext: ByteArray?) {
        var wroteFragment = false
        try {
            require(key.size == 32 && (plaintext == null || plaintext.size <= LargeBlobs.MAX_PLAINTEXT))
            val info = getInfo()
            val entries = LargeBlobs.decode(readLargeBlobBytes(info)).filter { entry ->
                val existing = LargeBlobs.decrypt(key, entry)
                if (existing == null) true else { existing.fill(0); false }
            }.toMutableList()
            if (plaintext != null) entries += LargeBlobs.encrypt(key, plaintext)
            val serialized = LargeBlobs.encode(entries)
            val capacity = info.raw[11].count(CborCodec.MAX_BYTES)
            if (serialized.size > capacity) throw CardFailure(FailureCode.CAPACITY_EXCEEDED)
            fun write(token: PinToken?) {
                val chunk = largeBlobChunk(info)
                var offset = 0
                while (offset < serialized.size) {
                    val fragment = serialized.copyOfRange(offset, minOf(offset + chunk, serialized.size))
                    val authenticated = ByteArray(32) { 0xff.toByte() } + byteArrayOf(0x0c, 0) +
                        ByteArray(4) { (offset ushr (it * 8)).toByte() } + sha256(fragment)
                    command(0x0c, cborMap(2 to fragment.cbor(), 3 to offset.cbor(),
                        4 to if (offset == 0) serialized.size.cbor() else null,
                        5 to token?.authenticate(authenticated)?.cbor(), 6 to token?.version?.cbor()),
                        effectful = true, applied = offset + fragment.size == serialized.size)
                    wroteFragment = true
                    offset += fragment.size
                }
            }
            if (info.options["clientPin"] == true || info.options["uv"] == true || info.options["alwaysUv"] == true) {
                if (pin == null) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
                withToken(pin, 16, info = info, block = ::write)
            } else write(null)
            if (!readLargeBlobBytes(info).contentEquals(serialized)) throw CardFailure(FailureCode.RECOVERY_REQUIRED)
        } catch (failure: CardFailure) {
            if (wroteFragment && failure.code in setOf(FailureCode.CAPACITY_EXCEEDED, FailureCode.UNSUPPORTED,
                    FailureCode.AUTHORIZATION_DENIED, FailureCode.PIN_INVALID, FailureCode.PIN_BLOCKED))
                throw CardFailure(FailureCode.RECOVERY_REQUIRED, failure.status)
            throw failure
        } catch (failure: TransportException) {
            // A later undispatched fragment or readback does not undo an accepted write.
            if (wroteFragment && !failure.cancelled && failure.dispatch == Dispatch.NOT_DISPATCHED)
                throw CardFailure(FailureCode.RECOVERY_REQUIRED)
            throw failure
        } finally { pin?.fill('\u0000'); key.fill(0); plaintext?.fill(0) }
    }

    private fun largeBlobChunk(info: FidoInfo): Int {
        val maxMessage = info.raw[5]?.count(CborCodec.MAX_BYTES) ?: 1024
        if (maxMessage <= 64) malformed()
        return minOf(1024, maxMessage - 64)
    }

    private fun readLargeBlobBytes(info: FidoInfo): ByteArray {
        info.requireOption("largeBlobs")
        val capacity = info.raw[11].count(CborCodec.MAX_BYTES)
        val chunk = largeBlobChunk(info)
        val result = java.io.ByteArrayOutputStream()
        while (true) {
            val part = command(0x0c, cborMap(1 to chunk.cbor(), 3 to result.size().cbor()))[1].bytes()
            if (part.size > chunk || result.size() + part.size > capacity) malformed()
            result.write(part)
            if (part.size < chunk) break
        }
        return result.toByteArray()
    }

    private fun makeExtensions(extension: MakeExtensions, info: FidoInfo): Cbor.Map {
        val values = linkedMapOf<Cbor, Cbor>()
        fun add(name: String, value: Cbor) {
            if (name !in info.extensions) throw CardFailure(FailureCode.UNSUPPORTED)
            values[Cbor.Text(name)] = value
        }
        extension.credentialProtection?.let { require(it in 1..3); add("credProtect", it.cbor()) }
        extension.credentialBlob?.let {
            require(it.size <= (info.maxCredentialBlobLength ?: 0)); add("credBlob", it.cbor())
        }
        if (extension.uvm) add("uvm", true.cbor())
        if (extension.hmacSecret) add("hmac-secret", true.cbor())
        if (extension.minPinLength) add("minPinLength", true.cbor())
        if (extension.largeBlobKey) add("largeBlobKey", true.cbor())
        return Cbor.Map(values)
    }

    private fun validateMakeExtensions(request: MakeExtensions, response: Cbor.Map?) {
        response?.get("credProtect")?.let { if (it.count(3) < (request.credentialProtection ?: 1)) malformed() }
        if (request.credentialProtection != null && response?.get("credProtect") == null) malformed()
        response?.get("credBlob")?.bool()
        response?.get("hmac-secret")?.bool()
        response?.get("minPinLength")?.count(63)
    }
}
