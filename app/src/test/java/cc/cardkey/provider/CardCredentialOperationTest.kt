package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
class CardCredentialOperationTest {
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private val pub = pair.public as ECPublicKey
    private val key = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(pub.w.affineX).cbor(), -3 to fixed32(pub.w.affineY).cbor())
    private val rp = "cardkey.example"
    private val id = byteArrayOf(1, 2, 3)
    private val user = FidoUser(byteArrayOf(9), "account", "Account")
    private val data = "{\"type\":\"webauthn.create\",\"challenge\":\"AQ\",\"origin\":\"https://cardkey.example\"}".toByteArray()
        .let { ClientData(it, sha256(it), false) }
    private val create = ParsedCreate(rp, "CardKey", user, "AQ", ResidentKeyRequirement.REQUIRED,
        UserVerificationRequirement.REQUIRED, emptyList(), AttestationPreference.NONE)
    private val get = ParsedGet(rp, "AQ", emptyList(), UserVerificationRequirement.REQUIRED)
    private fun sign(message: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private); update(message); sign()
    }
    private fun registration(flags: Int = 0x45, rpId: String = rp, publicKey: Cbor.Map = key): MakeCredentialResult {
        val auth = sha256(rpId.toByteArray()) + byteArrayOf(flags.toByte(), 0, 0, 0, 2) +
            ByteArray(16) { 7 } + byteArrayOf(0, 3) + id + CborCodec.encode(publicKey)
        return MakeCredentialResult("packed", auth,
            textMap("alg" to (-7).cbor(), "sig" to sign(auth + data.hash).cbor()), null, null)
    }
    private fun resultMap(result: MakeCredentialResult) = cborMap(1 to result.format.cbor(),
        2 to result.authData.cbor(), 3 to result.statement, 4 to result.enterpriseAttestation?.cbor(),
        5 to result.largeBlobKey?.cbor())
    private fun creation(result: MakeCredentialResult = registration(), options: Map<String, Boolean> = mapOf("rk" to true, "uv" to true)) =
        FidoChannel { command, _ -> when (command) {
            4 -> success(info(options))
            1 -> success(resultMap(result))
            else -> error("Unexpected command $command")
        } }
    private fun assertion(credential: ByteArray = id, flags: Int = 5, rpId: String = rp, account: FidoUser? = user): Assertion {
        val auth = sha256(rpId.toByteArray()) + byteArrayOf(flags.toByte(), 0, 0, 0, 3)
        return Assertion(credential, auth, sign(auth + data.hash), account, null, null)
    }
    private fun assertions(values: List<Assertion>): FidoChannel {
        var index = 0
        return FidoChannel { command, _ -> when (command) {
            4 -> success(info(mapOf("uv" to true)))
            2, 8 -> {
                val value = values[index++]
                success(cborMap(1 to descriptor(value.credentialId), 2 to value.authData.cbor(),
                    3 to value.signature.cbor(), 4 to value.user?.encode(),
                    5 to if (command == 2) values.size.cbor() else null))
            }
            else -> error("Unexpected command $command")
        } }
    }
    private fun createWith(channel: FidoChannel, request: ParsedCreate = create) =
        CardSession(channel).run { CardCredentialOperation.create(it, request, data) }
    private fun getWith(channel: FidoChannel, request: ParsedGet = get) =
        CardSession(channel).run { CardCredentialOperation.get(it, request, data) }

    @Test fun verifiesOriginalBeforeNonePrivacyTransform() {
        val original = registration()
        val channel = creation(original)
        val result = createWith(channel) as OperationResult.Completed
        val created = result.value
        assertEquals("none", created.result.format)
        assertTrue(created.result.statement.value.isEmpty())
        val expected = original.authData.copyOf().also { it.fill(0, 37, 53) }
        assertArrayEquals(expected, created.result.authData)
        assertArrayEquals(ByteArray(16) { 7 }, original.authData.copyOfRange(37, 53))
        assertEquals(0, created.result.authData[32].toInt() and 0x18)
        assertTrue(created.residentKey)
        assertEquals(1, channel.selectCount)
        assertTrue(channel.closed)
    }

    @Test fun privacyTransformPreservesSignedAuthenticatorExtensionBytes() {
        val original = registration()
        val auth = original.authData.copyOf().also { it[32] = 0xc5.toByte() } +
            CborCodec.encode(textMap("vendor.policy" to true.cbor()))
        val withExtension = original.copy(authData = auth, statement = textMap("alg" to (-7).cbor(),
            "sig" to sign(auth + data.hash).cbor()))
        val result = createWith(creation(withExtension)) as OperationResult.Completed
        val expected = auth.copyOf().also { it.fill(0, 37, 53) }
        assertArrayEquals(expected, result.value.result.authData)
        assertTrue(AuthenticatorData.parse(result.value.result.authData).extensions!!["vendor.policy"].bool())
    }

    @Test fun rejectsPrivateCoseMaterialInsteadOfExportingItInAttestation() {
        val invalidPublicKey = Cbor.Map(key.value + cborMap(-4 to ByteArray(32) { 1 }.cbor()).value)
        val result = createWith(creation(registration(publicKey = invalidPublicKey)))
        assertTrue(result is OperationResult.Failed)
        assertEquals(FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
    }

    @Test fun directPreservesOriginalWhileIndirectUsesNone() {
        val original = registration()
        val direct = createWith(creation(original), create.copy(attestation = AttestationPreference.DIRECT)) as OperationResult.Completed
        assertEquals(original.statement, direct.value.result.statement)
        assertArrayEquals(original.authData, direct.value.result.authData)
        assertEquals("packed", direct.value.result.format)
        val indirect = createWith(creation(original), create.copy(attestation = AttestationPreference.INDIRECT)) as OperationResult.Completed
        assertEquals("none", indirect.value.result.format)
    }

    @Test fun rejectsInvalidAttestationSignatureEvenWhenNoneRequested() {
        val original = registration()
        val bad = original.copy(statement = textMap("alg" to (-7).cbor(),
            "sig" to sign(original.authData + ByteArray(32)).cbor()))
        val result = createWith(creation(bad)) as OperationResult.Failed
        assertEquals(FailureCode.AUTHORIZATION_DENIED, result.code)
        assertTrue(result.writeAccepted)
    }

    @Test fun validatesBasicAttestationSignatureBeforePrivacyTransform() {
        val attestationPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val certificate = JcaX509v3CertificateBuilder(X500Name("CN=Synthetic attestation"), BigInteger.ONE,
            Date(0), Date(4102444800000L), X500Name("CN=Synthetic attestation"), attestationPair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(attestationPair.private)).encoded
        val original = registration()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(attestationPair.private); update(original.authData + data.hash); sign()
        }
        val basic = original.copy(statement = textMap("alg" to (-7).cbor(), "sig" to signature.cbor(),
            "x5c" to Cbor.Array(listOf(certificate.cbor()))))
        val result = createWith(creation(basic)) as OperationResult.Completed
        assertEquals("none", result.value.result.format)
        val corrupted = basic.copy(statement = textMap("alg" to (-7).cbor(),
            "sig" to sign(original.authData + data.hash).cbor(), "x5c" to Cbor.Array(listOf(certificate.cbor()))))
        assertTrue(createWith(creation(corrupted)) is OperationResult.Failed)
    }

    @Test fun acceptsGenuineNoneOnlyWithValidCredentialPublicKey() {
        val original = registration().copy(format = "none", statement = cborMap())
        assertTrue(createWith(creation(original)) is OperationResult.Completed)
        val invalid = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
            -2 to ByteArray(32).cbor(), -3 to ByteArray(32).cbor())
        val bad = registration(publicKey = invalid).copy(format = "none", statement = cborMap())
        assertTrue(createWith(creation(bad)) is OperationResult.Failed)
    }

    @Test fun rejectsWrongRpMissingUvWrongAlgorithmAndBackupFlags() {
        val cases = listOf(registration(rpId = "other.example"), registration(flags = 0x41),
            registration(flags = 0x44), registration(flags = 0x4d), registration(flags = 0x5d),
            registration(publicKey = Cbor.Map(key.value + (3.cbor() to (-257).cbor()))))
        for (bad in cases) assertTrue(createWith(creation(bad)) is OperationResult.Failed)
    }

    @Test fun rejectsEnterpriseAttestationAndUnrequestedLargeBlobKey() {
        assertTrue(createWith(creation(registration().copy(enterpriseAttestation = true))) is OperationResult.Failed)
        assertTrue(createWith(creation(registration().copy(largeBlobKey = ByteArray(32)))) is OperationResult.Failed)
    }

    @Test fun exclusionListReachesCardAndExcludedReturnedIdIsRejected() {
        val excluded = listOf(id)
        val channel = creation()
        assertTrue(createWith(channel, create.copy(excludeIds = excluded)) is OperationResult.Failed)
        val make = channel.commands.single { it.first == 1 }.second!!
        assertArrayEquals(id, parseDescriptor(make[5].array().single()))
    }

    @Test fun cardExclusionFailureIsNotRetried() {
        val channel = FidoChannel { command, _ -> when (command) {
            4 -> success(info(mapOf("rk" to true, "uv" to true)))
            1 -> byteArrayOf(0x19)
            else -> error("Unexpected command")
        } }
        val result = createWith(channel) as OperationResult.Failed
        assertEquals(0x19, result.status)
        assertEquals(1, channel.commands.count { it.first == 1 })
    }

    @Test fun requiredCapabilitiesFailBeforeCreateAndPreferredCanFallBack() {
        val noResident = creation(options = mapOf("uv" to true))
        assertTrue(createWith(noResident) is OperationResult.Failed)
        assertFalse(noResident.commands.any { it.first == 1 })
        val noUv = creation(options = mapOf("rk" to true))
        assertTrue(createWith(noUv) is OperationResult.Failed)
        assertFalse(noUv.commands.any { it.first == 1 })
        val fallback = creation(registration(flags = 0x41), emptyMap())
        val response = createWith(fallback, create.copy(residentKey = ResidentKeyRequirement.PREFERRED,
            userVerification = UserVerificationRequirement.PREFERRED)) as OperationResult.Completed
        assertFalse(response.value.residentKey)
        val options = fallback.commands.single { it.first == 1 }.second!![7].map()
        assertFalse(options["rk"].bool())
        assertFalse(options["uv"].bool())
    }

    @Test fun nonresidentAllowListUsesNoCredentialManagementAndRpVerifiesUnchangedSignature() {
        val original = assertion(account = null)
        val channel = assertions(listOf(original))
        val result = getWith(channel, get.copy(allowIds = listOf(id))) as OperationResult.Completed
        val response = result.value.single().assertion
        assertArrayEquals(original.authData, response.authData)
        assertArrayEquals(original.signature, response.signature)
        assertTrue(FidoDiagnostics.verifyAssertion(response, key, rp, data.hash).signatureValid)
        assertFalse(FidoDiagnostics.verifyAssertion(response, key, rp, ByteArray(32)).signatureValid)
        assertEquals(listOf(4, 2), channel.commands.map { it.first })
    }

    @Test fun relyingPartyRejectsWrongChallengeSignatureWithoutProviderInventingVerification() {
        val original = assertion()
        val wrongChallenge = original.copy(signature = sign(original.authData + ByteArray(32)))
        val result = getWith(assertions(listOf(wrongChallenge))) as OperationResult.Completed
        assertArrayEquals(wrongChallenge.signature, result.value.single().assertion.signature)
        assertFalse(FidoDiagnostics.verifyAssertion(result.value.single().assertion, key, rp, data.hash).signatureValid)
    }

    @Test fun multipleAccountsAreRetrievedContiguouslyBeforeSelection() {
        val originals = listOf(assertion(), assertion(byteArrayOf(4, 5), account = user.copy(id = byteArrayOf(8))))
        val channel = assertions(originals)
        val result = getWith(channel) as OperationResult.Completed
        assertEquals(2, result.value.size)
        assertEquals(listOf(4, 2, 8), channel.commands.map { it.first })
        for (response in result.value.map { it.assertion }) assertTrue(FidoDiagnostics.verifyAssertion(response, key, rp, data.hash).signatureValid)
        assertEquals(1, channel.selectCount)
    }

    @Test fun rejectsAssertionBindingFlagsMissingUserAndMalformedDer() {
        val cases = listOf(assertion(rpId = "other.example"), assertion(flags = 1), assertion(flags = 4),
            assertion(flags = 13), assertion(flags = 21), assertion(account = null),
            assertion().copy(signature = byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 1)),
            assertion().copy(signature = byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, -1)))
        for (bad in cases) assertTrue(getWith(assertions(listOf(bad))) is OperationResult.Failed)
        assertTrue(getWith(assertions(listOf(assertion())), get.copy(allowIds = listOf(byteArrayOf(9)))) is OperationResult.Failed)
        assertTrue(getWith(assertions(listOf(assertion(), assertion()))) is OperationResult.Failed)
    }

    @Test fun unknownCreateOutcomeHasNoAutomaticReplay() {
        val channel = FidoChannel { command, _ -> when (command) {
            4 -> success(info(mapOf("rk" to true, "uv" to true)))
            1 -> throw IOException("Synthetic lost response")
            else -> error("Unexpected command")
        } }
        val result = createWith(channel) as OperationResult.Failed
        assertEquals(Effect.UNKNOWN, result.effect)
        assertEquals(FailureCode.TRANSPORT_FAILURE, result.code)
        assertEquals(1, channel.commands.count { it.first == 1 })
        assertTrue(channel.closed)
    }

    @Test fun getPreferredUvCanProceedWithoutUvAndRequiredStopsBeforeAssertion() {
        val original = assertion(flags = 1)
        fun channel() = FidoChannel { command, parameters -> when (command) {
            4 -> success(info())
            2 -> {
                assertFalse(parameters!![5].map()["uv"].bool())
                success(cborMap(1 to descriptor(id), 2 to original.authData.cbor(),
                    3 to original.signature.cbor(), 4 to user.encode()))
            }
            else -> error("Unexpected command")
        } }
        assertTrue(getWith(channel(), get.copy(userVerification = UserVerificationRequirement.PREFERRED)) is OperationResult.Completed)
        val required = channel()
        assertTrue(getWith(required) is OperationResult.Failed)
        assertFalse(required.commands.any { it.first == 2 })
    }

    @Test fun preferredUvWithUnavailablePinTokenRetainsSignedAllowListLoginWithoutProbeAuthentication() = runTest {
        for (version in listOf(1, 2)) {
            for (capability in listOf("absent", "disabled", "force-change")) {
                val options = if (capability == "absent") mapOf("clientPin" to true)
                    else mapOf("clientPin" to true, "pinUvAuthToken" to (capability == "force-change"))
                val original = assertion(flags = 1, account = null)
                val request = get.copy(allowIds = listOf(id), userVerification = UserVerificationRequirement.PREFERRED)
                val registry = RequestRegistry()
                val claim = registry.claim(registry.offer(OfferedRequest(ProviderRequestKind.GET, "{}", data.hash,
                    CallerSnapshot("synthetic.provider", setOf("certificate"), null), "fixture")))
                val runner = ProviderOperationRunner()
                var connects = 0
                val channel = FidoChannel { code, parameters -> when (code) {
                    4 -> success(info(options, protocols = listOf(version),
                        extra = if (capability == "force-change") cborMap(12 to true.cbor()) else cborMap()))
                    2 -> {
                        assertFalse(requireNotNull(parameters)[5].map()["uv"].bool())
                        assertArrayEquals(id, parseDescriptor(parameters[3].array().single()))
                        assertNull(parameters[6])
                        assertNull(parameters[7])
                        success(cborMap(1 to descriptor(id), 2 to original.authData.cbor(), 3 to original.signature.cbor()))
                    }
                    else -> error("Unavailable PIN path must not authenticate: $code")
                } }
                val result = async { runner.recognizeAndExecute(null, request, data, claim = claim,
                    onRecognized = { assertFalse(it.pinRequired) }) {
                    connects++
                    assertFalse(claim.reserveTransport())
                    channel
                } }
                runCurrent()
                assertEquals(listOf(4), channel.commands.map { it.first })
                assertFalse(result.isCompleted)
                assertNull(CardOperationGate.tryAcquire())
                assertTrue(runner.confirm(null))
                val completed = result.await() as OperationResult.Completed
                val provided = (completed.value as ProviderOutput.Assertions).values.single()
                assertArrayEquals(original.authData, provided.assertion.authData)
                assertArrayEquals(original.signature, provided.assertion.signature)
                assertTrue(FidoDiagnostics.verifyAssertion(provided.assertion, key, rp, data.hash, requireUv = false).signatureValid)
                val mandatoryUv = assertThrows(CardFailure::class.java) {
                    FidoDiagnostics.verifyAssertion(provided.assertion, key, rp, data.hash)
                }
                assertEquals(FailureCode.AUTHORIZATION_DENIED, mandatoryUv.code)
                assertEquals(listOf(4, 2), channel.commands.map { it.first })
                assertEquals(1, connects)
                assertEquals(1, channel.selectCount)
                assertTrue(channel.closed)
                provided.close()
                assertFalse(runner.confirm(null))
                assertEquals(FailureCode.CANCELLED, (runner.recognizeAndExecute(null, request, data,
                    claim = claim, onRecognized = { error("Repeated observation") }) { error("Reconnected") } as OperationResult.Failed).code)
                CardOperationGate.tryAcquire()!!.close()
            }
        }
    }

    @Test fun builtInUvWithoutPinRetainsSignedLoginWhenOptionalBlobWriteCannotAuthorize() = runTest {
        val original = assertion(account = null)
        val blobInput = byteArrayOf(9)
        val blobKey = ByteArray(32) { 7 }
        val request = get.copy(allowIds = listOf(id), extensions = GetExtensionInputs(largeBlobWrite = blobInput))
        val registry = RequestRegistry()
        val claim = registry.claim(registry.offer(OfferedRequest(ProviderRequestKind.GET, "{}", data.hash,
            CallerSnapshot("synthetic.provider", setOf("certificate"), null), "fixture")))
        val runner = ProviderOperationRunner()
        var connects = 0
        val channel = FidoChannel { code, parameters -> when (code) {
            4 -> success(info(mapOf("uv" to true, "largeBlobs" to true), extensions = listOf("largeBlobKey"),
                extra = cborMap(11 to 4096.cbor())))
            2 -> {
                assertTrue(requireNotNull(parameters)[5].map()["uv"].bool())
                assertTrue(parameters[4].map()["largeBlobKey"].bool())
                assertNull(parameters[6])
                assertNull(parameters[7])
                success(cborMap(1 to descriptor(id), 2 to original.authData.cbor(),
                    3 to original.signature.cbor(), 7 to blobKey.copyOf().cbor()))
            }
            12 -> {
                assertNotNull(requireNotNull(parameters)[1])
                assertNull(parameters[2])
                success(cborMap(1 to LargeBlobs.encode(emptyList()).cbor()))
            }
            else -> error("No PIN authorization or optional write may be dispatched: $code")
        } }
        val result = async { runner.recognizeAndExecute(null, request, data, claim = claim,
            onRecognized = { assertFalse(it.pinRequired) }) { connects++; channel } }
        runCurrent()
        assertEquals(listOf(4), channel.commands.map { it.first })
        assertFalse(result.isCompleted)
        assertNull(CardOperationGate.tryAcquire())
        assertTrue(runner.confirm(null))
        val completed = result.await() as OperationResult.Completed
        val provided = (completed.value as ProviderOutput.Assertions).values.single()
        assertEquals(false, provided.extensions.largeBlobWritten)
        assertArrayEquals(original.authData, provided.assertion.authData)
        assertArrayEquals(original.signature, provided.assertion.signature)
        assertTrue(FidoDiagnostics.verifyAssertion(provided.assertion, key, rp, data.hash).signatureValid)
        val response = com.google.gson.JsonParser.parseString(ResponseEncoder.get(provided, data)).asJsonObject
        assertFalse(response["clientExtensionResults"].asJsonObject["largeBlob"].asJsonObject["written"].asBoolean)
        assertNull(provided.assertion.largeBlobKey)
        assertArrayEquals(byteArrayOf(0), blobInput)
        assertEquals(listOf(4, 2, 4, 12), channel.commands.map { it.first })
        assertEquals(1, connects)
        assertEquals(1, channel.selectCount)
        assertTrue(channel.closed)
        provided.close()
        assertFalse(runner.confirm(null))
        assertEquals(FailureCode.CANCELLED, (runner.recognizeAndExecute(null, request, data,
            claim = claim, onRecognized = { error("Repeated observation") }) { error("Reconnected") } as OperationResult.Failed).code)
        CardOperationGate.tryAcquire()!!.close()
    }

    @Test fun nativeHashMismatchIsRejectedBeforeCardIoAndPinIsCleared() {
        val channel = creation()
        val pin = charArrayOf('1', '2', '3', '4')
        val result = CardSession(channel).run {
            CardCredentialOperation.create(it, create, data.copy(hash = ByteArray(32)), pin)
        }
        assertTrue(result is OperationResult.Failed)
        assertEquals(0, channel.selectCount)
        assertTrue(pin.all { it == '\u0000' })
    }
}
