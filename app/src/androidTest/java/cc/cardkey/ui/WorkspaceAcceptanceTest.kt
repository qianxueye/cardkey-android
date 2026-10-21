package cc.cardkey.ui

import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.cardkey.MainActivity
import cc.cardkey.R
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protocol.Effect
import cc.cardkey.protocol.OperationResult
import cc.cardkey.signing.SigningFileState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkspaceAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(check: (MainActivity, (ManagerState) -> Unit, AtomicInteger) -> Unit) {
        val state = mutableStateOf(ManagerState())
        val dispatches = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { host ->
                activity = host
                host.setContent {
                    CardKeyTheme {
                        Box(Modifier.widthIn(max = 360.dp).fillMaxSize()) {
                            ManagerScreen(
                                state = state.value, clearInputs = 0,
                                onReader = {}, onRefresh = {},
                                onSubmit = { dispatches.incrementAndGet(); it.close() },
                                onCancel = {}, onImport = {}, imported = null,
                                onExport = {}, onLastRetry = {},
                                onSecureChange = { secure ->
                                    if (secure) host.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                    else host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                },
                                onProviderSettings = {}, enrollment = EnrollmentState(),
                                onEnrollmentSettings = { _, _ -> }, onEnrollmentRoot = {},
                                onEnrollmentSubmit = { dispatches.incrementAndGet(); it.close() },
                                onEnrollmentIssue = { token, _ -> dispatches.incrementAndGet(); token.fill('\u0000') },
                                onEnrollmentAbandon = {}, tls = TlsQualificationState(),
                                onTlsSettings = {}, onTlsRoot = {},
                                onTlsSubmit = { dispatches.incrementAndGet(); it.close() },
                                signingFiles = SigningFileState(), onSigningAction = { _, _ -> },
                                onSigningFile = {}, inventory = InventoryState(),
                                onInventoryRp = { _, _ -> }, onInventoryCredential = { _, _ -> },
                                onInventoryFile = {}, onInventoryAction = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
            check(activity, { next -> compose.runOnIdle { state.value = next }; compose.waitForIdle() }, dispatches)
        }
    }

    @Test fun noCardNavigationKeepsCommonAdvancedAndCertificateTasksReachableWithoutDispatch() {
        render { activity, _, dispatches ->
            compose.onNodeWithTag("recognition-task").assertDoesNotExist()
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).assertExists()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            for (piv in listOf(false, true)) {
                compose.onNode(hasText("设置") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
                compose.onNodeWithText(if (piv) "高级 PIV 管理与诊断" else "高级 FIDO 设置与诊断").performScrollTo().performClick()
                val actions = ManagerOperation.entries.filter {
                    it.piv == piv && it !in setOf(ManagerOperation.FIDO_RESET, ManagerOperation.FIDO_RPS,
                        ManagerOperation.FIDO_CREDENTIALS, ManagerOperation.FIDO_DELETE, ManagerOperation.FIDO_UPDATE,
                        ManagerOperation.PIV_ENROLL_CSR, ManagerOperation.PIV_ENROLL_IMPORT)
                }
                for (action in actions) {
                    compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
                    compose.onNodeWithTag("task-${action.name}").performScrollTo().assertIsDisplayed().performClick()
                    compose.onNodeWithTag("manager-run").assertExists()
                    compose.onNodeWithTag("recognition-task").assertDoesNotExist()
                    val secrets = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes()
                    secrets.forEach { assertEquals("", it.config[SemanticsProperties.EditableText].text) }
                    if (secrets.isNotEmpty()) assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
            }
            compose.onNode(hasText("设置") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            compose.onNodeWithText("申请 PIV 证书").performScrollTo().performClick()
            compose.onNodeWithText(activity.getString(R.string.enrollment_title)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("P-256 · 9A").assertExists()
            compose.onNodeWithTag("enrollment-address").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("enrollment-cn").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("enrollment-root").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("enrollment-pin").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-create-csr").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-admin").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-import").assertDoesNotExist()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertCountEquals(0)
            compose.onNodeWithTag("manager-run").assertDoesNotExist()
            compose.onAllNodesWithText("读卡器").onLast().performClick()
            compose.onNodeWithText(activity.getString(R.string.reader_empty)).performScrollTo().assertIsDisplayed()
            assertEquals(0, dispatches.get())
        }
    }

    @Test fun retainedResultDetailsAndRealKeyboardKeepFocusedSecretInputVisibleWithoutDispatch() {
        render { activity, update, dispatches ->
            val report = ManagerReport(listOf(ResultRow("PIN 剩余重试次数", "3 次"),
                ResultRow("公钥", "synthetic-public-key")) + (1..20).map { ResultRow("field $it", "synthetic value") },
                export = byteArrayOf(1, 2, 3), fileName = "synthetic.csr")
            update(ManagerState(recognition = CardRecognition("nfc", 1, CardFamily.FIDO2), operation = ManagerOperation.PIV_CSR,
                result = OperationResult.Completed(report, Effect.NONE)))
            compose.onNodeWithText("导出").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("PIN 剩余重试次数").assertExists()
            compose.onNodeWithText("synthetic-public-key").assertDoesNotExist()
            compose.onNodeWithTag("result-details").performScrollTo().performClick()
            compose.onNodeWithText("synthetic-public-key").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("result-details").performScrollTo().performClick()
            compose.onNodeWithText("synthetic-public-key").assertDoesNotExist()
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("task-FIDO_CHANGE_PIN").performScrollTo().performClick()
            compose.onNodeWithText("新 FIDO PIN").performScrollTo().performClick().performTextInput("123456")
            compose.runOnIdle {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                activity.window.insetsController?.show(WindowInsets.Type.ime())
            }
            compose.waitUntil(10_000) {
                activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
            compose.waitForIdle()
            val field = compose.onNodeWithText("新 FIDO PIN").assertIsFocused().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Focused input outside viewport: $field in $viewport",
                field.top >= viewport.top && field.bottom <= viewport.bottom)
            compose.runOnIdle { activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
            assertEquals(0, dispatches.get())
        }
    }
}
