package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class ProviderExtensionsTest {
    private val createJson = """{"rp":{"id":"cardkey.example","name":"CardKey"},"user":{"id":"AQ","name":"account","displayName":"Account"},"challenge":"AQ","pubKeyCredParams":[{"type":"public-key","alg":-7}],"authenticatorSelection":{"residentKey":"required"}}"""
    private val getJson = """{"rpId":"cardkey.example","challenge":"AQ","allowCredentials":[{"type":"public-key","id":"AQ"}]}"""
    private fun create(ext: String) = RequestParser.parseCreate(createJson.dropLast(1) + ",\"extensions\":$ext}")
    private fun get(ext: String, json: String = getJson) = RequestParser.parseGet(json.dropLast(1) + ",\"extensions\":$ext}")
    private val data = ClientData(byteArrayOf(1), sha256(byteArrayOf(1)), false)
    private val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private val publicKey = (pair.public as ECPublicKey).let { cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(it.w.affineX).cbor(), -3 to fixed32(it.w.affineY).cbor()) }
    private fun sign(value: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(value); sign() }
    private fun assertion(id: ByteArray = byteArrayOf(1), extensions: Cbor.Map? = null, key: ByteArray? = null, count: Int? = null, uv: Boolean = false): Cbor.Map {
        val auth = sha256("cardkey.example".toByteArray()) + byteArrayOf(((if (extensions == null) 1 else 0x81) or (if (uv) 4 else 0)).toByte(), 0, 0, 0, 1) +
            (extensions?.let(CborCodec::encode) ?: byteArrayOf())
        return cborMap(1 to descriptor(id), 2 to auth.cbor(), 3 to sign(auth + data.hash).cbor(),
            4 to FidoUser(id, "Account", "Account").encode(), 5 to count?.cbor(), 7 to key?.cbor())
    }
    private fun make(extensions: Cbor.Map? = null, key: ByteArray? = null): Cbor.Map {
        val auth = sha256("cardkey.example".toByteArray()) + byteArrayOf(if (extensions == null) 0x41 else 0xc1.toByte(), 0, 0, 0, 1) +
            ByteArray(16) + byteArrayOf(0, 1, 1) + CborCodec.encode(publicKey) + (extensions?.let(CborCodec::encode) ?: byteArrayOf())
        return cborMap(1 to "packed".cbor(), 2 to auth.cbor(), 3 to textMap("alg" to (-7).cbor(), "sig" to sign(auth + data.hash).cbor()), 5 to key?.cbor())
    }
    private fun performGet(channel: FidoChannel, request: ParsedGet) = CardSession(channel).run { CardCredentialOperation.get(it, request, data) }
    private fun performCreate(channel: FidoChannel, request: ParsedCreate) = CardSession(channel).run { CardCredentialOperation.create(it, request, data) }

    @Test fun largeBlobRecommendationRemainsResidentOnlyAndRequiredStillRejectsNonresident() {
        val capabilities = FidoInfo(listOf("FIDO_2_1"), setOf("largeBlobKey"), mapOf("rk" to true, "largeBlobs" to true),
            listOf(2), 4, null, cborMap(), null, null)
        val preferred = create("""{"largeBlob":{"support":"preferred"}}""").extensions
        assertFalse(preferred.forCard(capabilities, resident = false).largeBlobKey)
        assertTrue(preferred.forCard(capabilities, resident = true).largeBlobKey)
        val required = create("""{"largeBlob":{"support":"required"}}""").extensions
        assertThrows(CardFailure::class.java) { required.forCard(capabilities, resident = false) }
        assertTrue(required.forCard(capabilities, resident = true).largeBlobKey)
    }

    @Test fun prfSaltsMatchIndependentW3cVectorsAndCredentialOverrideWins() {
        val first = "576562417574686e20505246207465737420766563746f727302".hexBytes()
        val second = "576562417574686e20505246207465737420766563746f727303".hexBytes()
        assertEquals("527413ebb48293772df30f031c5ac4650c7de14bf9498671ae163447b6a772b3d68ac03329a10ee5e0ec834492bb9a96a0e547baf563bf78ccbe8789b22e776b",
            PrfInputs(first, second).salts().hex())
        val parsed = get("""{"prf":{"eval":{"first":"Ag"},"evalByCredential":{"AQ":{"first":"Aw","second":""}}}}""")
        assertArrayEquals(byteArrayOf(3), parsed.extensions.inputsFor(byteArrayOf(1))!!.first)
        assertArrayEquals(byteArrayOf(), parsed.extensions.inputsFor(byteArrayOf(1))!!.second)
    }

    @Test fun rejectsMalformedAndUnsupportedInputsBeforeIo() {
        listOf("""{"credProps":"yes"}""", """{"credentialProtectionPolicy":"wrong"}""",
            """{"enforceCredentialProtectionPolicy":true}""", """{"prf":{"eval":{"second":"AA"}}}""",
            """{"prf":{"evalByCredential":{}}}""", """{"largeBlob":{"read":false}}""",
            """{"credBlob":"AQ="}""").forEach { assertThrows(ProviderRequestException::class.java) { create(it) } }
        listOf("""{"largeBlob":{"support":"required"}}""", """{"largeBlob":{"read":false,"write":""}}""",
            """{"prf":{"evalByCredential":{"Ag":{"first":"AQ"}}}}""", """{"prf":{"eval":{"first":1}}}""",
            """{"getCredBlob":1}""").forEach { assertThrows(ProviderRequestException::class.java) { get(it) } }
        val noAllow = """{"rpId":"cardkey.example","challenge":"AQ"}"""
        assertThrows(ProviderRequestException::class.java) { get("""{"largeBlob":{"write":"AA"}}""", noAllow) }
        val multi = getJson.replace("}]", "},{\"type\":\"public-key\",\"id\":\"Ag\"}]")
        assertNotNull(get("""{"prf":{"eval":{"first":"AQ"},"evalByCredential":{"Ag":{"first":"Ag"}}}}""", multi))
        assertNotNull(get("""{"prf":{"eval":{"first":"AQ"},"evalByCredential":{"Ag":{"first":"AQ"}}}}""", multi))
    }

    @Test fun requiredCapabilitiesStopBeforeMakeAndOptionalCapabilitiesDoNotInventOutputs() {
        for (ext in listOf("""{"largeBlob":{"support":"required"}}""",
            """{"credentialProtectionPolicy":"userVerificationRequired","enforceCredentialProtectionPolicy":true}""")) {
            val channel = FidoChannel { code, _ -> assertEquals(4, code); success(info(mapOf("rk" to true))) }
            assertEquals(FailureCode.UNSUPPORTED, (performCreate(channel, create(ext)) as OperationResult.Failed).code)
            assertFalse(channel.commands.any { it.first == 1 })
        }
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("rk" to true)))
            1 -> { assertNull(map!![6]); success(make()) }
            else -> error("unexpected")
        } }
        val result = (performCreate(channel, create("""{"credProps":true,"credBlob":"AA","uvm":true,"prf":{},"minPinLength":true,"largeBlob":{}}""")) as OperationResult.Completed).value
        val envelope = JsonParser.parseString(ResponseEncoder.create(result, data)).asJsonObject
        assertEquals("cross-platform", envelope["authenticatorAttachment"].asString)
        assertEquals(listOf("nfc", "usb"), envelope["response"].asJsonObject["transports"].asJsonArray.map { it.asString })
        val output = envelope["clientExtensionResults"].asJsonObject
        assertTrue(output["credProps"].asJsonObject["rk"].asBoolean)
        assertFalse(output["prf"].asJsonObject["enabled"].asBoolean)
        assertFalse(output["largeBlob"].asJsonObject["supported"].asBoolean)
        assertEquals(setOf("credProps", "prf", "largeBlob"), output.keySet())
    }

    @Test fun createTranslatesSupportedExtensionsAndPreservesSignedOnlyOutputs() {
        val outputs = textMap("credProtect" to 3.cbor(), "credBlob" to true.cbor(), "hmac-secret" to true.cbor(),
            "minPinLength" to 6.cbor(), "uvm" to Cbor.Array(listOf(Cbor.Array(listOf(4.cbor(), 2.cbor(), 1.cbor())))))
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("rk" to true, "largeBlobs" to true), extensions = listOf("credProtect", "credBlob", "hmac-secret", "minPinLength", "uvm", "largeBlobKey"), extra = cborMap(15 to 32.cbor())))
            1 -> {
                val ext = map!![6].map()
                assertEquals(3L, ext["credProtect"].number())
                assertArrayEquals(byteArrayOf(1), ext["credBlob"].bytes())
                for (name in listOf("hmac-secret", "minPinLength", "uvm", "largeBlobKey")) assertTrue(ext[name].bool())
                success(make(outputs, ByteArray(32) { 9 }))
            }
            else -> error("unexpected")
        } }
        val request = create("""{"credProps":true,"credentialProtectionPolicy":"userVerificationRequired","enforceCredentialProtectionPolicy":true,"credBlob":"AQ","prf":{"eval":{"first":"AA"}},"minPinLength":true,"uvm":true,"largeBlob":{"support":"required"}}""")
        val result = (performCreate(channel, request) as OperationResult.Completed).value
        assertNull(result.result.largeBlobKey)
        assertEquals(outputs, AuthenticatorData.parse(result.result.authData).extensions)
        val json = JsonParser.parseString(ResponseEncoder.create(result, data)).asJsonObject["clientExtensionResults"].asJsonObject
        assertEquals(setOf("credProps", "prf", "largeBlob", "uvm"), json.keySet())
        assertTrue(json["prf"].asJsonObject["enabled"].asBoolean)
        assertFalse(json["prf"].asJsonObject.has("results"))
        assertTrue(json["largeBlob"].asJsonObject["supported"].asBoolean)
    }

    @Test fun prfBothProtocolsReturnDistinctAccountResultsWithoutLeakingRawKeys() {
        for (version in 1..2) {
            val server = FidoClientTest.PinServer(version)
            var shared: ByteArray? = null
            var index = 0
            val request = get("""{"prf":{"eval":{"first":"AQ","second":"Ag"}},"getCredBlob":true,"uvm":true}""",
                """{"rpId":"cardkey.example","challenge":"AQ"}""")
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("uv" to true), protocols = listOf(version), extensions = listOf("hmac-secret", "credBlob", "uvm"), extra = cborMap(15 to 32.cbor())))
                6 -> success(cborMap(1 to server.cose))
                2, 8 -> {
                    if (code == 2) {
                        assertTrue(map!![5].map()["uv"].bool())
                        val ext = map[4].map()["hmac-secret"].map()
                        shared = server.keyFor(ext[1].map())
                        assertArrayEquals(request.extensions.prfInputs!!.salts(), PinProtocol.decrypt(version, shared!!, ext[2].bytes()))
                    }
                    index++
                    val outputs = textMap("hmac-secret" to PinProtocol.encrypt(version, shared!!, ByteArray(64) { index.toByte() }).cbor(),
                        "credBlob" to byteArrayOf(9).cbor(), "uvm" to Cbor.Array(listOf(Cbor.Array(listOf(1.cbor(), 2.cbor(), 1.cbor())))))
                    success(assertion(byteArrayOf(index.toByte()), outputs, count = if (code == 2) 2 else null, uv = true))
                }
                else -> error("unexpected")
            } }
            val result = (performGet(channel, request) as OperationResult.Completed).value
            assertEquals(2, result.size)
            assertEquals(listOf(2, 8), channel.commands.map { it.first }.filter { it == 2 || it == 8 })
            result.forEachIndexed { i, credential ->
                assertNull(credential.assertion.hmacSecret)
                assertNull(credential.assertion.largeBlobKey)
                assertArrayEquals(ByteArray(64) { (i + 1).toByte() }, credential.extensions.prfResult)
                val envelope = JsonParser.parseString(ResponseEncoder.get(credential, data)).asJsonObject
                assertEquals("cross-platform", envelope["authenticatorAttachment"].asString)
                val json = envelope["clientExtensionResults"].asJsonObject
                assertEquals(setOf("prf", "uvm"), json.keySet())
                assertFalse(json["prf"].asJsonObject.has("enabled"))
                assertTrue(json["prf"].asJsonObject["results"].asJsonObject.has("second"))
                assertFalse(credential.extensions.toString().contains(encodeExtensionBytes(credential.extensions.prfResult!!)))
                credential.close()
                assertArrayEquals(ByteArray(64), credential.extensions.prfResult)
            }
            shared?.fill(0)
        }
    }

    @Test fun prfEvaluationRequiresUvEvenWhenRpDiscouragesIt() {
        val request = get("""{"prf":{"eval":{"first":"AQ"}}}""").copy(userVerification = UserVerificationRequirement.DISCOURAGED)
        val absent = FidoChannel { code, _ -> assertEquals(4, code); success(info(extensions = listOf("hmac-secret"))) }
        assertEquals(FailureCode.AUTHORIZATION_DENIED, (performGet(absent, request) as OperationResult.Failed).code)
        assertFalse(absent.commands.any { it.first == 2 || it.first == 6 })
    }

    @Test fun prfCipherOutputMatchesW3cIndependentVectorAndDebugStringsAreRedacted() {
        val shared = "0c63083de8170101d38bcf8bd72309568ddb4550867e23404b35d85712f7c20d8bc911ee23c06034cbc14290b9669bec07739053c5a416e313ef905c79955876".hexBytes()
        val ciphertext = "3bfaa48f7952330d63e35ff8cd5bca48d2a12823828915749287256ab146272f9fb437bf65691243c3f504bd7ea6d5e6".hexBytes()
        assertEquals("3c33e07d202c3b029cc21f1722767021bf27d595933b3d2b6a1b9d5dddc77fae", PinProtocol.decrypt(2, shared, ciphertext).hex())
        assertThrows(CardFailure::class.java) { PinProtocol.decrypt(2, shared, ciphertext.copyOf(31)) }
        assertEquals("AssertionExtensions(redacted)", AssertionExtensions(hmacSalts = shared).toString())
        assertEquals("MakeExtensions(redacted)", MakeExtensions(credentialBlob = shared).toString())
        assertEquals("Assertion(redacted)", Assertion(byteArrayOf(1), byteArrayOf(1), byteArrayOf(1), null, shared, shared).toString())
        assertEquals("MakeCredentialResult(redacted)", MakeCredentialResult("none", byteArrayOf(1), cborMap(), shared, false).toString())
    }

    @Test fun malformedAuthenticatorExtensionsFailAndDoNotWrite() {
        val bad = listOf(textMap("uvm" to Cbor.Array(listOf(Cbor.Array(listOf(1.cbor(), 2.cbor()))))),
            textMap("uvm" to Cbor.Array(listOf(Cbor.Array(listOf(1.cbor(), 65536.cbor(), 1.cbor()))))),
            textMap("credBlob" to ByteArray(33).cbor()), textMap("largeBlobKey" to ByteArray(32).cbor()))
        for (outputs in bad) {
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> success(info(extensions = listOf("uvm", "credBlob"), extra = cborMap(15 to 32.cbor())))
                2 -> success(assertion(extensions = outputs))
                else -> error("unexpected")
            } }
            assertEquals(FailureCode.MALFORMED_RESPONSE, (performGet(channel, get("""{"uvm":true,"getCredBlob":true}""")) as OperationResult.Failed).code)
        }
    }

    @Test fun largeBlobReadAndWriteUseCredentialBoundStorageAndNoKeyInResponse() {
        val key = ByteArray(32) { 7 }
        val expected = byteArrayOf(4, 5, 6)
        var stored = LargeBlobs.encode(listOf(LargeBlobs.encrypt(key, byteArrayOf(9))))
        fun channel() = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
            2 -> { assertTrue(map!![4].map()["largeBlobKey"].bool()); success(assertion(key = key.copyOf())) }
            12 -> if (map!![1] != null) success(cborMap(1 to stored.cbor())) else {
                assertEquals(0L, map[3].number()); stored = map[2].bytes(); success()
            }
            else -> error("unexpected")
        } }
        val written = (performGet(channel(), get("""{"largeBlob":{"write":"BAUG"}}""")) as OperationResult.Completed).value.single()
        assertEquals(true, written.extensions.largeBlobWritten)
        assertArrayEquals(expected, LargeBlobs.decrypt(key, LargeBlobs.decode(stored).single()))
        val read = (performGet(channel(), get("""{"largeBlob":{"read":true}}""")) as OperationResult.Completed).value.single()
        assertArrayEquals(expected, read.extensions.largeBlob)
        assertNull(read.assertion.largeBlobKey)
        val json = JsonParser.parseString(ResponseEncoder.get(read, data)).asJsonObject["clientExtensionResults"].asJsonObject
        assertEquals("BAUG", json["largeBlob"].asJsonObject["blob"].asString)
        read.close(); written.close()
    }

    @Test fun missingLargeBlobKeyReturnsEmptyReadOrFalseWriteAndUnknownWriteDoesNotReplay() {
        fun unsupported() = FidoChannel { code, _ -> when (code) { 4 -> success(info()); 2 -> success(assertion()); else -> error("unexpected") } }
        val read = (performGet(unsupported(), get("""{"largeBlob":{"read":true}}""")) as OperationResult.Completed).value.single()
        assertEquals("{}", read.extensions.json()["largeBlob"].toString())
        val write = (performGet(unsupported(), get("""{"largeBlob":{"write":""}}""")) as OperationResult.Completed).value.single()
        assertEquals(false, write.extensions.largeBlobWritten)
        val channel = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
            2 -> success(assertion(key = ByteArray(32)))
            12 -> if (map!![1] != null) success(cborMap(1 to LargeBlobs.encode(emptyList()).cbor())) else throw IOException("lost write response")
            else -> error("unexpected")
        } }
        val failure = performGet(channel, get("""{"largeBlob":{"write":"AA"}}""")) as OperationResult.Failed
        assertEquals(Effect.UNKNOWN, failure.effect)
        assertEquals(FailureCode.TRANSPORT_FAILURE, failure.code)
        assertEquals(1, channel.commands.count { it.first == 12 && it.second?.get(2) != null })
    }
    @Test fun absentLargeBlobSupportUsesRecommendationPreferredSemantics() {
        for (supported in listOf(false, true)) {
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("rk" to true, "largeBlobs" to supported),
                    extensions = if (supported) listOf("largeBlobKey") else emptyList()))
                1 -> {
                    assertEquals(supported, map!![6]?.map()?.get("largeBlobKey")?.bool() ?: false)
                    success(make(key = if (supported) ByteArray(32) else null))
                }
                else -> error("unexpected")
            } }
            val result = (performCreate(channel, create("""{"largeBlob":{}}""")) as OperationResult.Completed).value
            assertEquals(supported, result.extensions.largeBlobSupported)
            assertEquals(setOf("largeBlob"), result.extensions.json().keySet())
        }
    }

    @Test fun failedBlobReadsPreserveAssertionButCancellationStillAborts() {
        for (kind in listOf("corrupt", "oversize", "removed", "lost", "cancelled")) {
            val key = ByteArray(32) { 7 }
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
                2 -> success(assertion(key = key.copyOf()))
                12 -> when (kind) {
                    "corrupt" -> success(cborMap(1 to byteArrayOf(1, 2, 3).cbor()))
                    "oversize" -> success(cborMap(1 to LargeBlobs.encode(listOf(LargeBlobs.encrypt(key, ByteArray(MAX_PROVIDER_BLOB + 1)))).cbor()))
                    "removed" -> throw TransportException(Dispatch.NOT_DISPATCHED)
                    "lost" -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    else -> throw TransportException(Dispatch.NOT_DISPATCHED, cancelled = true)
                }
                else -> error("unexpected")
            } }
            val result = performGet(channel, get("""{"largeBlob":{"read":true}}"""))
            if (kind == "cancelled") assertEquals(FailureCode.CANCELLED, (result as OperationResult.Failed).code)
            else {
                val output = (result as OperationResult.Completed).value.single()
                assertEquals("{}", output.extensions.json()["largeBlob"].toString())
                assertArrayEquals(byteArrayOf(1), output.assertion.credentialId)
                assertEquals(Effect.APPLIED, result.effect)
                output.close()
            }
            assertEquals(1, channel.commands.count { it.first == 2 })
            assertEquals(1, channel.commands.count { it.first == 12 })
        }
    }

    @Test fun undispatchedBlobWriteReturnsFalseButReadbackAndCancellationCannotClaimFailure() {
        for (stage in listOf("before", "write", "readback", "readbackRejected", "cancelled", "unknown")) {
            var written = false
            val channel = FidoChannel { code, map -> when (code) {
                4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
                2 -> success(assertion(key = ByteArray(32)))
                12 -> if (map!![1] != null) {
                    if (written && stage == "readbackRejected") throw CardFailure(FailureCode.UNSUPPORTED)
                    if (stage == "before" || written) throw TransportException(Dispatch.NOT_DISPATCHED)
                    success(cborMap(1 to LargeBlobs.encode(emptyList()).cbor()))
                } else {
                    if (stage != "readback" && stage != "readbackRejected") throw TransportException(
                        if (stage == "unknown") Dispatch.RESPONSE_UNAVAILABLE else Dispatch.NOT_DISPATCHED,
                        cancelled = stage == "cancelled")
                    written = true
                    success()
                }
                else -> error("unexpected")
            } }
            val result = performGet(channel, get("""{"largeBlob":{"write":"AQ"}}"""))
            if (stage == "before" || stage == "write") {
                val output = (result as OperationResult.Completed).value.single()
                assertEquals(false, output.extensions.largeBlobWritten)
                output.close()
            } else {
                val failure = result as OperationResult.Failed
                assertEquals(when (stage) { "cancelled" -> FailureCode.CANCELLED
                    "readback", "readbackRejected" -> FailureCode.RECOVERY_REQUIRED
                    else -> FailureCode.TRANSPORT_FAILURE }, failure.code)
                if (stage == "unknown") assertEquals(Effect.UNKNOWN, failure.effect)
            }
            assertEquals(1, channel.commands.count { it.first == 2 })
            assertEquals(if (stage == "before") 0 else 1, channel.commands.count { it.first == 12 && it.second?.get(2) != null })
        }
    }

    @Test fun operationCompletionAndFailureWipeDecodedExtensionInputs() {
        for (fail in listOf(false, true)) {
            val creation = create("""{"credBlob":"AQID"}""")
            val channel = FidoChannel { code, _ -> when (code) {
                4 -> if (fail) throw CardFailure(FailureCode.CANCELLED) else success(info(mapOf("rk" to true)))
                1 -> success(make())
                else -> error("unexpected")
            } }
            performCreate(channel, creation)
            assertArrayEquals(ByteArray(3), creation.extensions.credBlob)
            val request = get("""{"prf":{"eval":{"first":"AQ","second":"Ag"},"evalByCredential":{"AQ":{"first":"Aw","second":"BA"}}},"largeBlob":{"write":"BQ"}}""")
            val getChannel = FidoChannel { code, _ -> when (code) {
                4 -> if (fail) throw CardFailure(FailureCode.CANCELLED) else success(info())
                2 -> success(assertion())
                else -> error("unexpected")
            } }
            performGet(getChannel, request)
            assertArrayEquals(byteArrayOf(0), request.extensions.prfInputs!!.first)
            assertArrayEquals(byteArrayOf(0), request.extensions.prfInputs.second)
            assertArrayEquals(byteArrayOf(0), request.extensions.prfByCredential["AQ"]!!.first)
            assertArrayEquals(byteArrayOf(0), request.extensions.prfByCredential["AQ"]!!.second)
            assertArrayEquals(byteArrayOf(0), request.extensions.largeBlobWrite)
        }
    }

}
