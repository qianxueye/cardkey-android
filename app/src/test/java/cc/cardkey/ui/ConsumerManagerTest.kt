package cc.cardkey.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.signing.SigningFileState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import kotlinx.coroutines.flow.MutableStateFlow
import cc.cardkey.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsumerManagerTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun cancellationReceiptDistinguishesUnsentUnknownAndAcceptedEffects() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val state = mutableStateOf(ManagerState())
        activity.get().setContent {
            MaterialTheme { Box(Modifier.requiredSize(800.dp, 800.dp)) {
                ManagerScreen(state.value, 0, {}, {}, { fail("Unexpected submission") }, {}, {}, null, {}, {}, {}, {},
                    EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                    TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(), { _, _ -> }, {},
                    InventoryState(), { _, _ -> }, { _, _ -> }, {}, {},
                    protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(),
                    onProtectedPivDocument = {}, onProtectedPivClear = {},
                    onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
            } }
        }
        try {
            for (effect in listOf(Effect.NONE, Effect.UNKNOWN, Effect.APPLIED)) {
                compose.runOnIdle {
                    state.value = ManagerState(operation = ManagerOperation.FIDO_DELETE,
                        requiresInspection = effect != Effect.NONE,
                        result = OperationResult.Failed(FailureCode.CANCELLED, effect,
                            dispatch = if (effect == Effect.NONE) Dispatch.NOT_DISPATCHED else Dispatch.RESPONSE_UNAVAILABLE,
                            writeAccepted = effect == Effect.APPLIED))
                }
                val expected = when (effect) {
                    Effect.NONE -> R.string.operation_notDispatched
                    Effect.UNKNOWN -> R.string.operation_unknown
                    Effect.APPLIED -> R.string.operation_appliedReadbackFailed
                }
                compose.onNodeWithText(activity.get().getString(expected)).performScrollTo().assertIsDisplayed()
                if (effect == Effect.NONE) compose.onNodeWithText(activity.get().getString(R.string.error_cancelled)).assertExists()
                else compose.onNodeWithText(activity.get().getString(R.string.error_cancelled)).assertDoesNotExist()
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun protectedPreflightDoesNotRenderPrivateCompletionButExecutionDoes() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val model = ManagerViewModel(RuntimeEnvironment.getApplication())
        val mutable = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        val finish = ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java).apply { isAccessible = true }
        activity.get().setContent {
            val state = model.state.collectAsState().value
            MaterialTheme { Box(Modifier.requiredSize(800.dp, 800.dp)) {
                ManagerScreen(state, 0, {}, {}, { it.close() }, {}, {}, null, {}, {}, {}, {},
                    EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                    TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(), { _, _ -> }, {},
                    InventoryState(), { _, _ -> }, { _, _ -> }, {}, {},
                    protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(awaitingPin = true),
                    onProtectedPivDocument = {}, onProtectedPivClear = {},
                    onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
            } }
        }
        val receipt = activity.get().getString(R.string.workspace_receipt)
        val verification = "此结果仅验证卡片返回的密码运算、CSR 或签名，不代表证书受信任或外部账户接受。"
        try {
            for (operation in listOf(ManagerOperation.PIV_CSR, ManagerOperation.PIV_SIGN, ManagerOperation.PIV_ECDH, ManagerOperation.PIV_DECRYPT)) {
                compose.runOnIdle {
                    mutable.value = mutable.value.copy(operation = operation, busy = true)
                    finish.invoke(model, OperationResult.Completed(ManagerReport(emptyList(), protectedPivRecognition = true), Effect.NONE))
                }
                compose.onNodeWithText(receipt).assertDoesNotExist()
                compose.onNodeWithText(verification).assertDoesNotExist()
                compose.runOnIdle {
                    mutable.value = mutable.value.copy(operation = operation)
                    finish.invoke(model, OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE))
                }
                compose.onNodeWithText(receipt).assertExists()
                compose.onNodeWithText(verification).assertDoesNotExist()
            }
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun homePublicReadCanCancelTheActualPendingNfcRequest() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowNfcAdapter.setNfcHardwareExists(true)
        org.robolectric.Shadows.shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
        org.robolectric.Shadows.shadowOf(android.nfc.NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val model = ManagerViewModel(app)
        var submissions = 0
        activity.get().setContent {
            val state = model.state.collectAsState().value
            val inventory = model.inventory.state.collectAsState().value
            MaterialTheme { Box(Modifier.requiredSize(400.dp, 800.dp)) {
                ManagerScreen(state, 0, {}, {}, { submissions++; model.submit(it) }, model::cancel, {}, null, {}, {}, {}, {},
                    EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                    TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(), { _, _ -> }, {},
                    inventory, { _, _ -> }, { _, _ -> }, {}, model::inventoryAction,
                    protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(),
                    onProtectedPivDocument = {}, onProtectedPivClear = {},
                    onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
            } }
        }
        try {
            compose.onNodeWithTag("native-read-card").performScrollTo().performClick()
            assertTrue(model.state.value.busy)
            assertTrue(model.state.value.awaitingNfc)
            assertNotNull(model.pendingNfcOwner)
            compose.onNodeWithText("取消").performScrollTo().assertIsEnabled().performClick()
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.awaitingNfc)
            assertNull(model.pendingNfcOwner)
            val result = model.state.value.result as OperationResult.Failed
            assertEquals(FailureCode.CANCELLED, result.code)
            assertEquals(Effect.NONE, result.effect)
            assertEquals(1, submissions)
        } finally { model.cancel(); activity.pause().stop().destroy() }
    }

    @Test fun coldLaunchAcceptsPinBeforeCardAndNeverRequiresManualRead() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var submitted: OperationInput? = null
        var submissions = 0
        val state = mutableStateOf(ManagerState())
        activity.get().setContent {
            MaterialTheme { Box(Modifier.requiredSize(800.dp, 800.dp)) {
                ManagerScreen(state.value, 0, {}, {}, { submissions++; submitted = it }, {}, {}, null, {}, {}, {}, {},
                    EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                    TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(), { _, _ -> }, {},
                    InventoryState(), { _, _ -> }, { _, _ -> }, {}, {},
                    protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(),
                    onProtectedPivDocument = {}, onProtectedPivClear = {},
                    onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
            } }
        }
        try {
            compose.waitForIdle()
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, submissions) }
            compose.onNodeWithText(activity.get().getString(R.string.native_prepare)).performScrollTo().performClick()
            compose.onNodeWithTag("recognition-task").assertDoesNotExist()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).performScrollTo().performTextInput("1234")
            compose.onNodeWithTag("manager-run").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(ManagerOperation.FIDO_INVENTORY, submitted?.operation)
                assertArrayEquals("1234".toCharArray(), submitted?.pin)
            }
            compose.onNodeWithText("RP SHA-256 · HEX").assertDoesNotExist()
            compose.onNodeWithText("取消").performClick()
            compose.onNode(hasText("通行密钥") and hasClickAction()).performScrollTo().performClick()
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("task-FIDO_CHANGE_PIN").performScrollTo().performClick()
            compose.runOnIdle {
                state.value = ManagerState(operation = ManagerOperation.FIDO_DELETE,
                    result = OperationResult.Completed(ManagerReport(emptyList()), Effect.APPLIED))
            }
            compose.waitForIdle()
            compose.onNodeWithText("通行密钥已删除。重新读取可查看最新列表。").assertExists()
            compose.onNodeWithText("新 FIDO PIN").assertDoesNotExist()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(1, submissions) }

        } finally { submitted?.close(); activity.pause().stop().destroy() }
    }
    @Test fun loadedEmptyFailedAndStaleTargetRemainDistinct() {
        val inventory = FidoInventory()
        val read = OperationInput(ManagerOperation.FIDO_INVENTORY)
        val request = inventory.begin(read)
        assertEquals(InventoryLoad.LOADING, inventory.state.value.load)
        inventory.settle(request, ManagerReport(emptyList(), inventory = CredentialInventory(CredentialCapacity(0, 8), emptyList())))
        assertEquals(InventoryLoad.LOADED, inventory.state.value.load)
        assertTrue(inventory.state.value.credentials.isEmpty())
        inventory.failedCurrent()
        assertEquals(InventoryLoad.LOADED, inventory.state.value.load)
        inventory.retire()
        assertEquals(InventoryLoad.UNREAD, inventory.state.value.load)
        inventory.begin(OperationInput(ManagerOperation.FIDO_INVENTORY))
        inventory.failedCurrent()
        assertEquals(InventoryLoad.FAILED, inventory.state.value.load)
        val card = InventoryCard()
        seedInventory(inventory, card)
        val selected = inventory.state.value.credential!!
        val input = OperationInput(ManagerOperation.FIDO_DELETE, pin = "1234".toCharArray(),
            inventoryGeneration = inventory.state.value.generation, inventorySelection = selected, text = "ff")
        input.inventoryRequest = inventory.begin(input)
        inventory.retire()
        val channel = card.channel()
        val result = CardSession(channel).run { ManagerOperations().execute(it, input) }
        assertEquals(FailureCode.CANCELLED, (result as OperationResult.Failed).code)
        assertTrue(channel.commands.isEmpty())
        input.close()
        assertArrayEquals(CharArray(4), input.pin)
    }
}
