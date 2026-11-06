package cc.cardkey.enrollment

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EnrollmentRecoveryTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("enrollment_recovery_test", Context.MODE_PRIVATE)
    private val fixture = EnrollmentFixtures()
    private val target = EnrollmentTarget("https://issuer.example:8443", "person-42", "card-73", "ca-19", fixture.identity)

    @Before fun clearSettings() { preferences.edit().clear().commit() }

    private fun ready(store: EnrollmentSettings = EnrollmentSettings(preferences)) = EnrollmentWorkflow(store).apply {
        settings("https://localhost")
        choosePerson(target)
        acceptRoot(rootRequest(), fixture.root)
        settleCsr(startCsr(), fixture.csr)
    }

    @Test fun preparationPersistsFrozenRequestBeforeReturningAndRecreationBlocksMutation() {
        val flow = ready()
        val (owner, receipt) = flow.prepareIssuerRequest()
        val restored = EnrollmentWorkflow(EnrollmentSettings(preferences))
        val frozen = restored.state.value.receipt!!
        assertEquals(EnrollmentStage.UNKNOWN, restored.state.value.stage)
        assertEquals(target, restored.state.value.target)
        assertEquals("EMP-001", frozen.csr.identity.employeeReference)
        assertEquals("Synthetic", frozen.csr.identity.displayName)
        assertEquals("synthetic@apture.cc", frozen.csr.identity.upn)
        assertEquals("synthetic@apture.cc", frozen.csr.identity.email)
        assertEquals("person-42", frozen.target.personId)
        assertEquals("card-73", frozen.target.cardId)
        assertEquals("ca-19", frozen.target.caId)
        assertEquals("https://issuer.example:8443", frozen.target.issuerOrigin)
        assertEquals("https://localhost/1.0/sign", frozen.endpoint)
        assertArrayEquals(fixture.root, frozen.ca.anchor)
        assertArrayEquals(fixture.csr.der, frozen.csr.der)
        assertEquals(EnrollmentPurpose.TLS, frozen.csr.selection.purpose)
        assertEquals(0x9a, frozen.csr.selection.slot)
        assertEquals(fixture.csr.selection.algorithm, frozen.csr.selection.algorithm)
        assertEquals(receipt.issuanceId, frozen.issuanceId)
        assertEquals(receipt.registrationKey, frozen.registrationKey)
        assertEquals(receipt.mintKey, frozen.mintKey)
        assertEquals(receipt.resultKey, frozen.resultKey)
        assertEquals(receipt.certificateId, frozen.certificateId)
        assertEquals(5, setOf(frozen.issuanceId, frozen.registrationKey, frozen.mintKey, frozen.resultKey, frozen.certificateId).size)
        assertTrue(runCatching { restored.prepareIssuerRequest() }.isFailure)
        assertTrue(runCatching { restored.startIssuance(fixture.token()) }.isFailure)
        assertEquals(owner, flow.startIssuance(fixture.token()).first)
        assertFalse(preferences.all.values.any { it.toString().contains(fixture.token()) })
    }

    @Test fun recoveryKeepsUnknownReceiptsAfterNewPersonSelectionAndBlocksSameKeyIntent() {
        val flow = ready()
        val original = flow.prepareIssuerRequest().second
        val secondTarget = target.copy(personId = "person-99", identity = fixture.identity.copy(employeeReference = "EMP-099"))
        flow.choosePerson(secondTarget)
        val secondCsr = fixture.csr(key = EnrollmentFixtures.key(), identity = secondTarget.identity)
        flow.settleCsr(flow.startCsr(), secondCsr)
        val second = flow.prepareIssuerRequest().second
        val restored = EnrollmentWorkflow(EnrollmentSettings(preferences))
        assertEquals(listOf(original.issuanceId, second.issuanceId), restored.state.value.unknownReceipts.map { it.issuanceId })
        assertEquals(secondTarget, restored.state.value.target)
        restored.retire()
        restored.choosePerson(target)
        restored.settleCsr(restored.startCsr(), fixture.csr())
        assertTrue(restored.state.value.attempted)
        assertTrue(runCatching { restored.prepareIssuerRequest() }.isFailure)
    }

    @Test fun localCaPreparationFailureCannotForgetPriorIssuerMutation() {
        val flow = ready()
        val (owner, receipt) = flow.prepareIssuerRequest()
        flow.unsubmitted(owner)
        assertEquals(EnrollmentStage.UNKNOWN, flow.state.value.stage)
        assertEquals(receipt.issuanceId, flow.state.value.unknownReceipts.single().issuanceId)
        assertTrue(runCatching { flow.prepareIssuerRequest() }.isFailure)
        assertTrue(EnrollmentWorkflow(EnrollmentSettings(preferences)).state.value.attempted)
    }

    @Test fun settledResponsePersistsStageAndAttemptAcrossRecreation() {
        val flow = ready()
        val (owner, receipt) = flow.prepareIssuerRequest()
        flow.settleIssuance(owner, null, EnrollmentStage.REJECTED)
        val restored = EnrollmentWorkflow(EnrollmentSettings(preferences))
        assertTrue(restored.state.value.unknownReceipts.isEmpty())
        assertEquals(receipt.issuanceId, restored.state.value.receipt!!.issuanceId)
        assertEquals(EnrollmentStage.REJECTED, restored.state.value.receipt!!.stage)
        restored.choosePerson(target)
        restored.acceptRoot(restored.rootRequest(), fixture.root)
        restored.settleCsr(restored.startCsr(), fixture.csr)
        assertTrue(restored.state.value.attempted)
    }

    @Test fun validatedBundleAndPendingResultSurviveRecreationWithoutAnotherCaRequest() {
        val flow = ready()
        val (owner, receipt) = flow.prepareIssuerRequest()
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(), fixture.intermediate), fixture.root, fixture.csr)
        flow.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
        val restored = EnrollmentWorkflow(EnrollmentSettings(preferences))
        assertEquals(EnrollmentStage.VERIFIED, restored.state.value.stage)
        assertEquals(bundle.verifiedAt, restored.state.value.bundle!!.verifiedAt)
        assertArrayEquals(bundle.leaf, restored.importRequest().bundle.leaf)
        assertTrue(restored.state.value.receipt!!.reportPending)
        assertEquals(receipt.resultKey, restored.state.value.receipt!!.resultKey)
        assertEquals(receipt.certificateId, restored.state.value.receipt!!.certificateId)
        restored.markResultReported(receipt.issuanceId)
        val reported = EnrollmentWorkflow(EnrollmentSettings(preferences))
        assertFalse(reported.state.value.receipt!!.reportPending)
        reported.imported(reported.generation)
        val imported = EnrollmentWorkflow(EnrollmentSettings(preferences))
        assertEquals(EnrollmentStage.IMPORTED, imported.state.value.stage)
        assertTrue(runCatching { imported.importRequest() }.isFailure)
        assertArrayEquals(bundle.leaf, imported.importedIdentity().bundle.leaf)
    }

    @Test fun expiredHistoricalBundlesDoNotBlockHealthyLatestRecoveryOrLoseUnknownReceipts() {
        val store = EnrollmentSettings(preferences)
        val flow = ready(store)
        val unknown = flow.prepareIssuerRequest().second
        val verifiedAt = fixture.now - 10000
        val expiredBundle = EnrollmentProfile.validate(listOf(fixture.leaf(from = fixture.now - 30000,
            to = fixture.now - 5000), fixture.intermediate), fixture.root, fixture.csr,
            now = verifiedAt, issuedAt = verifiedAt)
        val verified = unknown.copy(issuanceId = "historical-verified", stage = EnrollmentStage.VERIFIED,
            bundle = expiredBundle)
        val imported = verified.copy(issuanceId = "historical-imported", stage = EnrollmentStage.IMPORTED)
        val healthyBundle = EnrollmentProfile.validate(listOf(fixture.leaf(), fixture.intermediate), fixture.root, fixture.csr)
        val latest = unknown.copy(issuanceId = "healthy-latest", stage = EnrollmentStage.VERIFIED, bundle = healthyBundle)
        store.saveReceipts(listOf(unknown, verified, imported, latest))
        val restored = EnrollmentWorkflow(store)
        assertEquals("healthy-latest", restored.state.value.receipt!!.issuanceId)
        assertEquals(fixture.identity, restored.state.value.csr!!.identity)
        assertArrayEquals(healthyBundle.leaf, restored.importRequest().bundle.leaf)
        assertEquals(unknown.issuanceId, restored.state.value.unknownReceipts.single().issuanceId)
        assertEquals(fixture.identity, store.loadReceipts()[1].csr.identity)
        store.saveReceipts(listOf(verified))
        val expired = EnrollmentWorkflow(store)
        assertEquals(EnrollmentStage.VERIFIED, expired.state.value.stage)
        assertTrue(runCatching { expired.importRequest() }.isFailure)
        store.saveReceipts(listOf(imported))
        assertTrue(runCatching { EnrollmentWorkflow(store).importedIdentity() }.isFailure)
    }

    @Test fun preparedIssuanceGrantsOnlyOneCaDispatchBeforeSettlement() {
        val flow = ready()
        val (owner, receipt) = flow.prepareIssuerRequest()
        val grant = flow.startIssuance(fixture.token())
        assertEquals(owner, grant.first)
        assertArrayEquals(receipt.csr.der, grant.second.der)
        assertTrue(runCatching { flow.startIssuance(fixture.token()) }.isFailure)
        assertEquals(receipt.issuanceId, flow.state.value.receipt!!.issuanceId)
        assertEquals(1, flow.state.value.unknownReceipts.size)
        assertEquals(EnrollmentStage.ISSUING, flow.state.value.stage)
    }

    @Test fun recoveryCommitFailureStopsPreparationBeforeCallerCanMutateIssuer() {
        var fail = false
        val failingPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit() = if (fail) false else editor.commit()
                }
            }
        }
        val flow = ready(EnrollmentSettings(failingPreferences))
        val generation = flow.generation
        fail = true
        var externalMutationReached = false
        val attempt = runCatching {
            flow.prepareIssuerRequest()
            externalMutationReached = true
        }
        assertTrue(attempt.isFailure)
        assertFalse(externalMutationReached)
        assertEquals(generation, flow.generation)
        assertEquals(EnrollmentStage.CSR, flow.state.value.stage)
        assertFalse(flow.state.value.attempted)
        assertNull(flow.state.value.receipt)
        assertFalse(preferences.contains("issuance_receipts"))
    }

    @Test fun corruptRecoveryCannotSilentlyReopenIssuance() {
        preferences.edit().putString("issuance_receipts", "{broken").commit()
        assertTrue(runCatching { EnrollmentWorkflow(EnrollmentSettings(preferences)) }.isFailure)
    }
}
