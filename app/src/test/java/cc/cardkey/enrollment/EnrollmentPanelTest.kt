package cc.cardkey.enrollment

import android.hardware.usb.UsbDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import cc.cardkey.protocol.piv.KeyAlgorithm
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.KeyPurposeId
import cc.cardkey.protocol.CardWire
import cc.cardkey.transport.UsbReaderDescriptor
import cc.cardkey.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnrollmentPanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = EnrollmentFixtures()
    private fun render(width: Int, height: Int,
                       initial: EnrollmentState = EnrollmentState(address = "https://localhost", target = enrollmentTarget("https://localhost", fixture.csr.identity), hasRoot = true, csr = fixture.csr, stage = EnrollmentStage.CSR),
                       onExport: (ManagerReport) -> Unit = {},
                       workflow: EnrollmentWorkflow? = null,
                       onSelection: (EnrollmentSelection) -> Unit = {},
                       check: (() -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "enrollment-ui-reader")
        val reader = UsbReaderDescriptor(device, 1, CardWire.APDU)
        val clear = mutableIntStateOf(0)
        val state = mutableStateOf(initial)
        controller.get().setContent {
            val enrollment = workflow?.state?.collectAsState()?.value ?: state.value
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    ManagerScreen(ManagerState(selectedReader = reader, nfc = false,
                        recognition = cc.cardkey.ui.CardRecognition(reader.routeKey, 1, cc.cardkey.ui.CardFamily.PIV)), clear.intValue,
                        {}, {}, {}, {}, {}, null, onExport, {}, {}, {}, enrollment,
                        { address -> if (workflow != null) workflow.settings(address) else state.value = state.value.copy(address = address) }, {},
                        { error("A cancelled confirmation must not submit") }, {}, {}, TlsQualificationState(), {}, {}, { it.close() }, cc.cardkey.signing.SigningFileState(), { _, _ -> }, {}, cc.cardkey.ui.InventoryState(), { _, _ -> }, { _, _ -> }, {}, {}, { selection ->
                            onSelection(selection)
                            if (workflow != null) workflow.selection(selection)
                            else state.value = state.value.copy(selection = selection, revision = state.value.revision + 1, csr = null)
                        }, protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(),
                            onProtectedPivDocument = {}, onProtectedPivClear = {},
                            onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
                }
            }
        }
        try {
            compose.waitForIdle()
            compose.onNode(cc.cardkey.ui.navTab("设置")).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("申请 PIV 证书").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            check { compose.runOnIdle { clear.intValue++ }; compose.waitForIdle() }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun savedAddressAndInvalidGuidanceRemainReachableInPortrait() {
        val flow = EnrollmentWorkflow().apply { settings("https://CA.example:8443/") }
        render(360, 640, workflow = flow) {
            compose.onNodeWithTag("enrollment-address").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("地址会自动保存。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("enrollment-address").performTextReplacement("http://other.example/path")
            compose.onNodeWithText("输入 HTTPS 服务地址，可包含端口；不要包含路径。").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals("https://ca.example:8443", flow.state.value.savedAddress) }
            compose.onNodeWithTag("enrollment-address").performTextClearance()
            compose.onNodeWithText("输入 CA 的 HTTPS 地址。").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals("", flow.state.value.savedAddress) }
            compose.onNodeWithTag("enrollment-root").performScrollTo().assertIsEnabled()
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-rUS-w800dp-h800dp-mdpi")
    fun englishSavedAddressGuidanceFitsCompactLandscape() {
        val flow = EnrollmentWorkflow().apply { settings("https://ca.example:8443") }
        render(800, 240, workflow = flow) {
            compose.onNodeWithTag("enrollment-address").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Address saved automatically.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("enrollment-root").performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test fun addressEditClearsPinAndOldRequestWithoutEditableIdentityOrToken() {
        val flow = EnrollmentWorkflow().apply {
            settings("https://localhost"); choosePerson(enrollmentTarget("https://localhost", fixture.csr.identity))
            acceptRoot(rootRequest(), fixture.root)
            settleCsr(startCsr(), fixture.csr)
        }
        render(360, 640, workflow = flow) {
            compose.onNodeWithTag("enrollment-pin").performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("enrollment-token").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-cn").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-address").performScrollTo().performTextReplacement("https://other.example:8443")
            compose.onNodeWithTag("enrollment-token").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-export-csr").assertDoesNotExist()
            compose.onNodeWithText("123456").assertDoesNotExist()
            compose.runOnIdle { assertFalse(flow.state.value.hasRoot) }
            compose.onNodeWithTag("enrollment-create-csr").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun documentSelectionDefaultsTo9cAndClearsOldCsrAndSecrets() {
        render(360, 640) {
            compose.onNodeWithText("当前 PIV PIN").performScrollTo().performTextInput("123456")
            compose.onNode(hasText("文档签名") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("9C").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(cc.cardkey.R.string.enrollment_documentScope)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导出 CSR").assertDoesNotExist()
            compose.onNodeWithText("123456").assertDoesNotExist()
        }
    }
    @Test fun keyManagementPurposeLocksSlot9dAndRsa2048WithoutDocumentAlgorithmPicker() {
        val changes = mutableListOf<EnrollmentSelection>()
        render(360, 640, onSelection = { changes += it }) {
            val label = org.robolectric.RuntimeEnvironment.getApplication().getString(cc.cardkey.R.string.enrollment_keyManagement)
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithText("RSA-2048 · 9D").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("P384").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-cn").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-token").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf(EnrollmentSelection(EnrollmentPurpose.KEY_MANAGEMENT, 0x9d, KeyAlgorithm.RSA2048)), changes)
            }
        }
    }
    @Test fun selectedDocumentPurposePreservesRequestAndVerifiedImportUntilPurposeChanges() {
        val selection = EnrollmentSelection(EnrollmentPurpose.DOCUMENT, 0x9c, KeyAlgorithm.P384)
        val key = EnrollmentCard(algorithm = selection.algorithm).keyPair
        val csr = fixture.csr(key = key, selection = selection)
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(key = key,
            purposes = arrayOf(KeyPurposeId.getInstance(ASN1ObjectIdentifier(EnrollmentPurpose.DOCUMENT.eku)), EnrollmentFixtures.oid("1.3.6.1.4.1.311.10.3.12"), EnrollmentFixtures.oid(EnrollmentPurpose.KEY_MANAGEMENT.eku)),
            usage = org.bouncycastle.asn1.x509.KeyUsage.digitalSignature or org.bouncycastle.asn1.x509.KeyUsage.nonRepudiation,
            identity = csr.identity, names = EnrollmentFixtures.emailNames(csr.identity)),
            fixture.intermediate), fixture.root, csr)
        val flow = EnrollmentWorkflow().apply {
            settings("https://localhost"); choosePerson(enrollmentTarget("https://localhost", csr.identity))
            selection(selection)
            acceptRoot(rootRequest(), fixture.root)
            settleCsr(startCsr(), csr)
        }
        val changes = mutableListOf<EnrollmentSelection>()
        render(360, 640, workflow = flow, onSelection = { changes += it }) {
            val beforeCsr = flow.state.value
            compose.onNode(hasText("文档签名") and hasClickAction()).performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(changes.isEmpty())
                assertSame(beforeCsr, flow.state.value)
                assertSame(csr, flow.state.value.csr)
                val (owner, _) = flow.prepareIssuerRequest()
                flow.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
            }
            compose.onNodeWithTag("enrollment-import").performScrollTo().assertIsEnabled()
            val beforeVerified = flow.state.value
            compose.onNode(hasText("文档签名") and hasClickAction()).performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(changes.isEmpty())
                assertSame(beforeVerified, flow.state.value)
                assertEquals(selection, flow.state.value.selection)
                assertSame(csr, flow.importRequest().csr)
                assertSame(bundle, flow.importRequest().bundle)
            }
            compose.onNodeWithText("9C").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("P384").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("enrollment-import").performScrollTo().assertIsEnabled()
            compose.onNode(hasText("客户端认证（TLS）") and hasClickAction()).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(listOf(EnrollmentSelection()), changes)
                assertEquals(EnrollmentSelection(), flow.state.value.selection)
                assertTrue(flow.state.value.revision > beforeVerified.revision)
                assertNull(flow.state.value.csr)
                assertNull(flow.state.value.bundle)
                assertEquals(EnrollmentStage.EMPTY, flow.state.value.stage)
                assertTrue(runCatching { flow.importRequest() }.isFailure)
            }
            compose.onNodeWithTag("enrollment-import").assertDoesNotExist()
            compose.onNode(hasText("文档签名") and hasClickAction()).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(listOf(EnrollmentSelection(), EnrollmentSelection(EnrollmentPurpose.DOCUMENT)), changes)
                assertEquals(EnrollmentSelection(EnrollmentPurpose.DOCUMENT), flow.state.value.selection)
            }
            compose.onNodeWithText("9C").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("P256").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun portraitEnrollmentCanReachFieldsAndExportAndPauseClearsPendingPinConfirmation() {
        render(360, 640) { pause ->
            compose.onNodeWithTag("enrollment-cn").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-token").assertDoesNotExist()
            compose.onNodeWithText("导出 CSR").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("当前 PIV PIN").performScrollTo().performTextInput("123456")
            compose.onNodeWithText("生成 CSR").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("确认").assertIsDisplayed()
            pause()
            compose.onNodeWithText("确认").assertDoesNotExist()
            compose.onNodeWithText("导出 CSR").performScrollTo().assertIsEnabled()
        }
    }
    @Test fun unknownReceiptShowsOriginalCnAndCaAfterChangingTheForm() {
        val flow = EnrollmentWorkflow()
        flow.settings("https://localhost"); flow.choosePerson(enrollmentTarget("https://localhost", fixture.csr.identity))
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        flow.settleCsr(flow.startCsr(), fixture.csr)
        flow.prepareIssuerRequest()
        flow.pause()
        flow.settings("https://new-ca.invalid")
        flow.choosePerson(enrollmentTarget("https://new-ca.invalid", fixture.identity.copy(displayName = "New identity")))
        assertNull(flow.state.value.csr)
        render(360, 640, flow.state.value) {
            compose.onNodeWithText("New identity", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("https://new-ca.invalid").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("${fixture.csr.cn} · ${fixture.csr.identity.employeeReference}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("https://localhost/1.0/sign").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(cc.cardkey.R.string.enrollment_unknown)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导出 CSR").assertDoesNotExist()
        }
    }

    @Test fun importedPanelExportsTheExactChainWithoutOfferingReimport() {
        val flow = EnrollmentWorkflow()
        flow.settings("https://localhost"); flow.choosePerson(enrollmentTarget("https://localhost", fixture.csr.identity))
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        flow.settleCsr(flow.startCsr(), fixture.csr)
        val (owner, _) = flow.prepareIssuerRequest()
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(), fixture.intermediate), fixture.root, fixture.csr)
        flow.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
        flow.imported(owner)
        assertTrue(runCatching { flow.importRequest() }.isFailure)
        var exported: ManagerReport? = null
        render(360, 640, flow.state.value, { exported = it }) {
            compose.onNodeWithText("证书已写入所选槽位，并完成回读。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("enrollment-export-chain").performScrollTo().assertIsEnabled().performClick()
            val report = requireNotNull(exported)
            assertEquals("piv-9a-chain.pem", report.fileName)
            assertArrayEquals(bundle.pem().toByteArray(), report.export)
            assertEquals(2, EnrollmentProfile.certificates(requireNotNull(report.export).toString(Charsets.UTF_8)).size)
            compose.onNodeWithTag("enrollment-import").assertDoesNotExist()
            compose.onNodeWithText("管理密钥").assertDoesNotExist()
        }
    }

    @Test fun compactEnrollmentKeepsIssuanceReachableWithoutTokenOrCnInputs() {
        render(800, 240) { pause ->
            compose.onNodeWithTag("enrollment-token").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-cn").assertDoesNotExist()
            compose.onNodeWithText("申请证书").performScrollTo().assertIsDisplayed().assertIsEnabled()
            pause()
            compose.onNodeWithText("申请证书").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("导出 CSR").performScrollTo().assertIsEnabled()
        }
    }
}
