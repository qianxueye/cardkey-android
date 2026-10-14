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
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnrollmentPanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = EnrollmentFixtures()
    private fun render(width: Int, height: Int,
                       initial: EnrollmentState = EnrollmentState(address = "https://localhost", cn = fixture.csr.cn, hasRoot = true, csr = fixture.csr, stage = EnrollmentStage.CSR),
                       onExport: (ManagerReport) -> Unit = {},
                       check: (() -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "enrollment-ui-reader")
        val reader = UsbReaderDescriptor(device, 1, CardWire.APDU)
        val clear = mutableIntStateOf(0)
        val state = mutableStateOf(initial)
        controller.get().setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    ManagerScreen(ManagerState(selectedReader = reader, nfc = false), clear.intValue,
                        {}, {}, {}, {}, {}, null, onExport, {}, {}, {}, state.value,
                        { address, cn -> state.value = state.value.copy(address = address, cn = cn) }, {},
                        { error("A cancelled confirmation must not submit") }, { it.fill('\u0000') }, {}, TlsQualificationState(), {}, {}, { it.close() }, cc.cardkey.signing.SigningFileState(), { _, _ -> }, {}, cc.cardkey.ui.InventoryState(), { _, _ -> }, { _, _ -> }, {}, {})
                }
            }
        }
        try {
            compose.waitForIdle()
            compose.onNodeWithText("PIV").performSemanticsAction(SemanticsActions.OnClick) { it() }
            check { compose.runOnIdle { clear.intValue++ }; compose.waitForIdle() }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun portraitEnrollmentCanReachFieldsAndExportAndPauseClearsPendingPinConfirmation() {
        render(360, 640) { pause ->
            compose.onNodeWithText("通用名称（CN）").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导出 CSR").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("PIN").performScrollTo().performTextInput("123456")
            compose.onNodeWithText("生成 CSR").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("确认").assertIsDisplayed()
            pause()
            compose.onNodeWithText("确认").assertDoesNotExist()
            compose.onNodeWithText("导出 CSR").performScrollTo().assertIsEnabled()
        }
    }
    @Test fun unknownReceiptShowsOriginalCnAndCaAfterChangingTheForm() {
        val flow = EnrollmentWorkflow()
        flow.settings("https://localhost", fixture.csr.cn)
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        flow.settleCsr(flow.startCsr(), fixture.csr)
        flow.startIssuance(fixture.token())
        flow.pause()
        flow.settings("https://new-ca.invalid", "New identity")
        assertNull(flow.state.value.csr)
        render(360, 640, flow.state.value) {
            compose.onNodeWithText("New identity").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("https://new-ca.invalid").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(fixture.csr.cn).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("https://localhost/1.0/sign").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("未确认签发结果。请先向 CA 管理员核实，勿重复提交。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导出 CSR").assertDoesNotExist()
        }
    }

    @Test fun importedPanelExportsTheExactChainWithoutOfferingReimport() {
        val flow = EnrollmentWorkflow()
        flow.settings("https://localhost", fixture.csr.cn)
        flow.acceptRoot(flow.rootRequest(), fixture.root)
        flow.settleCsr(flow.startCsr(), fixture.csr)
        val (owner, _) = flow.startIssuance(fixture.token())
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(), fixture.intermediate), fixture.root, fixture.csr)
        flow.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
        flow.imported(owner)
        assertTrue(runCatching { flow.importRequest() }.isFailure)
        var exported: ManagerReport? = null
        render(360, 640, flow.state.value, { exported = it }) {
            compose.onNodeWithText("证书已写入所选槽位，并完成回读。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导出").performScrollTo().assertIsEnabled().performClick()
            val report = requireNotNull(exported)
            assertEquals("piv-9a-chain.pem", report.fileName)
            assertArrayEquals(bundle.pem().toByteArray(), report.export)
            assertEquals(2, EnrollmentProfile.certificates(requireNotNull(report.export).toString(Charsets.UTF_8)).size)
            compose.onNodeWithText("导入证书").assertDoesNotExist()
            compose.onNodeWithText("管理密钥").assertDoesNotExist()
        }
    }

    @Test fun compactEnrollmentKeepsTokenAndIssuanceReachable() {
        render(800, 240) { pause ->
            compose.onNodeWithText("一次性授权令牌").performScrollTo().assertIsDisplayed().performTextInput("synthetic-token")
            compose.onNodeWithText("申请证书").performScrollTo().assertIsDisplayed().assertIsEnabled()
            pause()
            compose.onNodeWithText("申请证书").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("导出 CSR").performScrollTo().assertIsEnabled()
        }
    }
}
