package cc.cardkey.enrollment

import org.junit.Assert.*
import org.junit.Test

class EnrollmentWorkflowTest {
    private val fixture = EnrollmentFixtures()
    private fun target(identity: EnrollmentIdentity = fixture.identity) =
        EnrollmentTarget("https://issuer.example", "person-1", "card-1", "ca-1", identity)
    private fun ready(): EnrollmentWorkflow = EnrollmentWorkflow().apply {
        settings("https://localhost")
        choosePerson(target())
        assertTrue(acceptRoot(rootRequest(), fixture.root))
        settleCsr(startCsr(), fixture.csr)
    }
    @Test fun exportPausePreservesSettledPublicCsrAndBundle() {
        val flow = ready()
        val csr = flow.state.value.csr
        flow.pause()
        assertSame(csr, flow.state.value.csr)
        val (owner, _) = flow.startIssuance(fixture.token())
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(), fixture.intermediate), fixture.root, fixture.csr)
        flow.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
        flow.pause()
        assertSame(bundle, flow.state.value.bundle)
        assertNotNull(flow.importRequest())
    }
    @Test fun pauseDuringIssuanceRetainsUnknownReceiptAndRetiresLateCallback() {
        val flow = ready()
        val (owner, _) = flow.startIssuance(fixture.token())
        flow.pause()
        assertEquals(EnrollmentStage.UNKNOWN, flow.state.value.stage)
        assertTrue(flow.state.value.attempted)
        assertEquals(EnrollmentStage.UNKNOWN, flow.state.value.receipt!!.stage)
        flow.settleIssuance(owner, null, EnrollmentStage.REJECTED)
        assertEquals(EnrollmentStage.UNKNOWN, flow.state.value.stage)
        assertTrue(runCatching { flow.startIssuance(fixture.token()) }.isFailure)
    }
    @Test fun settingsRootReaderAndAbandonmentRetireEligibilityWithAbaProtection() {
        val changes: List<(EnrollmentWorkflow) -> Unit> = listOf(
            { it.settings("https://other"); it.settings("https://localhost") },
            { it.choosePerson(target(fixture.identity.copy(employeeReference = "EMP-002"))); it.choosePerson(target()) },
            { it.acceptRoot(it.rootRequest(), fixture.root) }, { it.retire() })
        changes.forEach { change ->
            val flow = ready()
            val owner = flow.generation
            change(flow)
            assertNull(flow.state.value.csr)
            flow.settleCsr(owner, fixture.csr)
            assertNull(flow.state.value.csr)
        }
    }
    @Test fun rootPickerSurvivesPauseButCannotApplyAcrossSettingsAbaOrNewPicker() {
        val flow = ready()
        val request = flow.rootRequest()
        flow.pause()
        assertTrue(flow.acceptRoot(request, fixture.root))
        val stale = flow.rootRequest()
        flow.settings("https://other"); flow.settings("https://localhost")
        assertFalse(flow.acceptRoot(stale, fixture.root))
        val first = flow.rootRequest()
        val second = flow.rootRequest()
        assertFalse(flow.acceptRoot(first, fixture.root))
        assertTrue(flow.acceptRoot(second, fixture.root))
    }
    @Test fun regeneratedIdenticalCsrCannotReopenIssuanceAfterRetireOrSelectionAba() {
        val flow = ready()
        flow.startIssuance(fixture.token())
        flow.pause()
        flow.settings("https://other")
        flow.settings("https://localhost")
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        flow.settleCsr(flow.startCsr(), fixture.csr)
        assertTrue(flow.state.value.attempted)
        assertSame(fixture.csr, flow.state.value.receipt!!.csr)
        assertTrue(runCatching { flow.startIssuance(fixture.token()) }.isFailure)
    }

    @Test fun invalidLocalTokenDoesNotSpendAttemptAndReceiptIsSeparateFromNewForm() {
        val flow = ready()
        assertTrue(runCatching { flow.startIssuance("invalid") }.isFailure)
        assertFalse(flow.state.value.attempted)
        val (owner, _) = flow.startIssuance(fixture.token())
        flow.retire()
        assertEquals(EnrollmentStage.UNKNOWN, flow.state.value.receipt!!.stage)
        flow.settleIssuance(owner, null, EnrollmentStage.REJECTED)
        assertEquals(EnrollmentStage.EMPTY, flow.state.value.stage)
        assertFalse(flow.state.value.attempted)
        assertEquals(EnrollmentStage.UNKNOWN, flow.state.value.receipt!!.stage)
    }
    @Test fun csrMustFreezeEverySelectedPersonField() {
        val flow = ready()
        val altered = fixture.identity.copy(employeeReference = "EMP-OTHER", upn = "other@apture.cc", email = "other@apture.cc")
        flow.choosePerson(target(altered))
        assertEquals(altered.displayName, flow.state.value.cn)
        val owner = flow.startCsr()
        assertTrue(runCatching { flow.settleCsr(owner, fixture.csr) }.isFailure)
        val matching = EnrollmentCsr(fixture.csr.der, altered, fixture.csr.selection)
        flow.settleCsr(owner, matching)
        assertEquals(altered, flow.prepareIssuerRequest().second.csr.identity)
    }

    @Test fun missingUpnRejectedBeforeCsrStart() {
        val flow = EnrollmentWorkflow()
        flow.settings("https://localhost")
        flow.choosePerson(target(fixture.identity.copy(upn = "")))
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        val generation = flow.generation
        assertTrue(runCatching { flow.startCsr() }.isFailure)
        assertEquals(generation, flow.generation)
        assertNull(flow.state.value.csr)
    }

}
