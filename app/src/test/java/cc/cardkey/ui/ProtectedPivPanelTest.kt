package cc.cardkey.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import cc.cardkey.protectedpiv.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w800dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectedPivPanelTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun issuerLineIsOmittedWhenDisplayFilteringLeavesNoText() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val rawSubject = "\u202e\u200d\u200c\n"
        val state = ProtectedPivState(issuerSubject = rawSubject, issuerFingerprint = "012345abcdef")
        host.get().setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProtectedPivPanel(state, false, false, 0, {}, {}, { _, _, _ -> }, {})
                }
            }
        }
        try {
            compose.onNodeWithText("签发者：").assertDoesNotExist()
            compose.onNodeWithText("证书指纹（SHA-256）：012345abcdef").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-profile").assertIsEnabled()
            assertEquals(rawSubject, state.issuerSubject)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun publicDisplayRemovesJoinersWithoutRemovingLettersOrCombiningMarks() {
        assertEquals("👩🔬 میرویم e\u0301\u2028\u2029",
            protectedPivDisplayText("👩\u200d🔬 می\u200cرویم e\u0301\u2028\u2029"))
    }

    @Test fun issuerDisplayRemovesFormatControlsAndKeepsSupplementaryBoundaryWithoutChangingIdentity() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val symbol = String(Character.toChars(0x1f512))
        val rawSubject = "CN=\u202e\u2066" + "a".repeat(251) + symbol + "z"
        val expectedSubject = "CN=" + "a".repeat(251) + symbol
        val state = ProtectedPivState(issuerSubject = rawSubject, issuerFingerprint = "012345abcdef",
            fileName = symbol + "文.pdf", fileSize = 3)
        host.get().setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProtectedPivPanel(state, false, false, 0, {}, {}, { _, _, _ -> }, {})
                }
            }
        }
        try {
            compose.onNodeWithText("签发者：$expectedSubject").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("签发者：$rawSubject").assertDoesNotExist()
            compose.onNodeWithText("证书指纹（SHA-256）：012345abcdef").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("文件：${symbol}文.pdf · 3 字节").performScrollTo().assertIsDisplayed()
            assertEquals(rawSubject, state.issuerSubject)
            assertEquals("012345abcdef", state.issuerFingerprint)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun recognitionNeverExposesPinAndReadyProfileDoesNotClaimLiveVerification() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var recognized: ProtectedPivAction? = null
        host.get().setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProtectedPivPanel(ProtectedPivState(profileReady = true, issuerSubject = "Synthetic issuer"),
                        false, false, 0, {}, {}, { action, _, _ -> recognized = action }, { fail("Recognition sent PIN") })
                }
            }
        }
        try {
            compose.onNodeWithTag("protected-piv-ready").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-pin").assertDoesNotExist()
            compose.onNodeWithTag("protected-piv-recognize").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(ProtectedPivAction.STATUS, recognized) }
            compose.onNodeWithTag("protected-piv-pin").assertDoesNotExist()
        } finally { host.pause().stop().destroy() }
    }

    @Test fun privateExecutionRequiresConfirmationAndPauseRetiresPinAndDialog() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val state = mutableStateOf(ProtectedPivState(generation = 1, profileReady = true, awaitingPin = true,
            action = ProtectedPivAction.VERIFY_PIN, retries = 1, erasesKeysOnFailure = true))
        val clear = mutableIntStateOf(0)
        var executions = 0
        host.get().setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProtectedPivPanel(state.value, false, false, clear.intValue, {}, {}, { _, _, _ -> fail("Unexpected recognition") },
                        { pin -> assertArrayEquals("123456".toCharArray(), pin); pin.fill('\u0000'); executions++ })
                }
            }
        }
        try {
            compose.onNodeWithTag("protected-piv-pin").performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("protected-piv-execute").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(0, executions) }
            compose.onNodeWithText("仅剩 1 次机会；输错后可能需要恢复 PIN。").assertDoesNotExist()
            compose.onAllNodesWithText("PIN 仅剩一次重试，PUK 已锁定。此次验证失败将清除 PIV 私钥。确认继续？").assertCountEquals(2)
            compose.runOnIdle { host.pause() }
            compose.onNodeWithText("开始第二次扫描").assertDoesNotExist()
            compose.runOnIdle { host.resume(); clear.intValue++ }
            compose.onNodeWithTag("protected-piv-execute").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("protected-piv-pin").performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("protected-piv-execute").performScrollTo().performClick()
            compose.onNodeWithText("开始第二次扫描").performClick()
            compose.runOnIdle { assertEquals(1, executions); state.value = state.value.copy(generation = 2, awaitingPin = false) }
            compose.onNodeWithTag("protected-piv-pin").assertDoesNotExist()
        } finally { host.pause().stop().destroy() }
    }

    @Test fun absentProfileAndPendingPickerPreventRecognition() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val state = mutableStateOf(ProtectedPivState())
        host.get().setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProtectedPivPanel(state.value, false, false, 0, {}, {}, { _, _, _ -> fail("Ineligible scan") }, { fail("Ineligible execution") })
                }
            }
        }
        try {
            compose.onNodeWithTag("protected-piv-profile").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("protected-piv-recognize").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { state.value = state.value.copy(profileReady = true, documentId = "synthetic-picker") }
            compose.onNodeWithTag("protected-piv-recognize").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("protected-piv-issuer").performScrollTo().assertIsNotEnabled()
        } finally { host.pause().stop().destroy() }
    }
    @Test fun ordinaryUnknownGuidesOriginalManagementStatusInsteadOfProtectedProfile() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProtectedPivPanel(ProtectedPivState(), false, true, 0,
                        {}, {}, { _, _, _ -> }, { fail("Unknown operation was replayed") })
                }
            }
        }
        try {
            compose.onNodeWithText("上次操作结果未知。请重新导入原卡片的配置，再读取受保护状态；不要重复执行原操作。").assertDoesNotExist()
            compose.onNodeWithText("上次操作结果未知。请保持原连接方式，检查原卡片：在「设置」中打开原操作对应的「高级 FIDO 设置与诊断」并选择「FIDO2 · 读取」，或打开「高级 PIV 管理与诊断」并选择「查看 PIV 状态」。不要重复执行原操作。")
                .performScrollTo().assertIsDisplayed()
        } finally { host.pause().stop().destroy() }
    }

    @Test fun unknownOutcomeAllowsOnlyStatusRecognitionAndKeepsOriginalOperationWarning() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var recognized: ProtectedPivAction? = null
        host.get().setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProtectedPivPanel(ProtectedPivState(profileReady = true), false, true, 0,
                        {}, {}, { action, _, _ -> recognized = action }, { fail("Unknown operation was replayed") },
                        protectedInspectionRequired = true)
                }
            }
        }
        try {
            compose.onNodeWithText("上次操作结果未知。请重新导入原卡片的配置，再读取受保护状态；不要重复执行原操作。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-issuer").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("protected-piv-recognize").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(ProtectedPivAction.STATUS, recognized) }
            compose.onNodeWithTag("protected-piv-pin").assertDoesNotExist()
        } finally { host.pause().stop().destroy() }
    }

}
