package cc.cardkey.tls

import android.graphics.Insets
import android.hardware.usb.UsbDevice
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.*
import cc.cardkey.protocol.*
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

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TlsPanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val f = TlsFixture()
    private val clear = mutableIntStateOf(0)
    private val tls = mutableStateOf(TlsQualificationState("https://localhost/qualify", "CN=Synthetic Root", revision = 1))
    private val manager = mutableStateOf(ManagerState())
    private var submitted: TlsQualificationInput? = null
    private var cancelled = 0
    private fun render(width: Int, height: Int, check: (ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        activity.enableEdgeToEdge()
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "tls-panel-device")
        val reader = UsbReaderDescriptor(device, 1, CardWire.APDU)
        manager.value = ManagerState(selectedReader = reader, nfc = false,
            recognition = CardRecognition(reader.routeKey, 1, CardFamily.PIV))
        val enrollment = EnrollmentState("https://localhost", f.csr.cn, true, f.csr, f.bundle, EnrollmentStage.IMPORTED)
        activity.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    ManagerScreen(manager.value, clear.intValue, {}, {}, { it.close() }, { cancelled++ }, {}, null,
                        {}, {}, {}, {}, enrollment, { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                        tls.value, { tls.value = tls.value.copy(address = it, revision = tls.value.revision + 1) }, {},
                        { submitted = it }, cc.cardkey.signing.SigningFileState(), { _, _ -> }, {}, cc.cardkey.ui.InventoryState(), { _, _ -> }, { _, _ -> }, {}, {}, protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(),
                            onProtectedPivDocument = {}, onProtectedPivClear = {},
                            onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
                }
            }
        }
        try {
            compose.waitForIdle()
            compose.onNode(hasText("设置") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("申请 PIV 证书").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            check(activity)
        } finally { submitted?.close(); controller.pause().stop().destroy() }
    }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun pin() = compose.onNodeWithTag("tls-pin").performScrollTo().performTextInput("123456")
    private fun composeRoot(view: View): ViewRootForTest? =
        if (view is ViewRootForTest) view else (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { composeRoot(group.getChildAt(it)) }
        }

    @Test fun compactConfirmationShowsFixedEndpointIdentityAndClearsAfterPause() {
        render(360, 480) {
            pin(); click("tls-start")
            compose.onNodeWithText("将向 https://localhost/qualify 发送所选证书，并使用卡内私钥进行一次认证签名。PIN 输入错误会减少剩余重试次数。确认继续？").assertIsDisplayed()
            compose.onNodeWithText("确认").assertIsDisplayed()
            compose.runOnIdle { clear.intValue++ }
            compose.waitForIdle()
            compose.onNodeWithText("确认").assertDoesNotExist()
            assertNull(submitted)
            compose.onNodeWithTag("tls-pin").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            pin(); click("tls-start")
            compose.onNodeWithText("确认").performClick()
            assertEquals("https://localhost/qualify", submitted!!.address)
            assertSame(f.bundle, submitted!!.bundle)
            assertArrayEquals("123456".toCharArray(), submitted!!.pin)
            compose.onNodeWithTag("tls-pin").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        }
    }

    @Test fun landscapeImeKeepsTlsFieldsAndActionReachable() {
        render(800, 480) { activity ->
            compose.runOnIdle {
                requireNotNull(composeRoot(activity.window.decorView)).view.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 240))
                    .setVisible(WindowInsets.Type.ime(), true).build())
            }
            compose.waitForIdle()
            compose.onNodeWithTag("tls-endpoint").performScrollTo().assertIsDisplayed()
            pin()
            compose.onNodeWithTag("tls-start").performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertTrue(compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.height >= 80)
        }
    }

    @Test fun busyCleanupDisablesConflictingActionsAndRecoveryRemainsReadOnly() {
        render(360, 480) {
            pin()
            compose.runOnIdle {
                manager.value = manager.value.copy(busy = true)
                tls.value = tls.value.copy(running = true)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("tls-root").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("tls-endpoint").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("tls-start").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("tls-pin").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(""))).assertIsNotEnabled()
            compose.onNode(hasText("通行密钥") and hasClickAction()).assertIsNotEnabled()
            click("tls-cancel")
            assertEquals(1, cancelled)
            compose.onNodeWithTag("tls-start").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle {
                manager.value = manager.value.copy(busy = false, requiresInspection = true,
                    result = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE))
                tls.value = tls.value.copy(running = false, result = PivTlsResult(PivTlsOutcome.CANCELLED,
                    OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE), true))
            }
            compose.waitForIdle()
            compose.onNodeWithText("已取消连接验证。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("认证可能已提交，请核对服务器记录。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("tls-start").assertDoesNotExist()
            compose.onNodeWithTag("tls-pin").assertDoesNotExist()
            compose.onNodeWithTag("tls-root").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("tls-endpoint").performScrollTo().assertIsEnabled()
        }
    }
}
