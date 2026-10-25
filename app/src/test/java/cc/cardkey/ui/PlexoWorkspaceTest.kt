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
@Config(sdk = [34], qualifiers = "zh-rCN-w1200dp-h1000dp-mdpi")
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
                            { _, _ -> }, { _, _ -> }, {}, {}, protectedPiv = cc.cardkey.protectedpiv.ProtectedPivState(),
                            onProtectedPivDocument = {}, onProtectedPivClear = {},
                            onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
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
    @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun taskNavigationKeepsDeviceAndSettingsPassive() {
        render(400, 800, initial = ManagerState(nfc = false), openPasskeys = false) { activity, _, _ ->
            compose.onNode(hasText("卡片") and hasClickAction()).performClick()
            compose.onNodeWithText("通用卡片示意图").assertIsDisplayed()
            savePreview("device", activity)
            compose.onNodeWithText("刷新读卡器").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
            compose.onNodeWithTag("workspace-object-back").performClick()
            compose.onNodeWithTag("object-overview").assertIsDisplayed()
            savePreview("passkeys", activity)
            compose.onNode(hasText("设置") and hasClickAction()).performClick()
            compose.onNodeWithText("凭据提供者设置").assertIsDisplayed()
            compose.onNodeWithText("申请 PIV 证书").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun fidoDeliveryPreviewsUseOnlySyntheticState() {
        val dispatches = mutableListOf<OperationInput>()
        render(400, 800, onSubmit = { dispatches += it }) { activity, update, _ ->
            compose.onNodeWithTag("manager-read-fido").assertIsEnabled()
            savePreview("fido-unread", activity)
            chooseTask(ManagerOperation.FIDO_CHANGE_PIN)
            compose.onNodeWithTag("manager-read-fido").assertDoesNotExist()
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).assertIsDisplayed()
            compose.onNode(hasText("新 FIDO PIN") and hasSetTextAction()).assertIsDisplayed()
            savePreview("fido-pin-task", activity)
            update(ManagerState(operation = ManagerOperation.FIDO_INVENTORY,
                result = OperationResult.Completed(ManagerReport(listOf(
                    ResultRow("已存储凭据", "2 个"), ResultRow("剩余凭据容量（估算）", "18 个")
                )), Effect.NONE)))
            savePreview("fido-result", activity)
            assertTrue(dispatches.isEmpty())
        }
    }

    @Test fun explicitFidoReadIsPublicAndDoesNotChangePinPreparation() {
        val submissions = mutableListOf<OperationInput>()
        render(400, 800, onSubmit = { submissions += it }) { _, update, action ->
            compose.onNodeWithText("尚未读取 FIDO 信息").assertExists()
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).assertExists()
            assertTrue(submissions.isEmpty())
            compose.onNodeWithTag("manager-read-fido").performScrollTo().performClick()
            assertEquals(1, submissions.size)
            assertEquals(ManagerOperation.FIDO_INFO, submissions.single().operation)
            assertTrue(submissions.single().pin.isEmpty())
            assertEquals(ManagerOperation.FIDO_INVENTORY, action()?.first)
            update(ManagerState(recognition = CardRecognition("nfc", 1, CardFamily.FIDO2)))
            compose.onNodeWithText("已读取 FIDO 信息").assertExists()
            update(ManagerState(recognition = CardRecognition("other-route", 1, CardFamily.FIDO2)))
            compose.onNodeWithText("尚未读取 FIDO 信息").assertExists()
            compose.onNodeWithText("未初始化", substring = true).assertDoesNotExist()
            compose.onNodeWithText("未安装", substring = true).assertDoesNotExist()
            submissions.forEach { it.close() }
        }
    }

    @Test fun unknownOutcomeDoesNotClaimAnAbsentAppletOrCreateAnotherTask() {
        val submissions = mutableListOf<OperationInput>()
        render(400, 800, onSubmit = { submissions += it }, initial = ManagerState(
            requiresInspection = true,
            result = OperationResult.Failed(cc.cardkey.protocol.FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN)
        )) { _, _, _ ->
            compose.onNodeWithText("尚未读取 FIDO 信息").assertDoesNotExist()
            compose.onNodeWithText("未初始化", substring = true).assertDoesNotExist()
            compose.onNodeWithText("未安装", substring = true).assertDoesNotExist()
            assertTrue(submissions.isEmpty())
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w400dp-h800dp-night-mdpi")
    fun darkFidoWorkspaceUsesNativeSyntheticPreview() {
        render(400, 800) { activity, _, _ ->
            compose.onNodeWithTag("manager-read-fido").assertIsDisplayed()
            savePreview("fido-dark", activity)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h400dp-mdpi")
    fun largeTextLandscapePinTaskStaysScrollableWithoutDispatch() {
        render(800, 400, 1.5f) { activity, _, _ ->
            chooseTask(ManagerOperation.FIDO_CHANGE_PIN)
            compose.onNode(hasText("新 FIDO PIN") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("manager-run").assertIsDisplayed()
            savePreview("fido-large-landscape", activity)
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
            compose.onNodeWithTag("workspace-object-back").performClick()
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
        if (operation == ManagerOperation.PIV_ADMIN || operation == ManagerOperation.FIDO_ALWAYS_UV) {
            compose.onNode(hasText("设置") and hasClickAction()).performClick()
            compose.onNodeWithText(if (operation.piv) "高级 PIV 管理与诊断" else "高级 FIDO 设置与诊断").performScrollTo().performClick()
        } else {
            compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
        }
        compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("task-${operation.name}").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertEmptyInput(label: String) {
        assertEquals("", compose.onNodeWithText(label, substring = false).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    @Test fun pinPreparationNeedsNoPriorReadButPermissionRetiresSecrets() {
        val selected = reader("recognition-fido")
        val descriptor = ManagerState(nfc = false, selectedReader = selected)
        render(360, 640, initial = descriptor) { _, update, _ ->
            chooseTask(ManagerOperation.FIDO_CHANGE_PIN)
            compose.onNodeWithText("FIDO PIN", substring = false).performScrollTo().assertIsEnabled().performTextInput("123456")
            compose.onNodeWithTag("recognition-task").assertDoesNotExist()
            update(descriptor.copy(permissionPending = true))
            compose.onNodeWithText("FIDO PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithText("新 FIDO PIN", substring = false).assertDoesNotExist()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            update(descriptor)
            assertEmptyInput("FIDO PIN")
            assertEmptyInput("新 FIDO PIN")
        }
    }

    @Test fun advancedPivPreparationDoesNotDispatchOrRequirePublicProbe() {
        val reads = mutableListOf<OperationInput>()
        try {
            render(360, 640, onSubmit = { reads += it }) { _, _, action ->
                compose.onNode(hasText("设置") and hasClickAction()).performClick()
                compose.onNodeWithText("高级 PIV 管理与诊断").performScrollTo().performClick()
                chooseTask(ManagerOperation.PIV_ADMIN)
                compose.onNodeWithTag("recognition-task").assertDoesNotExist()
                compose.onNodeWithText("管理密钥 · HEX", substring = false).performScrollTo().assertIsEnabled()
                compose.onNodeWithText("新管理密钥 · HEX", substring = false).performScrollTo().assertIsEnabled()
                assertEquals(ManagerOperation.PIV_ADMIN to 0x9a, action())
                assertTrue(reads.isEmpty())
            }
        } finally { reads.forEach(OperationInput::close) }
    }

    @Test fun matchingFamilyEpochAndRouteReplacementRetirePinAndPendingConfirmation() {
        val original = reader("recognition-original")
        val initial = ManagerState(nfc = false, selectedReader = original,
            recognition = CardRecognition(original.routeKey, 1, CardFamily.FIDO2))
        render(360, 640, initial = initial) { _, update, _ ->
            chooseTask(ManagerOperation.FIDO_CHANGE_PIN)
            compose.onNodeWithText("FIDO PIN", substring = false).performScrollTo().performTextInput("123456")
            val newer = initial.copy(recognition = CardRecognition(original.routeKey, 2, CardFamily.FIDO2))
            update(newer)
            assertEmptyInput("FIDO PIN")
            compose.onNodeWithText("FIDO PIN", substring = false).performScrollTo().performTextInput("123456")
            compose.onNodeWithText("新 FIDO PIN", substring = false).performScrollTo().performTextInput("654321")
            chooseTask(ManagerOperation.FIDO_ALWAYS_UV)
            assertEmptyInput("FIDO PIN")
            compose.onNodeWithText("FIDO PIN", substring = false).performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("manager-run").performClick()
            compose.onNodeWithText("确认", substring = false).assertExists()
            val replacement = reader("recognition-replacement")
            update(newer.copy(selectedReader = replacement,
                recognition = CardRecognition(replacement.routeKey, 3, CardFamily.FIDO2)))
            compose.onNodeWithText("确认", substring = false).assertDoesNotExist()
            assertEmptyInput("FIDO PIN")
            update(ManagerState(nfc = false, selectedReader = replacement))
            assertEmptyInput("FIDO PIN")
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
            compose.onNodeWithTag("enrollment-pin").assertExists()
            compose.onNodeWithTag("enrollment-create-csr").assertExists()
            compose.onNodeWithTag("enrollment-admin").assertDoesNotExist()
            val recognized = descriptor.copy(recognition = CardRecognition(selected.routeKey, 1, CardFamily.PIV))
            update(recognized)
            compose.onNodeWithTag("enrollment-pin").performScrollTo().assertIsEnabled().performTextInput("123456")
            update(descriptor)
            compose.onNodeWithTag("enrollment-pin").assertExists()
            compose.onNodeWithTag("enrollment-create-csr").assertExists()
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
            compose.onNodeWithTag("tls-pin").assertExists()
            compose.onNodeWithTag("tls-start").assertExists()
            val recognized = descriptor.copy(recognition = CardRecognition(selected.routeKey, 1, CardFamily.PIV))
            update(recognized)
            compose.onNodeWithTag("tls-pin").performScrollTo().assertIsEnabled().performTextInput("123456")
            update(recognized.copy(recognition = CardRecognition(selected.routeKey, 2, CardFamily.PIV)))
            assertEquals("", compose.onNodeWithTag("tls-pin").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text)
            update(descriptor)
            compose.onNodeWithTag("tls-pin").assertExists()
            compose.onNodeWithTag("tls-start").assertExists()
            compose.onNodeWithTag("tls-root").performScrollTo().assertIsEnabled()
        }
    }

}
