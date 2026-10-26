package cc.cardkey.enrollment

import android.content.Context
import cc.cardkey.ui.ManagerViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EnrollmentSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val preferences get() = app.getSharedPreferences("enrollment_settings", Context.MODE_PRIVATE)

    @Test fun productionModelRestoresOnlyCanonicalOriginAndSupportsClear() {
        val model = ManagerViewModel(app)
        model.enrollmentSettings("https://CA.example:8443/", "Private identity")
        val restored = ManagerViewModel(app).enrollment.state.value
        assertEquals("https://ca.example:8443", restored.address)
        assertEquals(restored.address, restored.savedAddress)
        assertEquals("", restored.cn)
        assertFalse(restored.hasRoot)
        assertNull(restored.csr)
        assertEquals(mapOf("ca_origin" to "https://ca.example:8443"), preferences.all)
        model.enrollmentSettings("", "Private identity")
        assertEquals("", ManagerViewModel(app).enrollment.state.value.address)
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun invalidInputCannotReplaceLastValidAddress() {
        val model = ManagerViewModel(app)
        model.enrollmentSettings("https://CA.example:8443/", "")
        listOf("http://ca.example", "https://ca.example/path", "https://user@ca.example",
            "https://ca.example?token=secret", "https://ca.example#fragment", "https://", "https://ca.example:99999").forEach {
            model.enrollmentSettings(it, "")
            assertEquals(it, model.enrollment.state.value.address)
            assertEquals("https://ca.example:8443", ManagerViewModel(app).enrollment.state.value.address)
        }
        preferences.edit().putString("ca_origin", "http://invalid.example").commit()
        assertEquals("", ManagerViewModel(app).enrollment.state.value.address)
    }

    @Test fun endpointSwitchRetiresTrustCsrPickerAndLateIssuanceButKeepsOriginalReceipt() {
        val fixture = EnrollmentFixtures()
        val flow = EnrollmentWorkflow(EnrollmentSettings(preferences))
        flow.settings("https://localhost", fixture.csr.cn)
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        val staleCsr = flow.startCsr()
        flow.settleCsr(staleCsr, fixture.csr)
        val picker = flow.rootRequest()
        val (owner, _) = flow.startIssuance(fixture.token())
        flow.settings("https://other.example:8443", fixture.csr.cn)
        assertFalse(flow.state.value.hasRoot)
        assertTrue(runCatching { flow.ca() }.isFailure)
        assertNull(flow.state.value.csr)
        assertNull(flow.state.value.bundle)
        assertFalse(flow.acceptRoot(picker, fixture.root))
        flow.settleCsr(staleCsr, fixture.csr)
        flow.settleIssuance(owner, null, EnrollmentStage.REJECTED)
        assertNull(flow.state.value.csr)
        assertEquals(EnrollmentStage.EMPTY, flow.state.value.stage)
        assertEquals("https://localhost/1.0/sign", flow.state.value.unknownReceipts.single().endpoint)
        flow.settings("https://localhost", fixture.csr.cn)
        assertFalse(flow.state.value.hasRoot)
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        flow.settleCsr(flow.startCsr(), fixture.csr)
        assertTrue(flow.state.value.attempted)
        assertTrue(runCatching { flow.startIssuance(fixture.token()) }.isFailure)
    }

    @Test fun canonicalOriginKeepsExactNonDefaultPortSigningAudience() {
        val fixture = EnrollmentFixtures()
        val flow = EnrollmentWorkflow(EnrollmentSettings(preferences))
        flow.settings("https://LOCALHOST:8443/", fixture.csr.cn)
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        assertEquals("https://localhost:8443/1.0/sign", flow.ca().endpoint.toString())
        flow.ca().checkToken(fixture.token(address = "https://localhost:8443"), fixture.csr)
        assertTrue(runCatching { flow.ca().checkToken(fixture.token(), fixture.csr) }.isFailure)
        assertTrue(runCatching {
            flow.ca().checkToken(fixture.token { it.addProperty("aud", "https://localhost:8443/") }, fixture.csr)
        }.isFailure)
    }
}
