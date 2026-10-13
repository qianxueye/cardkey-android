package cc.cardkey.enrollment

import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.ui.*
import org.junit.Assert.*
import org.junit.Test

class EnrollmentContractTest {
    @Test fun realCaCardCsrBoundTokenPkixAndFreshImport() {
        StepCaFixture().use { fixture ->
            val card = EnrollmentCard()
            val pin = "123456".toCharArray()
            val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = pin, text = "Synthetic, CN=literal")
            val csr = CardOperationGate.tryAcquire()!!.use {
                try { (CardSession(card).run { ManagerOperations().execute(it, input) } as OperationResult.Completed).value.enrollmentCsr!! }
                finally { input.close() }
            }
            assertTrue(card.closed)
            assertTrue(pin.all { it == '\u0000' })
            CardOperationGate.tryAcquire()!!.close()
            val ca = EnrollmentCA(fixture.address, fixture.root)
            val token = fixture.token(csr)
            ca.checkToken(token, csr)
            val workflow = EnrollmentWorkflow()
            workflow.settings(fixture.address, csr.cn)
            workflow.acceptRoot(workflow.rootRequest(), fixture.root)
            workflow.settleCsr(workflow.startCsr(), csr)
            val (owner, _) = workflow.startIssuance(token)
            assertTrue(card.closed)
            CardOperationGate.tryAcquire()!!.close()
            val bundle = ca.execute(ca.call(csr, token), csr)
            workflow.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
            assertEquals(EnrollmentStage.VERIFIED, workflow.state.value.stage)
            assertFails { workflow.startIssuance(token) }
            val replay = runCatching { ca.execute(ca.call(csr, token), csr) }.exceptionOrNull() as EnrollmentException
            assertEquals(IssuanceFailure.REJECTED, replay.failure)
            val importInput = OperationInput(ManagerOperation.PIV_ENROLL_IMPORT, adminKey = card.managementKey.copyOf(), enrollmentImport = workflow.importRequest())
            val imported = CardOperationGate.tryAcquire()!!.use {
                try { CardSession(card).run { ManagerOperations().execute(it, importInput) } } finally { importInput.close() }
            }
            assertTrue(imported is OperationResult.Completed)
            assertArrayEquals(bundle.leaf, card.certificate)
            assertTrue(importInput.adminKey.all { it == 0.toByte() })
            val replacement = EnrollmentCard()
            val failed = CardSession(replacement).run { PivClient(it).importCertificate(0x9a, bundle.leaf, ManagementAlgorithm.TDES, replacement.managementKey.copyOf()) }
            assertEquals(FailureCode.CERTIFICATE_KEY_MISMATCH, (failed as OperationResult.Failed).code)
            assertTrue(replacement.commands.none { it.ins == 0x87 || it.ins == 0xdb })
            for (lost in listOf(true, false)) {
                card.lostWrite = lost; card.corruptReadback = !lost
                val outcome = CardSession(card).run { PivClient(it).importCertificate(0x9a, bundle.leaf, ManagementAlgorithm.TDES, card.managementKey.copyOf()) } as OperationResult.Failed
                if (lost) assertEquals(Effect.UNKNOWN, outcome.effect) else assertTrue(outcome.writeAccepted)
            }
        }
    }
    @Test fun p256AndMetadataAreCheckedBeforePinAndSignatureVerified() {
        val wrong = EnrollmentCard(algorithm = KeyAlgorithm.P384)
        val wrongPin = "123456".toCharArray()
        val failure = CardSession(wrong).run { PivClient(it).createCsr(0x9a, wrongPin, EnrollmentProfile.name("test"), KeyAlgorithm.P256) } as OperationResult.Failed
        assertEquals(FailureCode.UNSUPPORTED, failure.code)
        assertTrue(wrong.commands.none { it.ins == 0x20 || it.ins == 0x87 })
        assertTrue(wrongPin.all { it == '\u0000' })
        val corrupt = EnrollmentCard().apply { corruptSignature = true }
        assertTrue(CardSession(corrupt).run { PivClient(it).createCsr(0x9a, "123456".toCharArray(), EnrollmentProfile.name("test"), KeyAlgorithm.P256) } is OperationResult.Failed)
        val badPin = EnrollmentCard().apply { pinStatus = 0x63c2 }
        val result = CardSession(badPin).run { PivClient(it).createCsr(0x9a, "123456".toCharArray(), EnrollmentProfile.name("test"), KeyAlgorithm.P256) } as OperationResult.Failed
        assertEquals(FailureCode.PIN_INVALID, result.code); assertEquals(0x63c2, result.status); assertEquals(Effect.APPLIED, result.effect)
        assertTrue(badPin.commands.none { it.ins == 0x87 })
    }
    private fun assertFails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
}
