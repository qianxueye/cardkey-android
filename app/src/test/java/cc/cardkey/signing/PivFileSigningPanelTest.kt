package cc.cardkey.signing

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.EnrollmentState
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
class PivFileSigningPanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val files = mutableStateOf(SigningFileState(active = true, slot = 0x9c))
    private val manager = mutableStateOf(ManagerState())
    private var submitted: OperationInput? = null
    private var picks = 0
    private fun render(width: Int, height: Int, check: (ComponentActivity, () -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "signing-panel-device")
        val reader = UsbReaderDescriptor(device, 1, CardWire.APDU)
        manager.value = ManagerState(selectedReader = reader, nfc = false,
            recognition = CardRecognition(reader.routeKey, 1, CardFamily.PIV))
        val activity = controller.get()
        activity.enableEdgeToEdge()
        activity.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    ManagerScreen(manager.value, 0, {}, {}, { submitted = it }, {}, {}, null, {}, {}, {}, {},
                        EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                        TlsQualificationState(), {}, {}, { it.close() }, files.value, { _, _ -> }, { picks++ }, InventoryState(), { _, _ -> }, { _, _ -> }, {}, {})
                }
            }
        }
        try {
            compose.waitForIdle()
            check(activity) { controller.pause().resume(); compose.waitForIdle() }
        } finally { submitted?.close(); controller.pause().stop().destroy() }
    }
    private fun pin() = compose.onAllNodes(hasText("PIN") and hasSetTextAction()).onFirst()
    private fun choose() = compose.onNodeWithTag("signing-file-picker").performScrollTo().performClick()
    private fun composeRoot(view: View): ViewRootForTest? = if (view is ViewRootForTest) view else (view as? ViewGroup)?.let {
        (0 until it.childCount).firstNotNullOfOrNull { index -> composeRoot(it.getChildAt(index)) }
    }

    @Test fun compactFileInputRequiresSelectionAndTransfersOnlyItsGeneration() = fileInput(SignatureFormat.RAW)
    @Test fun cmsFileInputRequiresSelectionAndSubmitsCmsFormatWithPinConfirmation() = fileInput(SignatureFormat.CMS)
    private fun fileInput(format: SignatureFormat) {
        files.value = files.value.copy(operation = if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN)
        render(360, 480) { _, _ ->
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            compose.onNodeWithText("选择非空文件，最大 1 MiB。").performScrollTo().assertIsDisplayed()
            pin().performScrollTo().performTextInput("123456")
            choose()
            assertEquals(1, picks)
            pin().performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.runOnIdle { files.value = files.value.copy(selection = SigningFileSelection("raw.pem", 1048576, 42)) }
            compose.onNodeWithText("raw.pem · 1048576 字节").performScrollTo().assertIsDisplayed()
            pin().performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("manager-run").performClick()
            compose.onNodeWithText("确认").performClick()
            assertEquals(format, submitted!!.signatureFormat)
            assertEquals(if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN, submitted!!.operation)
            assertEquals(42L, submitted!!.signingFileGeneration)
            assertEquals(0x9c, submitted!!.slot)
            assertTrue(submitted!!.bytes.isEmpty())
            assertArrayEquals("123456".toCharArray(), submitted!!.pin)
        }
    }

    @Test fun landscapeImeKeepsFileAndPinControlsReachableAndPauseClosesConfirmation() = pinPause(SignatureFormat.RAW)
    @Test fun cmsPauseClearsPinAndConfirmationWithImeVisible() = pinPause(SignatureFormat.CMS)
    private fun pinPause(format: SignatureFormat) {
        files.value = files.value.copy(operation = if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN)
        files.value = files.value.copy(selection = SigningFileSelection("synthetic.raw", 100, 3))
        render(800, 480) { activity, pause ->
            compose.runOnIdle {
                requireNotNull(composeRoot(activity.window.decorView)).view.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 240)).setVisible(WindowInsets.Type.ime(), true).build())
            }
            compose.waitForIdle()
            compose.onNodeWithTag("signing-file-picker").performScrollTo().assertIsDisplayed()
            pin().performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("manager-run").assertIsEnabled().performClick()
            compose.onNodeWithText("确认").assertIsDisplayed()
            pause()
            compose.onNodeWithText("确认").assertDoesNotExist()
            assertNull(submitted)
            pin().performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            assertTrue(compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.height >= 80)
        }
    }

    @Test fun exportWritingAndUnknownRemainSeparateFromCardRecoveryBlocking() {
        render(360, 480) { _, _ ->
            val report = ManagerReport(emptyList(), byteArrayOf(1, 2, 3), "signature.bin", signatureFormat = SignatureFormat.RAW)
            compose.runOnIdle {
                manager.value = manager.value.copy(operation = ManagerOperation.PIV_SIGN, result = OperationResult.Completed(report, Effect.APPLIED))
                files.value = files.value.copy(exportOwner = report, exportStatus = SignatureExportStatus.WRITING)
            }
            compose.waitForIdle()
            compose.onNodeWithText("导出").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { files.value = files.value.copy(exportStatus = SignatureExportStatus.UNKNOWN) }
            compose.waitForIdle()
            compose.onNodeWithText("未确认导出结果。请检查所选文件。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("已导出签名。").assertDoesNotExist()
            compose.runOnIdle {
                manager.value = manager.value.copy(result = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
                    dispatch = Dispatch.RESPONSE_UNAVAILABLE), requiresInspection = true)
                files.value = files.value.copy(selection = SigningFileSelection("next.raw", 3, 7))
            }
            compose.waitForIdle()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
        }
    }
    @Test fun cmsVerifiedScopeAndTrustRemainVisibleWhenExportIsUnknown() {
        files.value = files.value.copy(operation = ManagerOperation.PIV_CMS_SIGN)
        render(360, 480) { _, _ ->
            compose.onNodeWithText("需要有效的文档签名证书，且证书公钥与所选槽位一致。").performScrollTo().assertIsDisplayed()
            val report = ManagerReport(emptyList(), byteArrayOf(1), "signature.p7s", signatureFormat = SignatureFormat.CMS,
                messages = listOf(cc.cardkey.R.string.signing_cmsVerified, cc.cardkey.R.string.signing_cmsScope, cc.cardkey.R.string.signing_cmsTrust))
            compose.runOnIdle {
                manager.value = manager.value.copy(operation = ManagerOperation.PIV_CMS_SIGN, result = OperationResult.Completed(report, Effect.APPLIED))
                files.value = files.value.copy(exportOwner = report, exportStatus = SignatureExportStatus.UNKNOWN)
            }
            compose.onNodeWithText("CMS 签名验证：通过").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导出 .p7s 独立签名，包含签名证书；验证时还需原文件。原文件保持不变。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("未验证证书信任链、吊销状态或可信时间。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("未确认导出结果。请检查所选文件。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("已导出签名。").assertDoesNotExist()
        }
    }

}
