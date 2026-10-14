package cc.cardkey.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.CardSession
import cc.cardkey.protocol.Effect
import cc.cardkey.protocol.OperationResult
import cc.cardkey.signing.SigningFileState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlexoWorkspaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(width: Int, height: Int, fontScale: Float = 1f,
                       onSubmit: (OperationInput) -> Unit = { it.close(); fail("Object selection dispatched a card operation") },
                       onExport: (ManagerReport) -> Unit = {},
                       check: (ComponentActivity, (ManagerState) -> Unit, () -> Pair<ManagerOperation?, Int>?) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val state = mutableStateOf(ManagerState())
        var action: Pair<ManagerOperation?, Int>? = null
        controller.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                CardKeyTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp)) {
                        ManagerScreen(state.value, 0, {}, {}, onSubmit, {}, {}, null, onExport, {}, {}, {},
                            EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                            TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(),
                            { operation, slot -> action = operation to slot }, {}, InventoryState(),
                            { _, _ -> }, { _, _ -> }, {}, {})
                    }
                }
            }
        }
        try {
            compose.waitForIdle()
            check(controller.get(), { next -> compose.runOnIdle { state.value = next }; compose.waitForIdle() }, { action })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun compactObjectSelectionAndSystemBackPreserveSlotWithoutDispatch() {
        render(360, 640, 1.5f) { activity, _, action ->
            compose.onNodeWithTag("workspace-objects").assertExists()
            compose.onNodeWithTag("workspace-detail").assertDoesNotExist()
            compose.onNodeWithText("PIV").performClick()
            compose.onNodeWithTag("object-slot-9C").performScrollTo().performClick()
            compose.onNodeWithTag("workspace-detail").assertExists()
            compose.onNodeWithTag("workspace-objects").assertDoesNotExist()
            assertEquals(ManagerOperation.PIV_SLOT to 0x9c, action())
            compose.onNodeWithTag("manager-run").assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("workspace-objects").assertExists()
            compose.onNodeWithTag("object-slot-9C").performScrollTo().assertIsSelected()
            assertEquals(ManagerOperation.PIV_SLOT to 0x9c, action())
        }
    }

    @Test fun wideObjectSelectionKeepsBothPanesAndCanReachRetiredSlotsWithoutDispatch() {
        render(1200, 800) { _, _, action ->
            compose.onNodeWithText("PIV").performClick()
            compose.onNodeWithTag("object-slot-8C").performScrollTo().performClick()
            compose.onNodeWithTag("workspace-objects").assertExists()
            compose.onNodeWithTag("workspace-detail").assertExists()
            compose.onNodeWithTag("object-slot-8C").assertIsSelected()
            assertEquals(ManagerOperation.PIV_SLOT to 0x8c, action())
            compose.onNodeWithTag("manager-run").assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test fun capturedCertificateExportRemainsOriginalAfterObjectSelectionChanges() {
        val fixture = CertificateLifecycleFixture()
        val result = fixture.read({ fixture.start + 1 }) as OperationResult.Completed
        var exported: ManagerReport? = null
        render(360, 640, onExport = { exported = it }) { _, update, action ->
            compose.onNodeWithText("PIV").performClick()
            compose.onNodeWithTag("object-slot-9D").performScrollTo().performClick()
            update(ManagerState(operation = ManagerOperation.PIV_SLOT, result = result))
            compose.onNodeWithTag("workspace-object-back").performScrollTo().performClick()
            compose.onNodeWithTag("object-slot-9C").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(ManagerOperation.PIV_SLOT to 0x9c, action())
            compose.onNodeWithText("导出", substring = false).performScrollTo().performClick()
            assertSame(result.value, exported)
            assertEquals("piv-9d.cer", exported?.fileName)
            assertArrayEquals(fixture.der, exported?.export)
            assertEquals(Effect.NONE, result.effect)
        }
    }

    @Test fun sshDetailsAndExportRemainCapturedAfterSelectingAnotherSlot() {
        val card = EnrollmentCard()
        val result = CardSession(card).run {
            ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_SSH_EXPORT, slot = 0x9c))
        } as OperationResult.Completed
        val report = result.value
        val publicText = requireNotNull(report.export).decodeToString()
        val commandCount = card.commands.size
        var exported: ManagerReport? = null
        render(360, 640, onExport = { exported = it }) { _, update, action ->
            compose.onNodeWithText("PIV").performClick()
            compose.onNodeWithTag("object-slot-9C").performScrollTo().performClick()
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("task-PIV_SSH_EXPORT").performScrollTo().performClick()
            update(ManagerState(operation = ManagerOperation.PIV_SSH_EXPORT, result = result))
            compose.onNodeWithText("PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithText(publicText).assertDoesNotExist()
            compose.onNodeWithText("piv-9c-ssh.pub").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("result-details").performScrollTo().performClick()
            compose.onNodeWithText(publicText).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("workspace-object-back").performScrollTo().performClick()
            compose.onNodeWithTag("object-slot-9D").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(ManagerOperation.PIV_SLOT to 0x9d, action())
            compose.onNodeWithText("PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithText(publicText).assertDoesNotExist()
            compose.onNodeWithTag("result-details").performScrollTo().performClick()
            compose.onNodeWithText(publicText).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("piv-9c-ssh.pub").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导出", substring = false).performScrollTo().performClick()
            assertSame(report, exported)
            assertEquals("piv-9c-ssh.pub", exported?.fileName)
            assertArrayEquals(report.export, exported?.export)
            assertEquals(commandCount, card.commands.size)
            assertTrue(card.commands.all { it.ins in setOf(0xa4, 0xf7, 0xc0) })
        }
    }

}
