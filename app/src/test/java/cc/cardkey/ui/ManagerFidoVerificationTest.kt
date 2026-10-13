package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ManagerFidoVerificationTest(private val wire: CardWire) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun wires() = CardWire.entries.map { arrayOf(it) }
    }
    private val manager = ManagerOperations()
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private val publicKey = pair.public as ECPublicKey
    private val point = byteArrayOf(4) + fixed32(publicKey.w.affineX) + fixed32(publicKey.w.affineY)
    private val key = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(publicKey.w.affineX).cbor(), -3 to fixed32(publicKey.w.affineY).cbor())
    private val rpHash = sha256("cardkey.example".toByteArray())
    private val id = byteArrayOf(1, 2, 3)
    private val otherId = byteArrayOf(4, 5, 6)
    private val certificate = X500Name("CN=Synthetic manager diagnostic").let { name ->
        JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)).encoded
    }
    private fun sign(message: ByteArray, valid: Boolean) = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(if (valid) message else message + byteArrayOf(0))
        sign()
    }
    private fun execute(operation: ManagerOperation, channel: CardChannel) =
        CardSession(channel).run { manager.execute(it, OperationInput(operation)) }

    private fun registration(credentialId: ByteArray = id, valid: Boolean = true, none: Boolean = false, blobKey: ByteArray? = null, enterprise: Int? = null, protection: Int? = null, uvm: Cbor? = null): ManagerTestChannel =
        ManagerTestChannel(wire) { code, parameters -> when (code) {
            4 -> success(info(mapOf("ep" to true, "rk" to true), extensions = listOf("largeBlobKey", "credProtect", "uvm")))
            1 -> {
                assertEquals(enterprise?.toLong(), parameters!![10]?.number())
                assertEquals(protection?.toLong(), parameters[6]?.map()?.get("credProtect")?.number())
                val auth = rpHash + byteArrayOf((if (protection == null && uvm == null) 0x41 else 0xc1).toByte(), 0, 0, 0, 0) + ByteArray(16) +
                    byteArrayOf(0, credentialId.size.toByte()) + credentialId + CborCodec.encode(key) +
                    (if (protection != null || uvm != null) CborCodec.encode(textMap("credProtect" to protection?.cbor(), "uvm" to uvm)) else byteArrayOf())
                success(cborMap(1 to (if (none) "none" else "packed").cbor(), 2 to auth.cbor(),
                    3 to if (none) textMap() else textMap("alg" to (-7).cbor(),
                        "sig" to sign(auth + parameters[1].bytes(), valid).cbor()), 5 to blobKey?.cbor()))
            }
            else -> error("Unexpected CTAP command $code")
        } }

    private fun assertion(valid: Boolean, counter: Int, uvm: Cbor? = null, credentialBlob: ByteArray? = null): ManagerTestChannel = ManagerTestChannel(wire) { code, parameters -> when (code) {
        4 -> success(info(extensions = listOf("uvm", "credBlob")))
        2 -> {
            assertArrayEquals(id, parameters!![3].array().single().map()["id"].bytes())
            val auth = rpHash + byteArrayOf((if (uvm == null && credentialBlob == null) 1 else 0x81).toByte(), 0, 0, 0, counter.toByte()) +
                (if (uvm != null || credentialBlob != null) CborCodec.encode(textMap("uvm" to uvm, "credBlob" to credentialBlob?.cbor())) else byteArrayOf())
            success(cborMap(1 to textMap("type" to "public-key".cbor(), "id" to id.cbor()),
                2 to auth.cbor(), 3 to sign(auth + parameters[2].bytes(), valid).cbor()))
        }
        else -> error("Unexpected CTAP command $code")
    } }

    private fun blobProof(params: Cbor.Map, valid: Boolean = true, returnedKey: ByteArray = ByteArray(32) { 7 }): ByteArray {
        assertFalse(params[5].map()["up"].bool())
        assertTrue(params[5].map()["uv"].bool())
        assertArrayEquals(id, params[3].array().single().map()["id"].bytes())
        val auth = rpHash + byteArrayOf(4, 0, 0, 0, 1)
        return success(cborMap(1 to textMap("type" to "public-key".cbor(), "id" to id.cbor()),
            2 to auth.cbor(), 3 to sign(auth + params[2].bytes(), valid).cbor(), 7 to returnedKey.cbor()))
    }

    private class U2fChannel(override val wire: CardWire, val respond: (ByteArray) -> ByteArray) : CardChannel {
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 7609
        override val supportsCtap1 get() = wire == CardWire.CTAP
        var requests = 0
        var closed = false
        override fun transmit(command: ByteArray): ByteArray {
            check(!closed && wire == CardWire.APDU)
            if (command[1].toInt() and 255 == 0xa4) return "U2F_V2".toByteArray() + byteArrayOf(0x90.toByte(), 0)
            requests++
            return respond(command) + byteArrayOf(0x90.toByte(), 0)
        }
        override fun exchangeCtap1(command: ByteArray): ByteArray {
            check(!closed && wire == CardWire.CTAP)
            assertEquals(0, command[4].toInt())
            assertEquals(command.size - 9, ((command[5].toInt() and 255) shl 8) or (command[6].toInt() and 255))
            requests++
            return respond(command) + byteArrayOf(0x90.toByte(), 0)
        }
        override fun close() { closed = true }
    }
    private fun u2fRegistration(credentialId: ByteArray = id, valid: Boolean = true) = U2fChannel(wire) { command ->
        assertEquals(1, command[1].toInt())
        val offset = if (wire == CardWire.CTAP) 7 else 5
        val challenge = command.copyOfRange(offset, offset + 32)
        val application = command.copyOfRange(offset + 32, offset + 64)
        byteArrayOf(5) + point + byteArrayOf(credentialId.size.toByte()) + credentialId + certificate +
            sign(byteArrayOf(0) + application + challenge + credentialId + point, valid)
    }
    private fun u2fAssertion(valid: Boolean, counter: Int) = U2fChannel(wire) { command ->
        assertEquals(2, command[1].toInt())
        val offset = if (wire == CardWire.CTAP) 7 else 5
        assertArrayEquals(id, command.copyOfRange(offset + 65, command.size - if (wire == CardWire.CTAP) 2 else 1))
        val presenceCounter = byteArrayOf(1, 0, 0, 0, counter.toByte())
        presenceCounter + sign(command.copyOfRange(offset + 32, offset + 64) + presenceCounter + command.copyOfRange(offset, offset + 32), valid)
    }
    private fun rejected(result: OperationResult<*>) {
        assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED, writeAccepted = true), result)
        assertFalse(ConfirmationPolicy.resultMessages(result).contains(cc.cardkey.R.string.operation_completed))
    }

    @Test fun invalidCtapRegistrationDoesNotReplaceVerifiedCredential() {
        assertTrue(execute(ManagerOperation.FIDO_REGISTER, registration()) is OperationResult.Completed)
        val invalid = registration(otherId, valid = false)
        rejected(execute(ManagerOperation.FIDO_REGISTER, invalid))
        assertEquals(listOf(4, 1), invalid.commands.map { it.first }); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.FIDO_ASSERT, assertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun invalidCtapAssertionDoesNotAdvanceVerifiedCounter() {
        execute(ManagerOperation.FIDO_REGISTER, registration())
        val invalid = assertion(false, 9)
        rejected(execute(ManagerOperation.FIDO_ASSERT, invalid))
        assertEquals(listOf(4, 2), invalid.commands.map { it.first }); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.FIDO_ASSERT, assertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun invalidU2fRegistrationDoesNotReplaceVerifiedCredential() {
        assertTrue(execute(ManagerOperation.U2F_REGISTER, u2fRegistration()) is OperationResult.Completed)
        val invalid = u2fRegistration(otherId, false)
        rejected(execute(ManagerOperation.U2F_REGISTER, invalid))
        assertEquals(1, invalid.requests); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.U2F_ASSERT, u2fAssertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun invalidU2fAssertionDoesNotAdvanceVerifiedCounter() {
        execute(ManagerOperation.U2F_REGISTER, u2fRegistration())
        val invalid = u2fAssertion(false, 9)
        rejected(execute(ManagerOperation.U2F_ASSERT, invalid))
        assertEquals(1, invalid.requests); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.U2F_ASSERT, u2fAssertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun noneAttestationRetainsCredentialForVerifiedAssertion() {
        val result = execute(ManagerOperation.FIDO_REGISTER, registration(none = true)) as OperationResult.Completed
        assertEquals("NONE", result.value.rows.single { it.label == "证明" }.value)
        assertTrue(execute(ManagerOperation.FIDO_ASSERT, assertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun lostRegistrationResponseIsUnknownAndNotRetried() {
        val channel = ManagerTestChannel(wire) { code, _ -> when (code) {
            4 -> success(info())
            1 -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            else -> error("Unexpected command")
        } }
        assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
            dispatch = Dispatch.RESPONSE_UNAVAILABLE), execute(ManagerOperation.FIDO_REGISTER, channel))
        assertEquals(listOf(4, 1), channel.commands.map { it.first }); assertTrue(channel.closed)
        val untouched = assertion(true, 1)
        assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), execute(ManagerOperation.FIDO_ASSERT, untouched))
        assertTrue(untouched.commands.isEmpty())
    }
    @Test fun secretOutputsNeverBecomeOrdinaryRowsAndAreWipedOnSessionClear() {
        val input = OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true,
            makeExtensions = MakeExtensions(largeBlobKey = true), enterpriseMode = 2)
        val channel = registration(blobKey = ByteArray(32) { 7 }, enterprise = 2)
        val result = CardSession(channel).run { manager.execute(it, input) } as OperationResult.Completed
        assertFalse(result.value.rows.any { it.label == "largeBlobKey" || it.value == "07".repeat(32) })
        assertNull(result.value.export)
        assertFalse(result.value.toString().contains("07".repeat(32)))
        val output = result.value.secrets!!.blobKey!!
        assertArrayEquals(ByteArray(32) { 7 }, output)
        manager.clearSession()
        assertArrayEquals(ByteArray(32), output)
        val untouched = assertion(true, 1)
        assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), execute(ManagerOperation.FIDO_ASSERT, untouched))
    }
    @Test fun blobVerifiesCredentialOnCurrentSessionAndNeverReusesCachedKey() {
        val input = OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true, makeExtensions = MakeExtensions(largeBlobKey = true))
        CardSession(registration(blobKey = ByteArray(32) { 7 })).run { manager.execute(it, input) }
        val encoded = LargeBlobs.encode(listOf(LargeBlobs.encrypt(ByteArray(32) { 7 }, byteArrayOf())))
        val channel = ManagerTestChannel(wire) { code, params -> when(code) {
            4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
            2 -> blobProof(params!!)
            12 -> success(cborMap(1 to encoded.cbor()))
            else -> error("Unexpected command")
        } }
        val result = execute(ManagerOperation.FIDO_BLOB_READ, channel) as OperationResult.Completed
        assertArrayEquals(byteArrayOf(), result.value.export)
        assertTrue(result.value.protectedExport)
        assertFalse(result.value.toString().contains("export=["))
        val unused = ManagerTestChannel(wire) { code, _ -> check(code == 4); success(info()) }
        assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE), execute(ManagerOperation.FIDO_BLOB_READ, unused))
        assertEquals(listOf(4), unused.commands.map { it.first })
    }
    @Test fun disclosureListIsExplicitAndBounded() {
        assertEquals(emptyList<String>(), parseDisclosureRps("  "))
        assertEquals(listOf("a.example", "b.example"), parseDisclosureRps("a.example\nb.example"))
        assertThrows(IllegalArgumentException::class.java) { parseDisclosureRps("a\na") }
        assertThrows(IllegalArgumentException::class.java) { parseDisclosureRps("a\nb\nc") }
    }

    @Test fun minimumPinPassesReplacementListAndForcePolicy() {
        var changed = false
        val channel = ManagerTestChannel(wire) { code, params -> when (code) {
            4 -> success(info(mapOf("setMinPINLength" to true, "authnrCfg" to true), extensions = listOf("minPinLength"),
                extra = cborMap(13 to (if (changed) 6 else 4).cbor(), 16 to 2.cbor(), 12 to changed.cbor())))
            13 -> {
                assertEquals(3L, params!![1].number())
                assertEquals(6L, params[2].map()[1].number())
                assertEquals(listOf("a.example", "b.example"), params[2].map()[2].array().map { it.text() })
                assertTrue(params[2].map()[3].bool()); changed = true; success()
            }
            else -> error("Unexpected command")
        } }
        val result = CardSession(channel).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_MIN_PIN,
            text = "6", enabled = true, rpIds = listOf("a.example", "b.example"))) }
        assertTrue(result is OperationResult.Completed)
    }
    @Test fun emptyReplacementListIsSentAndLowerPinLengthIsRejected() {
        var writes = 0
        val channel = ManagerTestChannel(wire) { code, params -> when(code) {
            4 -> success(info(mapOf("setMinPINLength" to true, "authnrCfg" to true), extensions = listOf("minPinLength"),
                extra = cborMap(16 to 2.cbor())))
            13 -> { assertTrue(params!![2].map()[2].array().isEmpty()); writes++; success() }
            else -> error("Unexpected command")
        } }
        assertTrue(CardSession(channel).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_MIN_PIN, text = "4")) } is OperationResult.Completed)
        assertEquals(1, writes)
        val lower = ManagerTestChannel(wire) { _, _ -> success(info(mapOf("setMinPINLength" to true))) }
        assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE),
            CardSession(lower).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_MIN_PIN, text = "3")) })
        assertEquals(listOf(4), lower.commands.map { it.first })
    }

    @Test fun blobDeletionUsesNullAndEmptyWriteRemainsEncryptedEntry() {
        for (delete in listOf(false, true)) {
            CardSession(registration(blobKey = ByteArray(32) { 7 })).run {
                manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true,
                    makeExtensions = MakeExtensions(largeBlobKey = true)))
            }
            var stored = LargeBlobs.encode(listOf(LargeBlobs.encrypt(ByteArray(32) { 7 }, byteArrayOf(9))))
            val channel = ManagerTestChannel(wire) { code, params -> when(code) {
                4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
                2 -> blobProof(params!!)
                12 -> if (params!![1] != null) success(cborMap(1 to stored.cbor())) else {
                    assertEquals(0L, params[3].number()); stored = params[2].bytes(); success()
                }
                else -> error("Unexpected command")
            } }
            assertTrue(CardSession(channel).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_BLOB_WRITE,
                bytes = byteArrayOf(), deleteBlob = delete)) } is OperationResult.Completed)
            val entries = LargeBlobs.decode(stored)
            assertEquals(if (delete) 0 else 1, entries.size)
            if (!delete) assertArrayEquals(byteArrayOf(), LargeBlobs.decrypt(ByteArray(32) { 7 }, entries.single()))
        }
    }

    @Test fun registrationLargeBlobKeyRequiresResidentAndPreservesOtherExtensions() {
        for (resident in listOf(false, true)) for (requested in listOf(false, true)) {
            val effective = resident && requested
            val blob = byteArrayOf(9, 8)
            val input = OperationInput(ManagerOperation.FIDO_REGISTER, enabled = resident,
                makeExtensions = MakeExtensions(credentialProtection = 2, credentialBlob = blob,
                    hmacSecret = true, minPinLength = true, largeBlobKey = requested, uvm = true))
            val channel = ManagerTestChannel(wire) { code, params -> when (code) {
                4 -> success(info(mapOf("rk" to true),
                    extensions = listOf("largeBlobKey", "credProtect", "credBlob", "hmac-secret", "minPinLength", "uvm"),
                    extra = cborMap(15 to 32.cbor())))
                1 -> {
                    assertEquals(resident, params!![7].map()["rk"].bool())
                    val extensions = params[6].map()
                    assertEquals(effective, extensions["largeBlobKey"]?.bool() ?: false)
                    if (!effective) assertNull(extensions["largeBlobKey"])
                    assertEquals(2L, extensions["credProtect"].number())
                    assertArrayEquals(blob, extensions["credBlob"].bytes())
                    for (name in listOf("hmac-secret", "minPinLength", "uvm")) assertTrue(extensions[name].bool())
                    val auth = rpHash + byteArrayOf(0xc1.toByte(), 0, 0, 0, 0) + ByteArray(16) +
                        byteArrayOf(0, id.size.toByte()) + id + CborCodec.encode(key) +
                        CborCodec.encode(textMap("credProtect" to 2.cbor()))
                    success(cborMap(1 to "packed".cbor(), 2 to auth.cbor(),
                        3 to textMap("alg" to (-7).cbor(), "sig" to sign(auth + params[1].bytes(), true).cbor()),
                        5 to if (effective) ByteArray(32) { 7 }.cbor() else null))
                }
                else -> error("Unexpected command $code")
            } }
            input.use {
                val result = CardSession(channel).run { manager.execute(it, input) }
                assertTrue("resident=$resident requested=$requested: $result", result is OperationResult.Completed)
                assertEquals(effective, (result as OperationResult.Completed).value.secrets?.blobKey != null)
                assertEquals(effective, input.makeExtensions.largeBlobKey)
                assertEquals(effective, cc.cardkey.R.string.confirm_secretRead in ConfirmationPolicy.messages(input))
            }
            assertEquals(listOf(4, 1), channel.commands.map { it.first })
        }
    }

    @Test fun assertionLargeBlobKeyRemainsAvailableAfterNonresidentRegistration() {
        assertTrue(execute(ManagerOperation.FIDO_REGISTER, registration()) is OperationResult.Completed)
        val input = OperationInput(ManagerOperation.FIDO_ASSERT, enabled = false,
            assertionExtensions = AssertionExtensions(largeBlobKey = true))
        assertTrue(input.assertionExtensions.largeBlobKey)
        assertTrue(cc.cardkey.R.string.confirm_secretRead in ConfirmationPolicy.messages(input))
        val channel = ManagerTestChannel(wire) { code, params -> when (code) {
            4 -> success(info(extensions = listOf("largeBlobKey")))
            2 -> {
                assertTrue(params!![4].map()["largeBlobKey"].bool())
                val auth = rpHash + byteArrayOf(1, 0, 0, 0, 1)
                success(cborMap(1 to descriptor(id), 2 to auth.cbor(),
                    3 to sign(auth + params[2].bytes(), true).cbor(), 7 to ByteArray(32) { 7 }.cbor()))
            }
            else -> error("Unexpected command $code")
        } }
        val result = CardSession(channel).run { manager.execute(it, input) }
        assertTrue(result is OperationResult.Completed)
        assertArrayEquals(ByteArray(32) { 7 }, (result as OperationResult.Completed).value.secrets?.blobKey)
    }

    @Test fun allCredentialProtectionPoliciesReachRegistration() {
        for (policy in 1..3) {
            assertTrue(CardSession(registration(protection = policy)).run {
                manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER,
                    makeExtensions = MakeExtensions(credentialProtection = policy)))
            } is OperationResult.Completed)
        }
    }

    @Test fun swappedCardAndMismatchedFreshBlobKeyCannotReadOrWriteBlob() {
        for (valid in listOf(false, true)) {
            CardSession(registration(blobKey = ByteArray(32) { 7 })).run {
                manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true,
                    makeExtensions = MakeExtensions(largeBlobKey = true)))
            }
            val channel = ManagerTestChannel(wire) { code, params -> when (code) {
                4 -> success(info(extensions = listOf("largeBlobKey")))
                2 -> blobProof(params!!, valid = valid, returnedKey = ByteArray(32) { 8 })
                else -> error("Unverified card must not receive a blob operation")
            } }
            val result = execute(ManagerOperation.FIDO_BLOB_READ, channel)
            assertTrue(result is OperationResult.Failed)
            assertEquals(listOf(4, 2), channel.commands.map { it.first })
            assertNull((result as? OperationResult.Completed)?.value?.export)
        }
    }

    @Test fun uvmRequestAndVerifiedResultReachManagerReport() {
        val methods = Cbor.Array(listOf(Cbor.Array(listOf(4.cbor(), 10.cbor(), 1.cbor()))))
        val channel = registration(uvm = methods)
        val result = CardSession(channel).run {
            manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER, makeExtensions = MakeExtensions(uvm = true)))
        } as OperationResult.Completed
        assertEquals("4, 10, 1", result.value.rows.single { it.label == "用户验证方法" }.value)
        assertTrue(channel.commands.single { it.first == 1 }.second!![6].map()["uvm"].bool())
        val assertionChannel = assertion(true, 1, methods)
        val assertionResult = CardSession(assertionChannel).run {
            manager.execute(it, OperationInput(ManagerOperation.FIDO_ASSERT, assertionExtensions = AssertionExtensions(uvm = true)))
        } as OperationResult.Completed
        assertEquals("4, 10, 1", assertionResult.value.rows.single { it.label == "用户验证方法" }.value)
        assertTrue(assertionChannel.commands.single { it.first == 2 }.second!![4].map()["uvm"].bool())
    }
    @Test fun malformedOrUnrequestedUvmCannotBecomeSuccessfulReport() {
        for (requested in listOf(false, true)) {
            val malformed = Cbor.Array(listOf(Cbor.Array(listOf(4.cbor(), 65536.cbor(), 1.cbor()))))
            val result = CardSession(registration(uvm = malformed)).run {
                manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER, makeExtensions = MakeExtensions(uvm = requested)))
            }
            assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED, writeAccepted = true), result)
        }
    }
    @Test fun cancellationAfterFreshProofPreservesKnownEffectAndDispatchesNoBlob() {
        var cancelled = false
        val controlled = ManagerOperations { cancelled }
        CardSession(registration()).run { controlled.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER)) }
        val channel = ManagerTestChannel(wire) { code, params -> when(code) {
            4 -> success(info(extensions = listOf("largeBlobKey")))
            2 -> blobProof(params!!).also { cancelled = true }
            else -> error("Cancelled workflow sent a blob command")
        } }
        val result = CardSession(channel).run { controlled.execute(it, OperationInput(ManagerOperation.FIDO_BLOB_READ)) }
        assertEquals(OperationResult.Failed(FailureCode.CANCELLED, Effect.APPLIED, writeAccepted = true), result)
        assertEquals(listOf(4, 2), channel.commands.map { it.first })
    }
    @Test fun lostBlobWriteAfterVerifiedProofRemainsUnknownAndNeverRetries() {
        CardSession(registration()).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER)) }
        val initial = LargeBlobs.encode(emptyList())
        var writes = 0
        val channel = ManagerTestChannel(wire) { code, params -> when(code) {
            4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
            2 -> blobProof(params!!)
            12 -> if (params!![1] != null) success(cborMap(1 to initial.cbor())) else {
                writes++; throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            }
            else -> error("Unexpected command")
        } }
        val result = CardSession(channel).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_BLOB_WRITE, bytes = byteArrayOf(9))) }
        assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
            dispatch = Dispatch.RESPONSE_UNAVAILABLE, writeAccepted = true), result)
        assertEquals(1, writes)
    }

    @Test fun importedBytesSurviveSuccessfulAndFailedAttempts() {
        val imported = "reusable imported contents".toByteArray()
        val expected = imported.copyOf()
        val key = ByteArray(32) { 7 }
        for (failWrite in listOf(false, true, false)) {
            CardSession(registration()).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER)) }
            var stored = LargeBlobs.encode(emptyList())
            val channel = ManagerTestChannel(wire) { code, params -> when (code) {
                4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
                2 -> blobProof(params!!)
                12 -> if (params!![1] != null) success(cborMap(1 to stored.cbor())) else {
                    if (failWrite) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    stored = params[2].bytes(); success()
                }
                else -> error("Unexpected command")
            } }
            val input = OperationInput(ManagerOperation.FIDO_BLOB_WRITE, bytes = imported.copyOf())
            val result = input.use { CardSession(channel).run { manager.execute(it, input) } }
            assertArrayEquals(expected, imported)
            assertArrayEquals(ByteArray(expected.size), input.bytes)
            if (failWrite) assertTrue(result is OperationResult.Failed) else {
                assertTrue(result is OperationResult.Completed)
                assertArrayEquals(expected, LargeBlobs.decrypt(key, LargeBlobs.decode(stored).single()))
            }
        }
    }

    @Test fun unknownMatchingCredentialMutationInvalidatesDiagnosticContextWithoutExtraPinAttempts() {
        for (operation in listOf(ManagerOperation.FIDO_DELETE, ManagerOperation.FIDO_UPDATE)) {
            execute(ManagerOperation.FIDO_REGISTER, registration())
            val server = FidoClientTest.PinServer(2)
            val channel = ManagerTestChannel(wire) { code, params -> when(code) {
                4 -> success(info(mapOf("credMgmt" to true, "pinUvAuthToken" to true)))
                6 -> server.respond(params!!, 4, null)
                10 -> when(params!![1].number()) {
                    2L -> success(cborMap(3 to textMap("id" to "cardkey.example".cbor()), 4 to rpHash.cbor(), 5 to 1.cbor()))
                    4L -> success(cborMap(6 to FidoUser(byteArrayOf(1), "old", null).encode(),
                        7 to descriptor(id), 8 to key, 9 to 1.cbor()))
                    6L, 7L -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    else -> error("Unexpected command")
                }
                else -> error("Unexpected command")
            } }
            val result = CardSession(channel).run { manager.execute(it, OperationInput(operation,
                pin = "1234".toCharArray(), text = id.hex(), secondary = "new")) }
            assertEquals(Effect.UNKNOWN, (result as OperationResult.Failed).effect)
            assertEquals(1, server.tokenRequests)
            val untouched = assertion(true, 1)
            assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), execute(ManagerOperation.FIDO_ASSERT, untouched))
            assertTrue(untouched.commands.isEmpty())
        }
    }

    @Test fun blobKeyMismatchInvalidatesContextBeforeAnotherAttempt() {
        CardSession(registration(blobKey = ByteArray(32) { 7 })).run { manager.execute(it,
            OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true, makeExtensions = MakeExtensions(largeBlobKey = true))) }
        val first = ManagerTestChannel(wire) { code, params -> when(code) {
            4 -> success(info(extensions = listOf("largeBlobKey")))
            2 -> blobProof(params!!, returnedKey = ByteArray(32) { 8 })
            else -> error("Mismatch must not dispatch a blob command")
        } }
        assertEquals(OperationResult.Failed(FailureCode.AUTHORIZATION_DENIED, Effect.APPLIED, writeAccepted = true),
            execute(ManagerOperation.FIDO_BLOB_READ, first))
        val encoded = LargeBlobs.encode(listOf(LargeBlobs.encrypt(ByteArray(32) { 8 }, byteArrayOf(9))))
        val next = ManagerTestChannel(wire) { code, params -> when(code) {
            4 -> success(info(mapOf("largeBlobs" to true), extensions = listOf("largeBlobKey"), extra = cborMap(11 to 4096.cbor())))
            2 -> blobProof(params!!, returnedKey = ByteArray(32) { 8 })
            12 -> success(cborMap(1 to encoded.cbor()))
            else -> error("Unexpected command")
        } }
        assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), execute(ManagerOperation.FIDO_BLOB_READ, next))
        assertTrue(next.commands.isEmpty())
    }
    @Test fun credentialBlobIsNeverAutomaticOrdinaryOutputAndFollowsSecretLifecycle() {
        execute(ManagerOperation.FIDO_REGISTER, registration())
        val plaintext = byteArrayOf(9, 8, 7, 6)
        val result = CardSession(assertion(true, 1, credentialBlob = plaintext)).run {
            manager.execute(it, OperationInput(ManagerOperation.FIDO_ASSERT,
                assertionExtensions = AssertionExtensions(credentialBlob = true)))
        } as OperationResult.Completed
        assertFalse(result.value.rows.any { it.label == "credBlob" || it.value.contains(plaintext.hex()) })
        assertNull(result.value.export)
        assertNotNull(result.value.secrets)
        val secret = result.value.secrets!!.credBlob!!
        assertArrayEquals(plaintext, secret)
        assertFalse(result.value.toString().contains(plaintext.hex()))
        manager.clearSession()
        assertArrayEquals(ByteArray(plaintext.size), secret)
    }

    @Test fun invalidCredentialBlobAssertionCannotProduceSecretOutputOrAdvanceContext() {
        execute(ManagerOperation.FIDO_REGISTER, registration())
        val channel = assertion(false, 9, credentialBlob = byteArrayOf(9, 8, 7))
        val result = CardSession(channel).run { manager.execute(it, OperationInput(ManagerOperation.FIDO_ASSERT,
            assertionExtensions = AssertionExtensions(credentialBlob = true))) }
        assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED, writeAccepted = true), result)
        assertTrue(execute(ManagerOperation.FIDO_ASSERT, assertion(true, 1)) is OperationResult.Completed)
    }

    @Test fun acceptedAndUncertainResetRetireDiagnosticProofAndSecretOutputs() {
        if (wire != CardWire.CTAP) return
        for (response in listOf("accepted", "lost", "malformed", "readback")) {
            assertTrue(execute(ManagerOperation.U2F_REGISTER, u2fRegistration()) is OperationResult.Completed)
            val registered = CardSession(registration(blobKey = ByteArray(32) { 7 })).run {
                manager.execute(it, OperationInput(ManagerOperation.FIDO_REGISTER, enabled = true,
                    makeExtensions = MakeExtensions(largeBlobKey = true)))
            } as OperationResult.Completed
            val secret = requireNotNull(registered.value.secrets?.blobKey)
            val reset = ManagerTestChannel(wire) { code, _ -> when (code) {
                7 -> when (response) {
                    "lost" -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    "malformed" -> byteArrayOf()
                    else -> success()
                }
                4 -> if (response == "readback") throw TransportException(Dispatch.RESPONSE_UNAVAILABLE) else success(info())
                else -> error("Unexpected command")
            } }
            execute(ManagerOperation.FIDO_RESET, reset)
            assertArrayEquals(ByteArray(32), secret)
            val staleU2f = u2fAssertion(true, 1)
            assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), execute(ManagerOperation.U2F_ASSERT, staleU2f))
            assertEquals(0, staleU2f.requests)
            for (operation in listOf(ManagerOperation.FIDO_ASSERT, ManagerOperation.FIDO_BLOB_READ, ManagerOperation.FIDO_BLOB_WRITE)) {
                val untouched = ManagerTestChannel(wire) { _, _ -> error("Reset restored stale proof") }
                assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), execute(operation, untouched))
                assertTrue(untouched.commands.isEmpty())
            }
        }
    }

}
