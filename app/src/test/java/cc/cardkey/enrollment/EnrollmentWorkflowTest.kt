package cc.cardkey.enrollment

import org.junit.Assert.*
import org.junit.Test

class EnrollmentWorkflowTest {
    private val fixture = EnrollmentFixtures()
    private fun ready(): EnrollmentWorkflow = EnrollmentWorkflow().apply {
        settings("https://localhost", fixture.csr.cn)
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
            { it.settings("https://other", fixture.csr.cn); it.settings("https://localhost", fixture.csr.cn) },
            { it.settings("https://localhost", "other"); it.settings("https://localhost", fixture.csr.cn) },
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
        flow.settings("https://other", fixture.csr.cn); flow.settings("https://localhost", fixture.csr.cn)
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
        flow.settings("https://other", "other")
        flow.settings("https://localhost", fixture.csr.cn)
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
}
