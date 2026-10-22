package cc.cardkey.protectedpiv

import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.ui.*
import com.google.gson.JsonParser
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.spec.*
import java.util.Date

class ProtectedPivWorkflowTest {
    private val fixture = JsonParser.parseString(File("../fixtures/cs7-v1.json").readText()).asJsonObject
    private fun bytes(name: String) = fixture[name].asString.hexBytes()
    private val issuerKey get() = Cs7.publicKey(bytes("issuer_point"))
    private fun issuer(): ByteArray {
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.valueOf(3), Cs7.curve))
        val name = X500Name("CN=Synthetic issuer")
        return JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(1000), name, issuerKey)
            .addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(bytes("issuer_id") + ByteArray(12)))
            .build(JcaContentSignerBuilder("SHA384withECDSA").build(privateKey)).encoded
    }
    private fun json(guid: String = fixture["guid"].asString) =
        """{"guid":"$guid","issuer_id":"${fixture["issuer_id"].asString}","card_public":"${fixture["card_point"].asString}","pairing":"12345678"}""".toByteArray()
    private fun workflow(file: ByteArray? = null, guid: String = fixture["guid"].asString) = ProtectedPivWorkflow().apply {
        receiveDocument(requireNotNull(requestDocument(ProtectedPivDocument.ISSUER)), issuer())
        file?.let { receiveDocument(requireNotNull(requestDocument(ProtectedPivDocument.SIGNING_FILE)), it) }
        receiveDocument(requireNotNull(requestDocument(ProtectedPivDocument.PROFILE)), json(guid))
    }
    private fun peer(key: KeyPair? = null) = PivSecureContextTest().Peer(dynamic = true, keyPair = key).apply {
        successfulPinStatus = 0x9000
        certificateAbsent = true
    }
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun run(workflow: ProtectedPivWorkflow, input: OperationInput, card: CardChannel): OperationResult<ManagerReport> {
        val result = CardSession(card).run { ManagerOperations().execute(it, input) }
        workflow.settle(requireNotNull(input.protectedScan), result)
        input.close()
        return result
    }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected refusal") } catch (_: IllegalArgumentException) { } }

    @Test fun producerImportRecognitionAndFreshExecutionReachActualSecurePinConsumer() {
        val flow = workflow()
        assertTrue(flow.state.value.profileReady)
        assertFalse(flow.state.value.awaitingPin)
        val first = peer()
        val recognition = flow.recognize(ProtectedPivAction.VERIFY_PIN, 0x9a, "")
        assertTrue(run(flow, recognition, first) is OperationResult.Completed)
        assertEquals(0, first.pinTries)
        assertTrue(flow.state.value.awaitingPin)
        val pin = "123456".toCharArray()
        val input = flow.execute(pin)
        assertTrue(pin.all { it == '\u0000' })
        fails { flow.execute("123456".toCharArray()) }
        val second = peer()
        assertTrue(run(flow, input, second) is OperationResult.Completed)
        assertEquals(1, second.pinTries)
        assertFalse(flow.state.value.profileReady)
        assertFalse(flow.state.value.awaitingPin)
        val firstHello = first.commands.first { it[1] == 0x87.toByte() }
        val secondHello = second.commands.first { it[1] == 0x87.toByte() }
        assertFalse(firstHello.contentEquals(secondHello))
        fails { flow.execute("123456".toCharArray()) }
    }

    @Test fun rawFileAndCsrUseTwoScansAndIndependentlyVerifiedSignatureConsumer() {
        for (action in listOf(ProtectedPivAction.RAW_SIGN, ProtectedPivAction.CSR, ProtectedPivAction.ECDH)) {
            val slotKey = key()
            val flow = workflow("original bytes".toByteArray())
            val first = peer(slotKey)
            assertTrue(run(flow, flow.recognize(action, 0x9a, "CN=Synthetic CSR"), first) is OperationResult.Completed)
            assertEquals(0, first.pinTries)
            val second = peer(slotKey)
            val result = run(flow, flow.execute("123456".toCharArray()), second)
            assertTrue("$action: $result", result is OperationResult.Completed)
            assertEquals(1, second.pinTries)
            if (action == ProtectedPivAction.RAW_SIGN) {
                val report = (result as OperationResult.Completed).value
                assertTrue(Signature.getInstance("SHA256withECDSA").run {
                    initVerify(slotKey.public); update("original bytes".toByteArray()); verify(report.export)
                })
            }
        }
    }

    @Test fun changedRetryOrSlotStopsBeforePinAndRetiresReceipt() {
        for (changedSlot in listOf(false, true)) {
            val slotKey = key()
            val flow = workflow()
            assertTrue(run(flow, flow.recognize(ProtectedPivAction.CSR, 0x9a, "CN=Test"), peer(slotKey)) is OperationResult.Completed)
            val second = peer(if (changedSlot) key() else slotKey).apply { if (!changedSlot) pinStatus = 0x63c3 }
            val result = run(flow, flow.execute("123456".toCharArray()), second) as OperationResult.Failed
            assertEquals(FailureCode.RECOVERY_REQUIRED, result.code)
            assertEquals(0, second.pinTries)
            assertFalse(flow.state.value.profileReady)
        }
    }

    @Test fun absentProfileUnknownMediaAndMismatchedGuidNeverDispatchPin() {
        fails { ProtectedPivWorkflow().recognize(ProtectedPivAction.VERIFY_PIN, 0x9a, "") }
        for (unknown in listOf(false, true)) {
            val flow = workflow(guid = if (unknown) fixture["guid"].asString else "00".repeat(16))
            val card = peer().apply { if (unknown) medium = Medium.UNKNOWN }
            val result = run(flow, flow.recognize(ProtectedPivAction.VERIFY_PIN, 0x9a, ""), card)
            assertTrue(result is OperationResult.Failed)
            assertEquals(0, card.pinTries)
            assertEquals(0, card.protectedCommands)
            if (unknown) assertTrue(card.commands.isEmpty())
        }
    }

    @Test fun lostPinResponseIsUnknownWithoutReplayAndKnownWrongPinKeepsEffect() {
        for (lost in listOf(false, true)) {
            val flow = workflow()
            run(flow, flow.recognize(ProtectedPivAction.VERIFY_PIN, 0x9a, ""), peer())
            val card = peer().apply {
                successfulPinStatus = 0x63c3
                if (lost) onCommand = { ins, p2, input -> if (ins == 0x20 && p2 == 0x80 && input.isNotEmpty()) failure = "lost" }
            }
            val result = run(flow, flow.execute("123456".toCharArray()), card) as OperationResult.Failed
            assertEquals(if (lost) Effect.UNKNOWN else Effect.APPLIED, result.effect)
            assertEquals(if (lost) FailureCode.TRANSPORT_FAILURE else FailureCode.PIN_INVALID, result.code)
            assertFalse(flow.state.value.profileReady)
            fails { flow.execute("123456".toCharArray()) }
        }
    }

    @Test fun contactReserveAndMissingSpkiRejectBeforePrivateCommand() {
        for (missingKey in listOf(false, true)) {
            val flow = workflow()
            val card = peer().apply {
                if (missingKey) slotMetadata = "01011102020201030100".hexBytes() else pinStatus = 0x6983
            }
            val result = run(flow, flow.recognize(if (missingKey) ProtectedPivAction.CSR else ProtectedPivAction.VERIFY_PIN, 0x9a, "CN=Test"), card)
            assertTrue(result is OperationResult.Failed)
            assertEquals(0, card.pinTries)
            assertFalse(flow.state.value.awaitingPin)
        }
    }

    @Test fun replacementBackgroundAndStalePickerCannotResurrectProfile() {
        val flow = workflow()
        val first = flow.requestDocument(ProtectedPivDocument.PROFILE)!!
        assertFalse(flow.state.value.profileReady)
        val next = flow.requestDocument(ProtectedPivDocument.PROFILE)!!
        assertFalse(flow.receiveDocument(first, json()))
        flow.retire(preserveDocument = true)
        assertTrue(flow.receiveDocument(next, json()))
        val input = flow.recognize(ProtectedPivAction.VERIFY_PIN, 0x9a, "")
        flow.retire()
        val card = peer()
        assertEquals(FailureCode.CANCELLED, (run(flow, input, card) as OperationResult.Failed).code)
        assertTrue(card.commands.isEmpty())
        assertFalse(flow.receiveDocument(next, json()))
        assertFalse(flow.state.value.profileReady)
    }

    @Test fun cancellationAfterAuthenticationAndBeforeExecutionHasNoPin() {
        val flow = workflow()
        val card = peer().apply { onCommand = { ins, p2, _ -> if (ins == 0x20 && p2 == 0x80) flow.retire() } }
        assertEquals(FailureCode.CANCELLED, (run(flow, flow.recognize(ProtectedPivAction.VERIFY_PIN, 0x9a, ""), card) as OperationResult.Failed).code)
        assertEquals(0, card.pinTries)
        assertFalse(flow.state.value.awaitingPin)
    }
}
