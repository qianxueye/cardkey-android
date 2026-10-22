package cc.cardkey.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsumerManagerTest {
    @get:Rule val compose = createEmptyComposeRule()
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
            compose.onNodeWithTag("recognition-task").assertDoesNotExist()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).performScrollTo().performTextInput("1234")
            compose.onNodeWithTag("manager-run").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(ManagerOperation.FIDO_INVENTORY, submitted?.operation)
                assertArrayEquals("1234".toCharArray(), submitted?.pin)
            }
            compose.onNodeWithText("RP SHA-256 · HEX").assertDoesNotExist()
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
