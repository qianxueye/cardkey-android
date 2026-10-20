package cc.cardkey.ui

import java.io.File

import android.hardware.usb.UsbDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.enrollment.EnrollmentStage
import cc.cardkey.protocol.CardWire
import cc.cardkey.transport.UsbReaderDescriptor
import cc.cardkey.tls.TlsFixture
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
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
                       initial: ManagerState = ManagerState(),
                       enrollment: EnrollmentState = EnrollmentState(),
                       openPasskeys: Boolean = true,
                       check: (ComponentActivity, (ManagerState) -> Unit, () -> Pair<ManagerOperation?, Int>?) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val state = mutableStateOf(initial)
        var action: Pair<ManagerOperation?, Int>? = null
        controller.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                CardKeyTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp)) {
                        ManagerScreen(state.value, 0, {}, {}, onSubmit, {}, {}, null, onExport, {}, {}, {},
                            enrollment, { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                            TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(),
                            { operation, slot -> action = operation to slot }, {}, InventoryState(),
                            { _, _ -> }, { _, _ -> }, {}, {})
                    }
                }
            }
        }
        try {
            compose.waitForIdle()
            if (openPasskeys) compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
            check(controller.get(), { next -> compose.runOnIdle { state.value = next }; compose.waitForIdle() }, { action })
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-mdpi")
    fun taskNavigationKeepsDeviceAndSettingsPassive() {
        render(400, 800, initial = ManagerState(nfc = false), openPasskeys = false) { activity, _, _ ->
            compose.onNodeWithText("通用卡片示意图").assertIsDisplayed()
            savePreview("device", activity)
            compose.onNodeWithText("刷新读卡器").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
            compose.onNodeWithTag("object-overview").assertIsDisplayed()
            savePreview("passkeys", activity)
            compose.onNode(hasText("设置") and hasClickAction()).performClick()
            compose.onNodeWithText("凭据提供者设置").assertIsDisplayed()
            compose.onNodeWithText("申请 PIV 证书").assertIsDisplayed()
        }
    }

    private fun savePreview(name: String, activity: ComponentActivity) {
        val view = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val directory = File("build/ui-previews").apply { mkdirs() }
        val output = File(directory, "$name.png")
        output.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun compactObjectSelectionAndSystemBackPreserveSlotWithoutDispatch() {
        render(360, 640, 1.5f) { activity, _, action ->
            compose.onNodeWithTag("workspace-objects").assertExists()
            compose.onNodeWithTag("workspace-detail").assertDoesNotExist()
            compose.onNode(hasText("证书") and hasClickAction()).performClick()
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
            compose.onNode(hasText("证书") and hasClickAction()).performClick()
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
            compose.onNode(hasText("证书") and hasClickAction()).performClick()
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
            compose.onNode(hasText("证书") and hasClickAction()).performClick()
            compose.onNodeWithTag("object-slot-9C").performScrollTo().performClick()
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("task-PIV_SSH_EXPORT").performScrollTo().performClick()
            update(ManagerState(operation = ManagerOperation.PIV_SSH_EXPORT, result = result))
            compose.onNodeWithText("当前 PIV PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithText(publicText).assertDoesNotExist()
            compose.onNodeWithText("piv-9c-ssh.pub").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("result-details").performScrollTo().performClick()
            compose.onNodeWithText(publicText).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("workspace-object-back").performScrollTo().performClick()
            compose.onNodeWithTag("object-slot-9D").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(ManagerOperation.PIV_SLOT to 0x9d, action())
            compose.onNodeWithText("当前 PIV PIN", substring = false).assertDoesNotExist()
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


    private fun reader(name: String): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", name)
        return UsbReaderDescriptor(device, 1, CardWire.APDU)
    }

    private fun chooseTask(operation: ManagerOperation) {
        if (operation.piv) compose.onNode(hasText("证书") and hasClickAction()).performClick()
        else compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
        compose.onNodeWithTag("object-overview").performScrollTo().performClick()
        compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("task-${operation.name}").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertEmptyInput(label: String) {
        assertEquals("", compose.onNodeWithText(label, substring = false).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    @Test fun readerDescriptorAndWrongFamilyCannotExposeFidoSecretsOrBypassPermission() {
        val selected = reader("recognition-fido")
        val descriptor = ManagerState(nfc = false, selectedReader = selected)
        render(360, 640, initial = ManagerState(nfc = false)) { _, update, _ ->
            chooseTask(ManagerOperation.FIDO_CHANGE_PIN)
            compose.onNodeWithText("当前 FIDO PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithText("新 FIDO PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            update(descriptor)
            compose.onNodeWithText("当前 FIDO PIN", substring = false).assertDoesNotExist()
            for (family in listOf(CardFamily.U2F, CardFamily.PIV)) {
                update(descriptor.copy(recognition = CardRecognition(selected.routeKey, 1, family)))
                compose.onNodeWithText("当前 FIDO PIN", substring = false).assertDoesNotExist()
                compose.onNodeWithText("新 FIDO PIN", substring = false).assertDoesNotExist()
                compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            }
            val recognized = descriptor.copy(recognition = CardRecognition(selected.routeKey, 2, CardFamily.FIDO2))
            update(recognized)
            compose.onNodeWithText("当前 FIDO PIN", substring = false).performScrollTo().assertIsEnabled().performTextInput("123456")
            compose.onNodeWithText("新 FIDO PIN", substring = false).performScrollTo().assertIsEnabled()
            update(recognized.copy(permissionPending = true))
            compose.onNodeWithText("当前 FIDO PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithText("新 FIDO PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            update(recognized)
            assertEmptyInput("当前 FIDO PIN")
            assertEmptyInput("新 FIDO PIN")
        }
    }

    @Test fun explicitPublicNfcProbePreservesProspectiveTaskAndSlotUntilRecognition() {
        val reads = mutableListOf<OperationInput>()
        try {
            render(360, 640, onSubmit = { reads += it }) { _, update, action ->
                compose.onNode(hasText("证书") and hasClickAction()).performClick()
                compose.onNodeWithTag("object-slot-9D").performScrollTo().performClick()
                compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
                compose.onNodeWithTag("task-PIV_ADMIN").performScrollTo().performClick()
                compose.waitForIdle()
                assertTrue(reads.isEmpty())
                assertEquals(ManagerOperation.PIV_ADMIN to 0x9d, action())
                compose.onNodeWithText("管理密钥 · HEX", substring = false).assertDoesNotExist()
                compose.onNodeWithText("新管理密钥 · HEX", substring = false).assertDoesNotExist()
                compose.onNodeWithTag("recognition-task").performScrollTo().performClick()
                compose.waitForIdle()
                val read = reads.single()
                assertEquals(ManagerOperation.PIV_INFO, read.operation)
                assertEquals(0x9d, read.slot)
                assertEquals(0L, read.inventoryGeneration)
                assertTrue(read.pin.isEmpty() && read.newPin.isEmpty())
                assertTrue(read.adminKey.isEmpty() && read.newAdminKey.isEmpty() && read.bytes.isEmpty())
                assertNull(read.signingFileGeneration)
                assertNull(read.enrollmentImport)
                assertEquals(ManagerOperation.PIV_ADMIN to 0x9d, action())
                compose.onNodeWithTag("manager-run").assertIsDisplayed().assertIsNotEnabled()
                compose.onNodeWithText("管理密钥 · HEX", substring = false).assertDoesNotExist()
                update(ManagerState(recognition = CardRecognition("nfc", 1, CardFamily.FIDO2)))
                compose.onNodeWithText("管理密钥 · HEX", substring = false).assertDoesNotExist()
                update(ManagerState(recognition = CardRecognition("nfc", 2, CardFamily.PIV)))
                compose.onNodeWithText("管理密钥 · HEX", substring = false).performScrollTo().assertIsEnabled()
                compose.onNodeWithText("新管理密钥 · HEX", substring = false).performScrollTo().assertIsEnabled()
                assertEquals(ManagerOperation.PIV_ADMIN to 0x9d, action())
                assertEquals(1, reads.size)
            }
        } finally { reads.forEach(OperationInput::close) }
    }

    @Test fun matchingFamilyEpochAndRouteReplacementRetirePinAndPendingConfirmation() {
        val original = reader("recognition-original")
        val initial = ManagerState(nfc = false, selectedReader = original,
            recognition = CardRecognition(original.routeKey, 1, CardFamily.FIDO2))
        render(360, 640, initial = initial) { _, update, _ ->
            chooseTask(ManagerOperation.FIDO_CHANGE_PIN)
            compose.onNodeWithText("当前 FIDO PIN", substring = false).performScrollTo().performTextInput("123456")
            val newer = initial.copy(recognition = CardRecognition(original.routeKey, 2, CardFamily.FIDO2))
            update(newer)
            assertEmptyInput("当前 FIDO PIN")
            compose.onNodeWithText("当前 FIDO PIN", substring = false).performScrollTo().performTextInput("123456")
            compose.onNodeWithText("新 FIDO PIN", substring = false).performScrollTo().performTextInput("654321")
            compose.onNodeWithTag("manager-run").performClick()
            compose.onNodeWithText("确认", substring = false).assertExists()
            val replacement = reader("recognition-replacement")
            update(newer.copy(selectedReader = replacement,
                recognition = CardRecognition(replacement.routeKey, 3, CardFamily.FIDO2)))
            compose.onNodeWithText("确认", substring = false).assertDoesNotExist()
            assertEmptyInput("当前 FIDO PIN")
            assertEmptyInput("新 FIDO PIN")
            update(ManagerState(nfc = false, selectedReader = replacement))
            compose.onNodeWithText("当前 FIDO PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithText("新 FIDO PIN", substring = false).assertDoesNotExist()
        }
    }

    @Test fun certificatePreparationStaysPublicWhileRecognitionLossRetiresCsrPin() {
        val selected = reader("recognition-ca")
        val descriptor = ManagerState(nfc = false, selectedReader = selected)
        render(360, 640, initial = descriptor) { _, update, _ ->
            compose.onNode(hasText("设置") and hasClickAction()).performClick()
            compose.onNodeWithText("申请 PIV 证书").performScrollTo().performClick()
            compose.onNodeWithTag("enrollment-address").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("enrollment-cn").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("enrollment-root").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("enrollment-pin").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-create-csr").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-admin").assertDoesNotExist()
            val recognized = descriptor.copy(recognition = CardRecognition(selected.routeKey, 1, CardFamily.PIV))
            update(recognized)
            compose.onNodeWithTag("enrollment-pin").performScrollTo().assertIsEnabled().performTextInput("123456")
            update(descriptor)
            compose.onNodeWithTag("enrollment-pin").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-create-csr").assertDoesNotExist()
            compose.onNodeWithTag("enrollment-root").performScrollTo().assertIsEnabled()
            update(recognized.copy(recognition = CardRecognition(selected.routeKey, 2, CardFamily.PIV)))
            assertEquals("", compose.onNodeWithTag("enrollment-pin").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text)
        }
    }

    @Test fun importedTlsPreparationStaysPublicAndRecognitionEpochClearsPin() {
        val fixture = TlsFixture()
        val imported = EnrollmentState("https://localhost", fixture.csr.cn, true,
            fixture.csr, fixture.bundle, EnrollmentStage.IMPORTED)
        val selected = reader("recognition-tls")
        val descriptor = ManagerState(nfc = false, selectedReader = selected)
        render(360, 640, initial = descriptor, enrollment = imported) { _, update, _ ->
            compose.onNode(hasText("设置") and hasClickAction()).performClick()
            compose.onNodeWithText("申请 PIV 证书").performScrollTo().performClick()
            compose.onNodeWithTag("tls-endpoint").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("tls-root").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("tls-pin").assertDoesNotExist()
            compose.onNodeWithTag("tls-start").assertDoesNotExist()
            val recognized = descriptor.copy(recognition = CardRecognition(selected.routeKey, 1, CardFamily.PIV))
            update(recognized)
            compose.onNodeWithTag("tls-pin").performScrollTo().assertIsEnabled().performTextInput("123456")
            update(recognized.copy(recognition = CardRecognition(selected.routeKey, 2, CardFamily.PIV)))
            assertEquals("", compose.onNodeWithTag("tls-pin").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text)
            update(descriptor)
            compose.onNodeWithTag("tls-pin").assertDoesNotExist()
            compose.onNodeWithTag("tls-start").assertDoesNotExist()
            compose.onNodeWithTag("tls-root").performScrollTo().assertIsEnabled()
        }
    }

}
