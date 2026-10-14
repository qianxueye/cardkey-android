package cc.cardkey.protocol.piv

import cc.cardkey.protocol.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.Assert.*
import org.junit.Test

class PivClientTest {
    private class FakeCard(override val medium: Medium = Medium.CONTACT,
                           val algorithm: KeyAlgorithm = KeyAlgorithm.P256) : CardChannel {
        override val maxCommandBytes = 261
        val commands = mutableListOf<Apdu>()
        val rawCommands = mutableListOf<ByteArray>()
        var closed = false
        var pinRemaining = 3
        var pukRemaining = 3
        var corruptSignature = false
        var certificate: ByteArray? = null
        var slotMetadata: ByteArray? = null
        var managementAlgorithm = ManagementAlgorithm.TDES
        var managementKey = ByteArray(24) { (it % 8 + 1).toByte() }
        private val witness = ByteArray(16) { (it + 1).toByte() }
        private val fragments = ByteArrayOutputStream()
        val keyPair: KeyPair = KeyPairGenerator.getInstance(if (algorithm == KeyAlgorithm.RSA2048) "RSA" else "EC").apply {
            if (this@FakeCard.algorithm == KeyAlgorithm.RSA2048) initialize(2048)
            else initialize(ECGenParameterSpec(this@FakeCard.algorithm.curve))
        }.generateKeyPair()
        override fun close() { closed = true }
        override fun transmit(command: ByteArray): ByteArray {
            rawCommands += command.copyOf()
            val length = if (command.size > 5) command[4].toInt() and 255 else 0
            val data = if (length == 0) byteArrayOf() else command.copyOfRange(5, 5 + length)
            fragments.write(data)
            if (command[0].toInt() and 0x10 != 0) return "9000".hexBytes()
            val apdu = Apdu(ins = command[1].toInt() and 255, p1 = command[2].toInt() and 255,
                p2 = command[3].toInt() and 255, data = fragments.toByteArray())
            fragments.reset()
            commands += apdu
            val result = when (apdu.ins) {
                0xa4 -> { assertArrayEquals("a000000308000010000100".hexBytes(), data); byteArrayOf() }
                0xfd -> byteArrayOf(1, 2, 3)
                0xf8 -> byteArrayOf(0, 0, 0, 7)
                0xf7 -> when (apdu.p2) {
                    0x80, 0x81 -> PivTlv.encode(5, byteArrayOf(1)) + PivTlv.encode(6, byteArrayOf(3,
                        (if (apdu.p2 == 0x80) pinRemaining else pukRemaining).toByte()))
                    0x9b -> PivTlv.encode(1, byteArrayOf(managementAlgorithm.code.toByte())) + PivTlv.encode(5, byteArrayOf(0))
                    else -> slotMetadata ?: metadata()
                }
                0xcb -> certificate?.let { PivTlv.encode(0x53, PivTlv.encode(0x70, it) + PivTlv.encode(0x71, byteArrayOf(0))) }
                    ?: return "6a82".hexBytes()
                0x20 -> { assertEquals(8, data.size); byteArrayOf() }
                0x24, 0x2c -> { assertEquals(16, data.size); byteArrayOf() }
                0x47 -> PivTlv.encode(0x7f49, encodedKey())
                0xdb -> {
                    val fields = PivTlv.parse(apdu.data)
                    certificate = PivTlv.parse(fields.getValue(0x53)).getValue(0x70)
                    byteArrayOf()
                }
                0xff -> {
                    managementAlgorithm = ManagementAlgorithm.entries.single { it.code == data[0].toInt() and 255 }
                    managementKey = data.copyOfRange(3, data.size)
                    byteArrayOf()
                }
                0xfb -> { pinRemaining = 3; pukRemaining = 3; byteArrayOf() }
                0x87 -> {
                    val fields = PivTlv.parse(PivTlv.one(apdu.data, 0x7c))
                    if (apdu.p2 == 0x9b) {
                        val cipher = Cipher.getInstance("${managementAlgorithm.cipher}/ECB/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(managementKey, managementAlgorithm.cipher))
                        if (fields[0x80]?.isEmpty() == true) PivTlv.encode(0x7c,
                            PivTlv.encode(0x80, cipher.doFinal(witness.copyOf(managementAlgorithm.blockSize))))
                        else {
                            assertArrayEquals(witness.copyOf(managementAlgorithm.blockSize), fields[0x80])
                            PivTlv.encode(0x7c, PivTlv.encode(0x82, cipher.doFinal(fields.getValue(0x81))))
                        }
                    } else {
                        val input = fields[0x81]
                        val output = when {
                            algorithm == KeyAlgorithm.RSA2048 -> Cipher.getInstance("RSA/ECB/NoPadding").run {
                                init(Cipher.DECRYPT_MODE, keyPair.private); doFinal(input)
                            }
                            fields.containsKey(0x85) -> {
                                val point = fields.getValue(0x85)
                                val ec = keyPair.public as ECPublicKey
                                val size = (point.size - 1) / 2
                                val publicKey = KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(
                                    java.security.spec.ECPoint(BigInteger(1, point.copyOfRange(1, size + 1)),
                                        BigInteger(1, point.copyOfRange(size + 1, point.size))), ec.params))
                                KeyAgreement.getInstance("ECDH").run { init(keyPair.private); doPhase(publicKey, true); generateSecret() }
                            }
                            else -> Signature.getInstance("NONEwithECDSA").run { initSign(keyPair.private); update(input); sign() }
                        }
                        if (corruptSignature) output[output.lastIndex] = (output.last().toInt() xor 1).toByte()
                        PivTlv.encode(0x7c, PivTlv.encode(0x82, output))
                    }
                }
                else -> error("Unexpected INS ${apdu.ins}")
            }
            return result + "9000".hexBytes()
        }
        fun metadata() = PivTlv.encode(1, byteArrayOf(algorithm.code.toByte())) +
            PivTlv.encode(2, byteArrayOf(2, 1)) + PivTlv.encode(3, byteArrayOf(0)) + PivTlv.encode(4, encodedKey())
        private fun encodedKey(): ByteArray {
            val key = keyPair.public
            if (key is RSAPublicKey) return PivTlv.encode(0x81, unsigned(key.modulus, 256)) + PivTlv.encode(0x82, key.publicExponent.toByteArray())
            key as ECPublicKey
            val size = if (algorithm == KeyAlgorithm.P256) 32 else 48
            return PivTlv.encode(0x86, byteArrayOf(4) + unsigned(key.w.affineX, size) + unsigned(key.w.affineY, size))
        }
        private fun unsigned(value: BigInteger, size: Int) = value.toByteArray().let {
            if (it.size > size) it.copyOfRange(it.size - size, it.size) else ByteArray(size - it.size) + it
        }
    }

    @Test fun rejectsEverySensitiveOperationBeforeSelectOnNfcAndUnknown() {
        for (medium in listOf(Medium.NFC, Medium.UNKNOWN)) {
            val operations: List<(PivClient) -> Unit> = listOf(
                { it.verifyPin(pin()) }, { it.changePin(pin(), pin()) }, { it.changePuk(pin(), pin()) },
                { it.unblockPin(pin(), pin()) }, { it.authenticateManagement(ManagementAlgorithm.TDES, ByteArray(24)) },
                { it.changeManagementKey(ManagementAlgorithm.TDES, ByteArray(24), ManagementAlgorithm.AES128, ByteArray(16)) },
                { it.generateKey(0x9a, KeyAlgorithm.P256, PinPolicy.ONCE, ManagementAlgorithm.TDES, ByteArray(24)) },
                { it.createCsr(0x9a, pin(), "CN=Synthetic") }, { it.importCertificate(0x9a, byteArrayOf(), ManagementAlgorithm.TDES, ByteArray(24)) },
                { it.verifySignature(0x9a, pin()) }, { it.verifyKeyAgreement(0x9d, pin()) },
                { it.verifyRsaDecryption(0x9d, pin()) }, { it.reset() })
            operations.forEach { operation ->
                val card = FakeCard(medium)
                val result = CardSession(card).run { operation(PivClient(it)) }
                assertEquals(FailureCode.CONTACT_REQUIRED, (result as OperationResult.Failed).code)
                assertTrue(card.rawCommands.isEmpty())
            }
        }
    }
    @Test fun publicReadsUseStandardAidAndMetadataOnNfc() {
        val card = FakeCard(Medium.NFC)
        val result = CardSession(card).run { session ->
            val client = PivClient(session)
            assertEquals(7L, client.readInfo().serial)
            assertEquals(card.keyPair.public, client.readSlot(0x9a).metadata!!.publicKey)
        }
        assertTrue(result is OperationResult.Completed)
        assertTrue(card.commands.all { it.ins in listOf(0xa4, 0xfd, 0xf8, 0xf7, 0xcb) })
    }
    @Test fun emptySlotMetadataHasValidatedPolicyAndOriginOnContact() {
        val card = FakeCard().apply { slotMetadata = "02020301030100".hexBytes() }
        val session = CardSession(card)
        val slot = PivClient(session).readSlot(0x9c)
        assertEquals(0x9c, slot.slot)
        assertNotNull(slot.metadata)
        assertEquals(3, slot.metadata!!.pinPolicy)
        assertEquals(1, slot.metadata.touchPolicy)
        assertTrue(slot.metadata.generated)
        assertNull(slot.metadata.algorithm)
        assertNull(slot.metadata.publicKey)
        assertNull(slot.certificate)
        assertEquals(listOf(0xa4, 0xf7, 0xcb), card.commands.map { it.ins })
        assertEquals(Effect.NONE, session.effect)
    }

    @Test fun emptySlotRejectsPrivateOperationsBeforePinOrManagementAuthentication() {
        val cert = certificate(FakeCard().keyPair)
        val operations: List<(PivClient, CharArray, ByteArray) -> Unit> = listOf(
            { client, pin, _ -> client.createCsr(0x9c, pin, "CN=Synthetic") },
            { client, pin, _ -> client.verifySignature(0x9c, pin) },
            { client, pin, _ -> client.verifyKeyAgreement(0x9c, pin) },
            { client, pin, _ -> client.verifyRsaDecryption(0x9c, pin) },
            { client, _, admin -> client.importCertificate(0x9c, cert, ManagementAlgorithm.TDES, admin) }
        )
        for (operation in operations) {
            val card = FakeCard().apply { slotMetadata = "02020201030100".hexBytes() }
            val result = CardSession(card).run { operation(PivClient(it), pin(), card.managementKey.copyOf()) }
            assertEquals(FailureCode.CARD_REJECTED, (result as OperationResult.Failed).code)
            assertEquals(0x6a88, result.status)
            assertEquals(Effect.NONE, result.effect)
            assertEquals(listOf(0xa4, 0xf7), card.commands.map { it.ins })
        }
    }

    @Test fun outOfProfileSlotsRejectBeforeAnyApdu() {
        val card = FakeCard()
        val cert = certificate(card.keyPair)
        val operations: List<(PivClient, Int) -> Unit> = listOf(
            { client, slot -> client.readSlot(slot) },
            { client, slot -> client.generateKey(slot, KeyAlgorithm.P256, PinPolicy.ONCE,
                ManagementAlgorithm.TDES, card.managementKey.copyOf()) },
            { client, slot -> client.createCsr(slot, pin(), "CN=Synthetic") },
            { client, slot -> client.verifySignature(slot, pin()) },
            { client, slot -> client.verifyKeyAgreement(slot, pin()) },
            { client, slot -> client.verifyRsaDecryption(slot, pin()) },
            { client, slot -> client.importCertificate(slot, cert, ManagementAlgorithm.TDES, card.managementKey.copyOf()) }
        )
        for (slot in listOf(0x81, 0x9b) + (0x8d..0x95)) {
            for (operation in operations) {
                try { operation(PivClient(CardSession(card)), slot); fail("slot $slot") }
                catch (_: IllegalArgumentException) { }
                assertTrue(card.commands.isEmpty())
            }
        }
        assertEquals(listOf(0x9a, 0x9c, 0x9d, 0x9e) + (0x82..0x8c), PivClient.slots)
    }

    @Test fun importedSlotsWithoutPublicKeysRemainPopulatedOnContact() {
        for (algorithm in listOf(KeyAlgorithm.P256, KeyAlgorithm.RSA2048)) {
            val card = FakeCard().apply {
                slotMetadata = PivTlv.encode(1, byteArrayOf(algorithm.code.toByte())) + "02020201030101".hexBytes()
            }
            val slot = PivClient(CardSession(card)).readSlot(0x9c)
            assertEquals(algorithm, slot.metadata!!.algorithm)
            assertEquals(2, slot.metadata.pinPolicy)
            assertEquals(1, slot.metadata.touchPolicy)
            assertFalse(slot.metadata.generated)
            assertNull(slot.metadata.publicKey)
            assertNull(slot.certificate)
        }
    }

    @Test fun importedSlotsWithoutPublicKeysRejectProofOperationsBeforeMutation() {
        val cert = certificate(FakeCard().keyPair)
        val operations: List<(PivClient, CharArray, ByteArray) -> Unit> = listOf(
            { client, pin, _ -> client.createCsr(0x9c, pin, "CN=Synthetic") },
            { client, pin, _ -> client.verifySignature(0x9c, pin) },
            { client, pin, _ -> client.verifyKeyAgreement(0x9c, pin) },
            { client, pin, _ -> client.verifyRsaDecryption(0x9c, pin) },
            { client, _, admin -> client.importCertificate(0x9c, cert, ManagementAlgorithm.TDES, admin) }
        )
        for (operation in operations) {
            val card = FakeCard().apply { slotMetadata = "01011102020201030101".hexBytes() }
            val pin = pin()
            val admin = card.managementKey.copyOf()
            val result = CardSession(card).run { operation(PivClient(it), pin, admin) }
            assertEquals(FailureCode.UNSUPPORTED, (result as OperationResult.Failed).code)
            assertEquals(Effect.NONE, result.effect)
            assertEquals(listOf(0xa4, 0xf7), card.commands.map { it.ins })
        }
    }

    @Test fun generatedKeyReadbackStillRequiresPublicKey() {
        val card = FakeCard().apply { slotMetadata = "01011102020201030101".hexBytes() }
        val result = CardSession(card).run {
            PivClient(it).generateKey(0x9c, KeyAlgorithm.P256, PinPolicy.ONCE, ManagementAlgorithm.TDES, card.managementKey)
        }
        assertEquals(FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
        assertEquals(Effect.APPLIED, result.effect)
        assertTrue(result.writeAccepted)
        assertEquals(1, card.commands.count { it.ins == 0x47 })
    }

    @Test fun malformedEmptyAndPartialSlotMetadataRemainRejected() {
        val malformed = listOf(
            "", "02020301", "030100", "020103030100", "0203030101030100",
            "02020001030100", "02020401030100", "02020300030100", "02020304030100",
            "020203010300", "0202030103020000", "02020301030102",
            "01011102020301030100", "020203010301000400", "02020301030100050100",
            "01011102020201030101050100", "0102110002020201030101", "010111030101",
            "01011102020201030102", "010111020202010301000400"
        )
        for (encoded in malformed) {
            val card = FakeCard().apply { slotMetadata = encoded.hexBytes() }
            val result = CardSession(card).run { PivClient(it).readSlot(0x9c) }
            assertEquals(encoded, FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
            assertEquals(encoded, Effect.NONE, result.effect)
            assertEquals(encoded, listOf(0xa4, 0xf7), card.commands.map { it.ins })
        }
    }

    @Test fun rejectsMalformedTlv() {
        listOf("0180", "0102ff", "018101ff", "010100010100", "1f800100", "0183000001").forEach {
            try { PivTlv.parse(it.hexBytes()); fail(it) } catch (failure: CardFailure) { assertEquals(FailureCode.MALFORMED_RESPONSE, failure.code) }
        }
    }
    @Test fun changesPinWithExactPaddingAndWipesCallerInput() {
        val card = FakeCard()
        val old = pin(); val new = "654321".toCharArray()
        val result = CardSession(card).run { PivClient(it).changePin(old, new) }
        assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect)
        assertArrayEquals("313233343536ffff363534333231ffff".hexBytes(), card.commands.single { it.ins == 0x24 }.data)
        assertTrue(old.all { it == '\u0000' } && new.all { it == '\u0000' })
    }
    @Test fun cardKeyPukUsesSixToEightDigitsAndEightBytePadding() {
        val card = FakeCard()
        val result = CardSession(card).run { PivClient(it).changePuk("12345678".toCharArray(), "654321".toCharArray()) }
        assertTrue(result is OperationResult.Completed)
        val change = card.commands.single { it.ins == 0x24 }
        assertEquals(0x81, change.p2)
        assertArrayEquals("3132333435363738363534333231ffff".hexBytes(), change.data)
    }
    @Test fun resetNeverExhaustsRetries() {
        val card = FakeCard()
        val result = CardSession(card).run { PivClient(it).reset() }
        assertEquals(FailureCode.RECOVERY_REQUIRED, (result as OperationResult.Failed).code)
        assertTrue(card.commands.none { it.ins in listOf(0x20, 0x24, 0x2c, 0xfb) })
        val blocked = FakeCard().apply { pinRemaining = 0; pukRemaining = 0 }
        val reset = CardSession(blocked).run { PivClient(it).reset() }
        assertEquals(Effect.APPLIED, (reset as OperationResult.Completed).effect)
        assertEquals(1, blocked.commands.count { it.ins == 0xfb })
    }
    @Test fun producesCryptographicallyVerifiedCsrForAllAlgorithms() {
        KeyAlgorithm.entries.forEach { algorithm ->
            val card = FakeCard(algorithm = algorithm)
            val result = CardSession(card).run { PivClient(it).createCsr(0x9a, pin(), "CN=Synthetic Card") }
            val csr = PKCS10CertificationRequest((result as OperationResult.Completed).value)
            assertTrue(csr.isSignatureValid(JcaContentVerifierProviderBuilder().build(card.keyPair.public)))
        }
    }
    @Test fun verifiesFreshSignatureAndAgreementAndRsaDecryption() {
        KeyAlgorithm.entries.forEach { algorithm ->
            val card = FakeCard(algorithm = algorithm)
            val result = CardSession(card).run {
                val client = PivClient(it)
                assertTrue(client.verifySignature(0x9a, pin()))
                if (algorithm == KeyAlgorithm.RSA2048) assertTrue(client.verifyRsaDecryption(0x9d, pin()))
                else assertTrue(client.verifyKeyAgreement(0x9d, pin()))
            }
            assertTrue(result.toString(), result is OperationResult.Completed)
        }
    }
    @Test fun rejectsInvalidSignatureRatherThanReturningFalse() {
        val card = FakeCard().apply { corruptSignature = true }
        val result = CardSession(card).run { PivClient(it).verifySignature(0x9a, pin()) }
        assertEquals(FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
    }
    @Test fun certificateMismatchFailsBeforeAdminAuthenticationOrWrite() {
        val card = FakeCard()
        val other = FakeCard()
        val result = CardSession(card).run { PivClient(it).importCertificate(0x9a, certificate(other.keyPair), ManagementAlgorithm.TDES, card.managementKey) }
        assertEquals(FailureCode.CERTIFICATE_KEY_MISMATCH, (result as OperationResult.Failed).code)
        assertTrue(card.commands.none { it.ins == 0x87 || it.ins == 0xdb })
    }
    @Test fun certificateImportReadsBackWrittenBytes() {
        val card = FakeCard()
        val cert = certificate(card.keyPair)
        val result = CardSession(card).run { PivClient(it).importCertificate(0x9a, cert, ManagementAlgorithm.TDES, card.managementKey) }
        assertArrayEquals(cert, (result as OperationResult.Completed).value.encoded)
        assertEquals(Effect.APPLIED, result.effect)
    }
    @Test fun mutualManagementAuthenticationAndKeyChangesSupportEveryAlgorithm() {
        ManagementAlgorithm.entries.forEach { algorithm ->
            val card = FakeCard()
            val key = ByteArray(algorithm.keySize) { (it + 11).toByte() }
            val expected = key.copyOf()
            val result = CardSession(card).run { PivClient(it).changeManagementKey(ManagementAlgorithm.TDES, card.managementKey, algorithm, key) }
            assertTrue(result.toString(), result is OperationResult.Completed)
            val write = card.commands.single { it.ins == 0xff }
            assertEquals(0xff, write.p1); assertEquals(0xff, write.p2)
            assertArrayEquals(byteArrayOf(algorithm.code.toByte(), 0x9b.toByte(), key.size.toByte()) + expected, write.data)
        }
    }
    @Test fun generationUsesExplicitPoliciesAndReadsBackKey() {
        KeyAlgorithm.entries.forEach { algorithm ->
            val card = FakeCard(algorithm = algorithm)
            val result = CardSession(card).run { PivClient(it).generateKey(0x9a, algorithm, PinPolicy.ONCE, ManagementAlgorithm.TDES, card.managementKey) }
            assertEquals(card.keyPair.public, (result as OperationResult.Completed).value.publicKey)
            assertArrayEquals("ac098001".hexBytes() + byteArrayOf(algorithm.code.toByte()) + "aa0102ab0101".hexBytes(),
                card.commands.single { it.ins == 0x47 }.data)
        }
    }
    @Test fun lostPinResponseIsUnknownAndNeverReplayed() {
        val commands = mutableListOf<ByteArray>()
        val channel = object : CardChannel {
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 261
            override fun close() {}
            override fun transmit(command: ByteArray): ByteArray {
                commands += command.copyOf()
                if (command[1].toInt() and 255 == 0xa4) return "9000".hexBytes()
                throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            }
        }
        val input = pin()
        val result = CardSession(channel).run { PivClient(it).verifyPin(input) }
        assertEquals(FailureCode.TRANSPORT_FAILURE, (result as OperationResult.Failed).code)
        assertEquals(Effect.UNKNOWN, result.effect)
        assertEquals(2, commands.size)
        assertTrue(input.all { it == '\u0000' })
    }
    @Test fun malformedSignatureEncodingIsTypedFailure() {
        val card = FakeCard()
        val channel = object : CardChannel by card {
            override fun transmit(command: ByteArray): ByteArray {
                val result = card.transmit(command)
                if (command[1].toInt() and 255 != 0x87) return result
                return PivTlv.encode(0x7c, PivTlv.encode(0x82, byteArrayOf(0))) + "9000".hexBytes()
            }
        }
        val result = CardSession(channel).run { PivClient(it).verifySignature(0x9a, pin()) }
        assertEquals(FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
    }
    private fun pin() = "123456".toCharArray()
    private fun certificate(pair: KeyPair): ByteArray {
        val name = X500Name("CN=Synthetic")
        return JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)).encoded
    }
}
