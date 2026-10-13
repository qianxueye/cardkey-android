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
    private fun render(width: Int, height: Int, check: (() -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "enrollment-ui-reader")
        val reader = UsbReaderDescriptor(device, 1, CardWire.APDU)
        val clear = mutableIntStateOf(0)
        val state = mutableStateOf(EnrollmentState(address = "https://localhost", cn = fixture.csr.cn, hasRoot = true, csr = fixture.csr, stage = EnrollmentStage.CSR))
        controller.get().setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    ManagerScreen(ManagerState(selectedReader = reader, nfc = false), clear.intValue,
                        {}, {}, {}, {}, {}, null, {}, {}, {}, {}, state.value,
                        { address, cn -> state.value = state.value.copy(address = address, cn = cn) }, {},
                        { error("A cancelled confirmation must not submit") }, { it.fill('\u0000') })
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
