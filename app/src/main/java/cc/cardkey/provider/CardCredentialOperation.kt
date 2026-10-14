package cc.cardkey.provider

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.CardSession
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.TransportException
import cc.cardkey.protocol.fido.*
import java.math.BigInteger

data class CreatedCredential(val result: MakeCredentialResult, val credentialId: ByteArray, val residentKey: Boolean,
    val extensions: ClientExtensionOutputs = ClientExtensionOutputs())

object CardCredentialOperation {
    fun create(session: CardSession, request: ParsedCreate, clientData: ClientData,
        pin: CharArray? = null): CreatedCredential = try {
        validateClientData(clientData)
        val client = FidoClient(session)
        client.select()
        create(client, client.getInfo(), request, clientData, pin)
    } finally { pin?.fill('\u0000'); request.extensions.close() }

    internal fun create(client: FidoClient, info: FidoInfo, request: ParsedCreate, clientData: ClientData,
        pin: CharArray?): CreatedCredential = try {
        validateClientData(clientData)
        val resident = when (request.residentKey) {
            ResidentKeyRequirement.REQUIRED -> {
                if (info.options["rk"] != true) throw CardFailure(FailureCode.UNSUPPORTED)
                true
            }
            ResidentKeyRequirement.PREFERRED -> info.options["rk"] == true
            ResidentKeyRequirement.DISCOURAGED -> false
        }
        val verification = if (info.options["alwaysUv"] == true ||
            (info.options["clientPin"] == true && (resident || info.options["makeCredUvNotRqd"] != true)))
            UserVerificationRequirement.REQUIRED else request.userVerification
        val requireUv = requireUv(verification, info, pin)
        val extensions = request.extensions.forCard(info, resident)
        val result = client.makeCredential(MakeCredentialRequest(
            request.rpId, request.rpName, request.user, clientData.hash, listOf(-7), resident,
            requireUv, request.excludeIds, extensions), pin, info)
        try {
            val auth = validateAuth(result.authData, request.rpId, requireUv)
            val id = auth.credentialId ?: malformed()
            if (request.excludeIds.any { it.contentEquals(id) }) malformed()
            val key = auth.publicKey ?: malformed()
            if (key[3].number() != -7L || key[-4] != null) malformed()
            FidoDiagnostics.publicKey(key)
            if (result.enterpriseAttestation == true) malformed()
            validateCreateExtensionOutput(auth, extensions)
            val extensionOutputs = ClientExtensionOutputs(
                residentKey = resident.takeIf { request.extensions.credProps },
                prfEnabled = (auth.extensions?.get("hmac-secret")?.bool() ?: false).takeIf { request.extensions.prf },
                prfRequested = request.extensions.prf,
                largeBlobSupported = (result.largeBlobKey != null).takeIf { request.extensions.largeBlobSupport != null },
                largeBlobRequested = request.extensions.largeBlobSupport != null,
                uvm = uvmOutput(auth, extensions.uvm))
            result.largeBlobKey?.fill(0)
            if (request.extensions.largeBlobSupport == "required" && extensionOutputs.largeBlobSupported != true)
                throw CardFailure(FailureCode.UNSUPPORTED)
            val verified = FidoDiagnostics.verifyRegistration(result, request.rpId, clientData.hash, requireUv)
            if (verified.signatureValid == false) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
            val output = when (request.attestation) {
                AttestationPreference.NONE, AttestationPreference.INDIRECT -> {
                    val privateData = result.authData.copyOf()
                    privateData.fill(0, 37, 53)
                    result.copy(format = "none", authData = privateData, statement = cborMap())
                }
                AttestationPreference.DIRECT -> result
            }
            CreatedCredential(output.copy(largeBlobKey = null), id.copyOf(), resident, extensionOutputs)
        } finally { result.largeBlobKey?.fill(0) }
    } finally { pin?.fill('\u0000'); request.extensions.close() }

    fun get(session: CardSession, request: ParsedGet, clientData: ClientData,
        pin: CharArray? = null): List<ProvidedAssertion> = try {
        validateClientData(clientData)
        val client = FidoClient(session)
        client.select()
        get(client, client.getInfo(), request, clientData, pin)
    } finally { pin?.fill('\u0000'); request.extensions.close() }

    internal fun get(client: FidoClient, info: FidoInfo, request: ParsedGet, clientData: ClientData,
        pin: CharArray?): List<ProvidedAssertion> {
        val completed = mutableListOf<ProvidedAssertion>()
        var results: List<Assertion> = emptyList()
        try {
            validateClientData(clientData)
            val input = request.extensions
            val effectiveInputs = if (request.allowIds.isEmpty()) listOf(input.prfInputs)
                else request.allowIds.map(input::inputsFor)
            val initialInputs = effectiveInputs.first()
            var prfInputs = initialInputs
            val supportsPrf = "hmac-secret" in info.extensions
            val requireUv = requireUv(if (info.options["alwaysUv"] == true ||
                (supportsPrf && effectiveInputs.any { it != null }))
                UserVerificationRequirement.REQUIRED else request.userVerification, info, pin)
            var allowIds = request.allowIds
            var selected: Assertion? = null
            if (supportsPrf && effectiveInputs.any { if (initialInputs == null) it != null else !initialInputs.sameAs(it) }) {
                val preflight = client.getAssertion(GetAssertionRequest(request.rpId, clientData.hash,
                    allowIds, requireUv = true, userPresence = false), pin?.copyOf(), info).singleOrNull() ?: malformed()
                val auth = validateAuth(preflight.authData, request.rpId, requireUv = true, requireUp = false)
                val builtInUv = pin == null && info.options["uv"] == true
                if ((auth.userPresent && !builtInUv) || auth.credentialId != null || auth.extensions?.value?.isNotEmpty() == true ||
                    allowIds.none { it.contentEquals(preflight.credentialId) } ||
                    (preflight.user != null && preflight.user.id.size !in 1..64)) malformed()
                validateSignature(preflight.signature)
                selected = preflight
                allowIds = listOf(preflight.credentialId)
                prfInputs = input.inputsFor(preflight.credentialId)
            }
            val extensions = AssertionExtensions(
                input.getCredBlob && "credBlob" in info.extensions,
                prfInputs?.takeIf { supportsPrf }?.salts(),
                (input.largeBlobRead || input.largeBlobWrite != null) && info.supportsLargeBlob(),
                input.uvm && "uvm" in info.extensions)
            results = client.getAssertion(GetAssertionRequest(request.rpId, clientData.hash,
                allowIds, requireUv, extensions), pin?.copyOf(), info)
            if (selected != null) {
                val final = results.singleOrNull() ?: malformed()
                if (!final.credentialId.contentEquals(selected.credentialId) ||
                    (selected.user != null && final.user != null && !selected.user.id.contentEquals(final.user.id))) malformed()
            }
            val seen = mutableSetOf<Cbor.Bytes>()
            // Validate every account before issuing any large-blob write.
            for (result in results) {
                val auth = validateAuth(result.authData, request.rpId, requireUv)
                if (auth.credentialId != null || result.credentialId.size !in 1..4096 ||
                    !seen.add(Cbor.Bytes(result.credentialId)) ||
                    (request.allowIds.isNotEmpty() && request.allowIds.none { it.contentEquals(result.credentialId) }) ||
                    (request.allowIds.isEmpty() && result.user == null) ||
                    (result.user != null && result.user.id.size !in 1..64)) malformed()
                validateSignature(result.signature)
                validateAssertionExtensionOutput(auth, extensions, info)
            }
            for (result in results) {
                var blob: ByteArray? = null
                try {
                    if (input.largeBlobRead && result.largeBlobKey != null) {
                        try {
                            blob = client.readLargeBlob(result.largeBlobKey.copyOf())
                            if (blob != null && blob.size > MAX_PROVIDER_BLOB) { blob.fill(0); blob = null }
                        } catch (failure: CardFailure) {
                            if (failure.code == FailureCode.CANCELLED) throw failure
                        } catch (failure: TransportException) {
                            if (failure.cancelled) throw failure
                        } catch (_: java.security.GeneralSecurityException) {
                            blob = null
                        } catch (_: IllegalArgumentException) {
                            blob = null
                        }
                    }
                    val written = if (input.largeBlobWrite == null) null else if (result.largeBlobKey == null) false else {
                        try {
                            client.writeLargeBlob(pin?.copyOf(), result.largeBlobKey.copyOf(), input.largeBlobWrite.copyOf())
                            true
                        } catch (failure: CardFailure) {
                            if (failure.code !in setOf(FailureCode.CAPACITY_EXCEEDED, FailureCode.UNSUPPORTED,
                                    FailureCode.AUTHORIZATION_DENIED, FailureCode.PIN_INVALID, FailureCode.PIN_BLOCKED)) throw failure
                            false
                        } catch (failure: TransportException) {
                            if (failure.cancelled || failure.dispatch != Dispatch.NOT_DISPATCHED) throw failure
                            false
                        }
                    }
                    val outputs = ClientExtensionOutputs(prfRequested = input.prf,
                        prfResult = result.hmacSecret?.copyOf(),
                        largeBlobRequested = input.largeBlobRead || input.largeBlobWrite != null,
                        largeBlob = blob, largeBlobWritten = written,
                        uvm = uvmOutput(AuthenticatorData.parse(result.authData), extensions.uvm))
                    completed += ProvidedAssertion(result.copy(hmacSecret = null, largeBlobKey = null), outputs)
                    blob = null
                } finally { blob?.fill(0) }
            }
            return completed
        } catch (failure: Exception) {
            completed.forEach { it.close() }
            throw failure
        } finally {
            results.forEach { it.hmacSecret?.fill(0); it.largeBlobKey?.fill(0) }
            pin?.fill('\u0000')
            request.extensions.close()
        }
    }

    private fun validateCreateExtensionOutput(auth: AuthenticatorData, request: MakeExtensions) {
        auth.extensions?.get("credBlob")?.let { if (request.credentialBlob == null) malformed(); it.bool() }
        auth.extensions?.get("hmac-secret")?.let { if (!request.hmacSecret) malformed(); it.bool() }
        auth.extensions?.get("minPinLength")?.let { if (!request.minPinLength) malformed(); it.count(63) }
        if (auth.extensions?.get("largeBlobKey") != null) malformed()
    }

    private fun validateAssertionExtensionOutput(auth: AuthenticatorData, request: AssertionExtensions, info: FidoInfo) {
        auth.extensions?.get("credBlob")?.let {
            if (!request.credentialBlob || it.bytes().size > (info.maxCredentialBlobLength ?: 0)) malformed()
        }
        if (auth.extensions?.get("minPinLength") != null || auth.extensions?.get("largeBlobKey") != null ||
            auth.extensions?.get("credProtect") != null) malformed()
        uvmOutput(auth, request.uvm)
    }

    private fun requireUv(requirement: UserVerificationRequirement, info: FidoInfo, pin: CharArray?): Boolean {
        val available = pin != null || info.options["uv"] == true
        if (requirement == UserVerificationRequirement.REQUIRED && !available)
            throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        return pin != null || requirement == UserVerificationRequirement.REQUIRED ||
            (requirement == UserVerificationRequirement.PREFERRED && available)
    }

    private fun validateAuth(data: ByteArray, rpId: String, requireUv: Boolean, requireUp: Boolean = true): AuthenticatorData {
        val auth = AuthenticatorData.parse(data)
        if (!auth.rpIdHash.contentEquals(sha256(rpId.toByteArray(Charsets.UTF_8))) ||
            (requireUp && !auth.userPresent) || (requireUv && !auth.userVerified))
            throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        if (auth.flags and 0x18 != 0) malformed()
        return auth
    }

    private fun validateSignature(signature: ByteArray) {
        if (signature.size !in 8..72 || signature[0] != 0x30.toByte() ||
            signature[1].toInt() != signature.size - 2) malformed()
        var offset = 2
        val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        repeat(2) {
            if (offset + 2 > signature.size || signature[offset++] != 2.toByte()) malformed()
            val length = signature[offset++].toInt() and 255
            if (length !in 1..33 || offset + length > signature.size || signature[offset].toInt() < 0 ||
                (length > 1 && signature[offset] == 0.toByte() && signature[offset + 1].toInt() >= 0)) malformed()
            val scalar = BigInteger(signature.copyOfRange(offset, offset + length))
            if (scalar.signum() <= 0 || scalar >= order) malformed()
            offset += length
        }
        if (offset != signature.size) malformed()
    }
}

internal fun validateClientData(data: ClientData) {
    require(data.hash.size == 32)
    if (!data.browserDelegated) require(data.json.isNotEmpty() && sha256(data.json).contentEquals(data.hash))
}
