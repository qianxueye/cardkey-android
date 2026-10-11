package cc.cardkey.protocol.piv

import cc.cardkey.protocol.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class PivSecureContextTest {
    private val fixture = JsonParser.parseString(File("../fixtures/cs7-v1.json").readText()).asJsonObject
    private fun bytes(name: String) = fixture[name].asString.hexBytes()
    private fun keys() = fixture["keys"].asJsonObject.let { listOf("cfrm", "mac", "enc", "rmac").fold(byteArrayOf()) { a, k -> a + it[k].asString.hexBytes() } }
    private fun identity(issuer: ByteArray = Cs7.publicKey(bytes("issuer_point")).encoded,
                         guid: ByteArray = bytes("guid"), point: ByteArray = bytes("card_point")) =
        EnrolledPivCard(issuer, bytes("issuer_id"), guid, point)
    private fun privateKey(scalar: Int) = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.valueOf(scalar.toLong()), Cs7.curve))
    private fun host() = KeyPair(Cs7.publicKey(bytes("host_point")), privateKey(7))
    private fun failure(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun publicEcdhKdfCiccAndConfirmationVectors() {
        val public = Cs7.verifyCicc(bytes("cvc"), identity())
        val z = KeyAgreement.getInstance("ECDH").run { init(privateKey(7)); doPhase(public, true); generateSecret() }
        assertArrayEquals(bytes("z"), z)
        assertArrayEquals(bytes("ids_icc"), MessageDigest.getInstance("SHA-256").digest(bytes("cvc")).copyOf(8))
        val derived = Cs7.derive(z, bytes("host_id"), bytes("ids_icc"), bytes("nonce"), bytes("host_point"))
        assertArrayEquals(keys(), derived)
        assertArrayEquals(bytes("confirmation"), Cs7.mac(derived.copyOf(32), "KC_1_V".toByteArray() +
            bytes("ids_icc") + bytes("host_id") + bytes("host_point").copyOfRange(1, 97)))
    }

    @Test fun publicFourExchangeVectorsIncludeIndependentFullChainsAndLargeBodies() {
        val cipher = Cs7Cipher(keys())
        for (element in fixture["exchanges"].asJsonArray) {
            val e = element.asJsonObject
            fun b(n: String) = e[n].asString.hexBytes()
            val h = b("header")
            assertArrayEquals(b("command_iv"), cipher.iv(false))
            assertArrayEquals(b("response_iv"), cipher.iv(true))
            val command = cipher.protect(Apdu(ins = h[1].toInt() and 255, p1 = h[2].toInt() and 255,
                p2 = h[3].toInt() and 255, data = b("plaintext"), le = if (e["le"].asBoolean) 256 else null))
            assertArrayEquals(b("command"), command.data)
            val result = cipher.unprotect(ApduResponse(b("response"), 0x9000))
            assertArrayEquals(b("response_plaintext"), result.data)
            assertEquals(e["status"].asString.toInt(16), result.status)
        }
        cipher.close()
        failure { cipher.protect(Apdu(ins = 0x20)) }
    }

    @Test fun trustMismatchWrongRoleOidAndNoncanonicalSignatureStopBeforePairing() {
        val cvc = bytes("cvc")
        val cases = listOf(
            identity(guid = ByteArray(16)),
            identity(point = bytes("host_point")),
            identity(issuer = Cs7.publicKey(bytes("host_point")).encoded)
        )
        for (enrolled in cases) {
            val card = Peer()
            val session = CardSession(card)
            val code = "12345678".toCharArray()
            failure { PivSecureContext.establish(session, enrolled, code, SecureRandom(), host(), bytes("host_id")) }
            assertTrue(code.all { it == '\u0000' })
            assertEquals(0, card.protectedCommands)
        }
        val fields = PivTlv.parse(PivTlv.one(cvc, 0x7f21))
        fun changed(tag: Int, value: ByteArray): ByteArray = PivTlv.encode(0x7f21,
            fields.entries.fold(byteArrayOf()) { a, e -> a + PivTlv.encode(e.key, if (e.key == tag) value else e.value) })
        val signature = PivTlv.one(fields.getValue(0x5f37), 0x30)
        val mutations = listOf(
            changed(0x5f4c, byteArrayOf(1)), changed(0x5f29, byteArrayOf(0)),
            changed(0x42, ByteArray(8)), changed(0x7f49, fields.getValue(0x7f49).copyOf().also { it[6] = 0x23 }),
            changed(0x5f37, PivTlv.encode(0x30, signature.copyOf().also { it[1] = 0x0c })),
            changed(0x5f37, PivTlv.encode(0x30, signature + byteArrayOf(0))),
            changed(0x5f37, byteArrayOf(0x30, 0)), cvc + byteArrayOf(0),
            PivTlv.encode(0x7f21, PivTlv.encode(0x42, bytes("issuer_id")) + PivTlv.one(cvc, 0x7f21))
        )
        for (bad in mutations) {
            val card = Peer(cicc = bad)
            failure { establish(card) }
            assertEquals(0, card.protectedCommands)
        }
    }

    @Test fun fullEstablishmentUsesPublicFixtureAndWipesPairingCode() {
        val card = Peer()
        val session = CardSession(card)
        val pairing = "12345678".toCharArray()
        val context = PivSecureContext.establish(session, identity(), pairing, SecureRandom(), host(), bytes("host_id"))
        assertTrue(pairing.all { it == '\u0000' })
        assertArrayEquals(fixture["exchanges"].asJsonArray[0].asJsonObject["command"].asString.hexBytes(), card.lastProtected)
        context.close()
        failure { PivClient(session, context).verifyPin("123456".toCharArray()) }
        assertEquals(1, card.protectedCommands)
    }

    @Test fun confirmationAndPairingFailuresNeverSendPin() {
        for (tamper in listOf(true, false)) {
            val card = Peer().apply { badConfirmation = tamper; pairingStatus = 0x6300 }
            val exception = failure { establish(card) }
            assertTrue(exception is CardFailure)
            assertEquals(if (tamper) 0 else 1, card.protectedCommands)
            assertEquals(0, card.pinTries)
        }
    }

    @Test fun protectedPinStatusAndWrongPinAreAuthenticatedAndDoNotFakeVerification() {
        val card = Peer()
        val (session, context) = establish(card)
        val client = PivClient(session, context)
        assertEquals(PivPinStatus(false, 5, false), client.pinStatus())
        assertEquals(0, card.pinTries)
        card.pinStatus = 0x63c4
        val result = session.run { client.verifyPin("000000".toCharArray()) }
        assertEquals(FailureCode.PIN_INVALID, (result as OperationResult.Failed).code)
        assertEquals(Effect.APPLIED, result.effect)
        assertFalse(result.writeAccepted)
        assertEquals(1, card.pinTries)
    }

    @Test fun protectedPinFloorRequestsContactOnlyOnNfcAndPreservesBlockedStatus() {
        for (transport in listOf(Medium.NFC, Medium.CONTACT)) {
            val card = Peer().apply { pinStatus = 0x6983; medium = transport }
            val (session, context) = establish(card)
            val client = PivClient(session, context)
            assertEquals(PivPinStatus(false, null, transport == Medium.NFC), client.pinStatus())
            assertEquals(0, card.pinTries)
            assertEquals(Effect.NONE, session.effect)
            val result = session.run { client.verifyPin("123456".toCharArray()) } as OperationResult.Failed
            assertEquals(FailureCode.PIN_BLOCKED, result.code)
            assertEquals(0x6983, result.status)
            assertEquals(Effect.NONE, result.effect)
            assertFalse(result.writeAccepted)
        }
    }

    @Test fun protectedPinSuccessRequiresAuthenticatedReply() {
        val card = Peer().apply { pinStatus = 0x9000 }
        val (session, context) = establish(card)
        val result = session.run { PivClient(it, context).verifyPin("123456".toCharArray()) }
        assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect)
    }

    @Test fun tamperedOrLostProtectedMutationIsUnknownAndNeverReplayed() {
        for (mode in listOf("mac", "lost", "outer", "short", "padding")) {
            val card = Peer()
            val (session, context) = establish(card)
            card.failure = mode
            val result = session.run { PivClient(it, context).verifyPin("123456".toCharArray()) } as OperationResult.Failed
            assertEquals(mode, Effect.UNKNOWN, result.effect)
            assertFalse(result.writeAccepted)
            assertEquals(mode, 2, card.protectedCommands)
        }
    }

    @Test fun priorAuthenticatedWriteRemainsRecordedAfterUncertainProtectedMutation() {
        val card = Peer()
        val (session, context) = establish(card)
        session.applied()
        card.failure = "mac"
        val result = session.run { PivClient(it, context).verifyPin("123456".toCharArray()) } as OperationResult.Failed
        assertEquals(Effect.UNKNOWN, result.effect)
        assertTrue(result.writeAccepted)
    }

    @Test fun beforeDispatchFailureDoesNotCreateMutationEffect() {
        val card = Peer()
        val (session, context) = establish(card)
        card.failure = "before"
        val result = session.run { PivClient(it, context).verifyPin("123456".toCharArray()) } as OperationResult.Failed
        assertEquals(Effect.NONE, result.effect)
        assertFalse(result.writeAccepted)
    }

    @Test fun reselectionNewEstablishmentFailureCloseAndMediumChangeInvalidateOldContext() {
        for (mode in listOf("select", "new", "close", "medium")) {
            val card = Peer()
            val (session, context) = establish(card)
            when (mode) {
                "select" -> session.exchange(Apdu(ins = 0xa4, p1 = 4))
                "new" -> failure { PivSecureContext.establish(session, identity(), "bad".toCharArray()) }
                "close" -> session.close()
                "medium" -> card.medium = Medium.CONTACT
            }
            val count = card.commands.size
            failure { PivClient(session, context).verifyPin("123456".toCharArray()) }
            assertEquals(count, card.commands.size)
        }
    }

    @Test fun unknownMediumCannotEstablishOrSendAnyApdu() {
        val card = Peer().apply { medium = Medium.UNKNOWN }
        failure { establish(card) }
        assertTrue(card.commands.isEmpty())
    }

    @Test fun pairedNfcStillRejectsAllAdministrativeOperationsBeforeTransmission() {
        val card = Peer()
        val (session, context) = establish(card)
        val client = PivClient(session, context)
        val operations = listOf<() -> Unit>(
            { client.changePin("123456".toCharArray(), "234567".toCharArray()) },
            { client.changePuk("123456".toCharArray(), "234567".toCharArray()) },
            { client.unblockPin("123456".toCharArray(), "234567".toCharArray()) },
            { client.authenticateManagement(ManagementAlgorithm.AES256, ByteArray(32)) },
            { client.changeManagementKey(ManagementAlgorithm.AES256, ByteArray(32), ManagementAlgorithm.AES256, ByteArray(32)) },
            { client.generateKey(0x9a, KeyAlgorithm.P256, PinPolicy.ONCE, ManagementAlgorithm.AES256, ByteArray(32)) },
            { client.importCertificate(0x9a, byteArrayOf(), ManagementAlgorithm.AES256, ByteArray(32)) },
            { client.reset() }
        )
        val count = card.commands.size
        for (op in operations) assertEquals(FailureCode.CONTACT_REQUIRED, (failure(op) as CardFailure).code)
        assertEquals(count, card.commands.size)
    }

    @Test fun protectedPrivateSignaturesAndCsrUseAuthenticatedPlaintextForAllAlgorithms() {
        for (algorithm in KeyAlgorithm.entries) {
            val card = Peer(algorithm = algorithm).apply { pinStatus = 0x9000 }
            val (session, context) = establish(card)
            val client = PivClient(session, context)
            assertTrue(client.verifySignature(0x9a, "123456".toCharArray()))
            assertTrue(client.createCsr(0x9a, "123456".toCharArray(), "CN=Public Synthetic CS7").isNotEmpty())
            session.close()
        }
    }

    @Test fun protectedEcdhAndRsaDecryptionCrossFragmentBoundaries() {
        for (algorithm in KeyAlgorithm.entries) {
            val card = Peer(algorithm = algorithm).apply { pinStatus = 0x9000 }
            val (session, context) = establish(card)
            val client = PivClient(session, context)
            assertTrue(if (algorithm == KeyAlgorithm.RSA2048) client.verifyRsaDecryption(0x9d, "123456".toCharArray())
                else client.verifyKeyAgreement(0x9d, "123456".toCharArray()))
            if (algorithm == KeyAlgorithm.RSA2048) {
                assertTrue(card.commands.any { it[0] == 0x1c.toByte() })
                assertTrue(card.commands.any { it[1] == 0xc0.toByte() && it[0] == 0.toByte() })
            }
            session.close()
        }
    }

    @Test fun responseObjectsRejectMissingDuplicateReorderedTruncatedReplayAndBadMac() {
        val first = fixture["exchanges"].asJsonArray[0].asJsonObject["response"].asString.hexBytes()
        val invalid = listOf(byteArrayOf(), first.copyOf(4), first + first, first.copyOfRange(4, first.size) + first.copyOf(4),
            first.copyOf(first.size - 1), first.copyOf().also { it[it.lastIndex]++ }, "99810290008e080a1a957b7cb7ab7a".hexBytes())
        for (body in invalid) {
            val cipher = Cs7Cipher(keys())
            cipher.protect(Apdu(ins = 0x20, p2 = 0x98, data = "12345678".toByteArray(), le = null))
            failure { cipher.unprotect(ApduResponse(body, 0x9000)) }
            failure { cipher.protect(Apdu(ins = 0x20)) }
        }
        val cipher = Cs7Cipher(keys())
        cipher.protect(Apdu(ins = 0x20, p2 = 0x98, data = "12345678".toByteArray(), le = null))
        cipher.unprotect(ApduResponse(first, 0x9000))
        cipher.protect(Apdu(ins = 0x20, p2 = 0x80, data = ByteArray(8), le = null))
        failure { cipher.unprotect(ApduResponse(first, 0x9000)) }
    }

    @Test fun pendingResponseBlocksAnotherCommandAndUnpairedContextIsNotReusable() {
        val cipher = Cs7Cipher(keys())
        cipher.protect(Apdu(ins = 0x20))
        failure { cipher.protect(Apdu(ins = 0x87)) }
        val card = Peer()
        val (session, context) = establish(card)
        card.failure = "mac"
        failure { PivClient(session, context).verifyPin("123456".toCharArray()) }
        val count = card.commands.size
        failure { PivClient(session, context).verifyPin("123456".toCharArray()) }
        assertEquals(count, card.commands.size)
    }

    @Test fun dataFreePinStatusHasNoEncryptedPayloadOrMutationEffect() {
        val card = Peer().apply { pinStatus = 0x9000 }
        val (session, context) = establish(card)
        assertEquals(PivPinStatus(true, null, false), PivClient(session, context).pinStatus())
        assertEquals(listOf(0x8e), PivTlv.parse(card.lastProtected).keys.toList())
        assertEquals(Effect.NONE, session.effect)
        assertEquals(0, card.pinTries)
    }

    @Test fun cipherRejectsWrongClaAndOuterErrorsWithoutExposingInnerSuccess() {
        val cipher = Cs7Cipher(keys())
        failure { cipher.protect(Apdu(cla = 0x1c, ins = 0x20)) }
        cipher.protect(Apdu(ins = 0x20, le = null))
        val response = fixture["exchanges"].asJsonArray[0].asJsonObject["response"].asString.hexBytes()
        val error = failure { cipher.unprotect(ApduResponse(response, 0x6988)) } as CardFailure
        assertEquals(0x6988, error.status)
        failure { cipher.protect(Apdu(ins = 0x20)) }
    }

    @Test fun authenticatedMalformedEncryptionObjectsAndPaddingAreRejected() {
        val enc = keys().copyOfRange(64, 96)
        for (kind in listOf("indicator", "size", "zero", "marker", "overpadding")) {
            val cipher = Cs7Cipher(keys())
            cipher.protect(Apdu(ins = 0x20, le = null))
            val plain = when (kind) {
                "marker" -> ByteArray(16).also { it[15] = 1 }
                "overpadding" -> ByteArray(32).also { it[0] = 0x80.toByte() }
                else -> ByteArray(16)
            }
            val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), IvParameterSpec(cipher.iv(true))); doFinal(plain)
            }
            val value = when (kind) {
                "indicator" -> byteArrayOf(2) + encrypted
                "size" -> byteArrayOf(1, 0)
                else -> byteArrayOf(1) + encrypted
            }
            val objects = PivTlv.encode(0x87, value) + "99029000".hexBytes()
            val mac = Cs7.mac(keys().copyOfRange(96, 128), ByteArray(16) + objects)
            failure { cipher.unprotect(ApduResponse(objects + PivTlv.encode(0x8e, mac.copyOf(8)), 0x9000)) }
            failure { cipher.protect(Apdu(ins = 0x20)) }
        }
    }

    @Test fun counterExhaustionCannotWrapOrReturnSuccess() {
        val cipher = Cs7Cipher(keys())
        val field = Cs7Cipher::class.java.getDeclaredField("counter").apply { isAccessible = true }
        val counter = field.get(cipher) as ByteArray
        counter.fill(0xff.toByte()); counter[0] = 0
        cipher.protect(Apdu(ins = 0x20, le = null))
        val objects = "99029000".hexBytes()
        val mac = Cs7.mac(keys().copyOfRange(96, 128), ByteArray(16) + objects)
        failure { cipher.unprotect(ApduResponse(objects + PivTlv.encode(0x8e, mac.copyOf(8)), 0x9000)) }
        failure { cipher.protect(Apdu(ins = 0x20)) }
    }

    @Test fun protectWholeLogicalDataBeforeFragmentingAt255And256() {
        for (size in listOf(255, 256)) {
            val card = Peer()
            val (session, context) = establish(card)
            val body = ByteArray(size) { it.toByte() }
            val result = context.exchange(Apdu(ins = 0xcb, p1 = 0x3f, p2 = 0xff, data = body))
            assertArrayEquals(ByteArray(256) { it.toByte() }, result.data)
            val fragments = card.commands.filter { it[0] == 0x1c.toByte() }
            assertEquals(1, fragments.size)
            assertEquals(255, fragments.single()[4].toInt() and 255)
            assertEquals(Effect.NONE, session.effect)
        }
    }

    @Test fun fragmentAcknowledgmentCannotAcceptMutationOrUnlockPin() {
        val card = Peer()
        val (session, context) = establish(card)
        card.failure = "lost"
        val result = session.run {
            context.exchange(Apdu(ins = 0x87, p1 = 7, p2 = 0x9d, data = ByteArray(256)), true)
        } as OperationResult.Failed
        assertEquals(Effect.UNKNOWN, result.effect)
        assertFalse(result.writeAccepted)
        assertEquals(0, card.pinTries)
        assertEquals(1, card.commands.count { it[0] == 0x1c.toByte() })
    }

    private fun establish(card: Peer): Pair<CardSession, PivSecureContext> {
        val session = CardSession(card)
        return session to PivSecureContext.establish(session, identity(), "12345678".toCharArray(), SecureRandom(), host(), bytes("host_id"))
    }

    private inner class Peer(val cicc: ByteArray = bytes("cvc"), val algorithm: KeyAlgorithm = KeyAlgorithm.P256) : CardChannel {
        override var medium = Medium.NFC
        override val maxCommandBytes = 261
        val commands = mutableListOf<ByteArray>()
        var protectedCommands = 0
        var pinTries = 0
        var pinStatus = 0x63c5
        var pairingStatus = 0x9000
        var badConfirmation = false
        var failure: String? = null
        var lastProtected = byteArrayOf()
        private var fragments = byteArrayOf()
        private var output = byteArrayOf()
        private var cchain = ByteArray(16)
        private var rchain = ByteArray(16)
        private var counter = 1
        private val keyBytes = keys()
        private val slotKey = KeyPairGenerator.getInstance(if (algorithm == KeyAlgorithm.RSA2048) "RSA" else "EC").apply {
            if (this@Peer.algorithm == KeyAlgorithm.RSA2048) initialize(2048) else initialize(ECGenParameterSpec(this@Peer.algorithm.curve))
        }.generateKeyPair()
        override fun close() = Unit
        override fun transmit(command: ByteArray): ByteArray {
            commands += command.copyOf()
            val ins = command[1].toInt() and 255
            if (ins == 0xc0) { assertEquals(0, command[0].toInt()); return next() }
            if (ins == 0xa4) return "9000".hexBytes()
            val length = command[4].toInt() and 255
            fragments += command.copyOfRange(5, 5 + length)
            if (command[0].toInt() and 0x10 != 0) return "9000".hexBytes()
            val body = fragments.also { fragments = byteArrayOf() }
            if (command[0] == 0.toByte()) {
                assertEquals(0x87, ins); assertEquals(0x2e, command[2].toInt()); assertEquals(4, command[3].toInt())
                val confirm = bytes("confirmation").also { if (badConfirmation) it[0]++ }
                output = PivTlv.encode(0x7c, PivTlv.encode(0x82, byteArrayOf(0) + bytes("nonce") + confirm + cicc))
                return next()
            }
            assertEquals(0x0c, command[0].toInt())
            if (failure == "before") throw TransportException(Dispatch.NOT_DISPATCHED)
            protectedCommands++
            lastProtected = body
            val fields = PivTlv.parse(body)
            val header = command.copyOf(4) + byteArrayOf(0x80.toByte()) + ByteArray(11)
            cchain = Cs7.mac(keyBytes.copyOfRange(32, 64), cchain + header + body.copyOf(body.size - 10))
            assertArrayEquals(cchain.copyOf(8), fields.getValue(0x8e))
            val input = fields[0x87]?.let {
                val plain = crypt(it.copyOfRange(1, it.size), false, Cipher.DECRYPT_MODE)
                var end = plain.lastIndex
                while (plain[end] == 0.toByte()) end--
                assertEquals(0x80.toByte(), plain[end]); plain.copyOf(end)
            } ?: byteArrayOf()
            if (failure == "lost") throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            val p2 = command[3].toInt() and 255
            var status = 0x9000
            val reply = when (ins) {
                0x20 -> {
                    if (p2 == 0x98) { assertArrayEquals("12345678".toByteArray(), input); status = pairingStatus }
                    else { status = pinStatus; if (input.isNotEmpty()) pinTries++ }
                    byteArrayOf()
                }
                0xf7 -> metadata()
                0x87 -> PivTlv.encode(0x7c, PivTlv.encode(0x82, privateOperation(input)))
                0xcb -> ByteArray(256) { it.toByte() }
                else -> error("Unexpected protected command")
            }
            if (failure == "outer") return "9000".hexBytes()
            if (failure == "short") return byteArrayOf(0)
            val encrypted = if (failure == "padding") PivTlv.encode(0x87, byteArrayOf(1) + crypt(ByteArray(16), true, Cipher.ENCRYPT_MODE))
                else if (reply.isEmpty()) byteArrayOf() else PivTlv.encode(0x87, byteArrayOf(1) +
                    crypt((reply + byteArrayOf(0x80.toByte())).copyOf((reply.size / 16 + 1) * 16), true, Cipher.ENCRYPT_MODE))
            val objects = encrypted + PivTlv.encode(0x99, byteArrayOf((status ushr 8).toByte(), status.toByte()))
            rchain = Cs7.mac(keyBytes.copyOfRange(96, 128), rchain + objects)
            output = objects + PivTlv.encode(0x8e, rchain.copyOf(8)).also { if (failure == "mac") it[it.lastIndex]++ }
            counter++
            return next()
        }
        private fun next(): ByteArray {
            val part = output.copyOf(minOf(120, output.size))
            output = output.copyOfRange(part.size, output.size)
            return part + if (output.isEmpty()) "9000".hexBytes() else "6100".hexBytes()
        }
        private fun crypt(data: ByteArray, response: Boolean, mode: Int): ByteArray {
            val key = SecretKeySpec(keyBytes.copyOfRange(64, 96), "AES")
            val ctr = ByteArray(16).also { it[15] = counter.toByte(); if (response) it[0] = 0x80.toByte() }
            val iv = Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, key); doFinal(ctr) }
            return Cipher.getInstance("AES/CBC/NoPadding").run { init(mode, key, IvParameterSpec(iv)); doFinal(data) }
        }
        private fun metadata(): ByteArray {
            val encoded = if (algorithm == KeyAlgorithm.RSA2048) (slotKey.public as RSAPublicKey).let {
                PivTlv.encode(0x81, unsigned(it.modulus)) + PivTlv.encode(0x82, unsigned(it.publicExponent))
            } else PivTlv.encode(0x86, point(slotKey.public as ECPublicKey))
            return PivTlv.encode(1, byteArrayOf(algorithm.code.toByte())) + PivTlv.encode(2, byteArrayOf(2, 1)) +
                PivTlv.encode(3, byteArrayOf(0)) + PivTlv.encode(4, encoded)
        }
        private fun privateOperation(input: ByteArray): ByteArray {
            val fields = PivTlv.parse(PivTlv.one(input, 0x7c))
            if (algorithm == KeyAlgorithm.RSA2048) return Cipher.getInstance("RSA/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, slotKey.private); doFinal(fields.getValue(0x81))
            }
            fields[0x85]?.let {
                val key = slotKey.public as ECPublicKey
                val size = (it.size - 1) / 2
                val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(
                    BigInteger(1, it.copyOfRange(1, 1 + size)), BigInteger(1, it.copyOfRange(1 + size, it.size))), key.params))
                return KeyAgreement.getInstance("ECDH").run { init(slotKey.private); doPhase(peer, true); generateSecret() }
            }
            return Signature.getInstance("NONEwithECDSA").run { initSign(slotKey.private); update(fields.getValue(0x81)); sign() }
        }
        private fun unsigned(value: BigInteger) = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        private fun point(key: ECPublicKey): ByteArray {
            val size = (key.params.curve.field.fieldSize + 7) / 8
            fun coord(value: BigInteger) = unsigned(value).let { ByteArray(size - it.size) + it }
            return byteArrayOf(4) + coord(key.w.affineX) + coord(key.w.affineY)
        }
    }
}
