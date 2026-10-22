package cc.cardkey.signing

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.content.Intent
import android.hardware.usb.*
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import cc.cardkey.MainActivity
import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.*
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PivFileSigningActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun model(activity: MainActivity, format: SignatureFormat = SignatureFormat.RAW): ManagerViewModel {
        fun endpoint(address: Int) = Shadow.newInstanceOf(UsbEndpoint::class.java).also {
            ReflectionHelpers.setField(it, "mAddress", address)
            ReflectionHelpers.setField(it, "mAttributes", 2)
            ReflectionHelpers.setField(it, "mMaxPacketSize", 64)
        }
        val intf = Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", 1)
            ReflectionHelpers.setField(it, "mClass", 0x0b)
            ReflectionHelpers.setField(it, "mEndpoints", arrayOf(endpoint(0x81), endpoint(1)))
        }
        val device = Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "signing-activity-device")
            ReflectionHelpers.setField(it, "mVendorId", 0x04e6)
            ReflectionHelpers.setField(it, "mProductId", 0x5810)
            ReflectionHelpers.setField(it, "mInterfaces", arrayOf(intf))
        }
        val manager = activity.getSystemService(UsbManager::class.java)
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        val model = ViewModelProvider(activity)[ManagerViewModel::class.java]
        model.selectReader(UsbAuthenticatorReader.discover(manager).single())
        compose.waitForIdle()
        compose.onNode(hasText("证书") and hasClickAction()).performClick()
        compose.onNodeWithTag("object-overview").performClick()
        compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(if (format == SignatureFormat.CMS) "CMS 文件签名" else "文件签名").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        return model
    }
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(8000) {
        shadowOf(Looper.getMainLooper()).idle(); test()
    }
    private fun state(model: ManagerViewModel) = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
    private fun signedResult(model: ManagerViewModel, format: SignatureFormat = SignatureFormat.RAW): ManagerReport {
        val card = EnrollmentCard().apply { if (format == SignatureFormat.CMS) certificate = documentCertificate(this).encoded }
        val operation = if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN
        val input = OperationInput(operation, pin = "123456".toCharArray(), bytes = byteArrayOf(1, 2, 3), signatureFormat = format)
        val result = try { CardSession(card).run { ManagerOperations().execute(it, input) } } finally { input.close() }
        state(model).value = model.state.value.copy(operation = operation, result = result)
        compose.waitForIdle()
        return (result as OperationResult.Completed).value
    }

    @Test fun sshPublicKeyMenuExportsAllAlgorithmsThroughOwnedDocumentPicker() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val model = model(activity)
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("导出 SSH 公钥").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            compose.onNodeWithText("当前 PIV PIN", substring = false).assertDoesNotExist()
            for (algorithm in cc.cardkey.protocol.piv.KeyAlgorithm.entries) {
                val card = EnrollmentCard(algorithm = algorithm)
                val result = CardSession(card).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_SSH_EXPORT)) }
                state(model).value = model.state.value.copy(operation = ManagerOperation.PIV_SSH_EXPORT, result = result)
                val report = (result as OperationResult.Completed).value
                compose.waitForIdle()
                compose.onNodeWithText("导出", substring = false).performScrollTo().performClick()
                val launch = shadowOf(activity).nextStartedActivityForResult
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.action)
                assertEquals("text/plain", launch.intent.type)
                assertEquals("piv-9a-ssh.pub", launch.intent.getStringExtra(Intent.EXTRA_TITLE))
                val uri = Uri.parse("content://ssh-activity/${algorithm.name}.pub")
                val output = ByteArrayOutputStream()
                shadowOf(activity.contentResolver).registerOutputStream(uri, output)
                activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
                waitFor { model.signingFiles.state.value.exportStatus == SignatureExportStatus.SAVED }
                assertArrayEquals(report.export, output.toByteArray())
                compose.onNodeWithText("已导出 SSH 公钥。").assertExists()
                assertTrue(card.commands.all { it.ins in setOf(0xa4, 0xf7, 0xc0) })
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rawPickerRecreationKeepsOwnerSlotAndExactBytesThenNormalPauseWipesSelection() = pickerRecreation(SignatureFormat.RAW)
    @Test fun cmsMenuPickerRecreationKeepsActionSlotAndBytesThenNormalPauseWipesSelection() = pickerRecreation(SignatureFormat.CMS)
    private fun pickerRecreation(format: SignatureFormat) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val original = controller.get()
            val model = model(original, format)
            compose.onNode(hasText("槽位") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("9C").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("signing-file-picker").performScrollTo().performClick()
            val launch = shadowOf(original).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            controller.recreate()
            val recreated = controller.get()
            assertSame(model, ViewModelProvider(recreated)[ManagerViewModel::class.java])
            val uri = Uri.parse("content://signing-activity/certificate-looking.pem")
            val raw = "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----\n".toByteArray()
            shadowOf(recreated.contentResolver).registerInputStream(uri, ByteArrayInputStream(raw))
            assertTrue(recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            waitFor { model.signingFiles.state.value.selection != null }
            assertEquals(0x9c, model.signingFiles.state.value.slot)
            assertEquals(if (format == SignatureFormat.CMS) ManagerOperation.PIV_CMS_SIGN else ManagerOperation.PIV_SIGN,
                model.signingFiles.state.value.operation)
            if (format == SignatureFormat.CMS) compose.onNodeWithText("需要有效的文档签名证书，且证书公钥与所选槽位一致。").performScrollTo().assertIsDisplayed()
            val selected = ReflectionHelpers.getField<ByteArray>(model.signingFiles, "selected")
            assertArrayEquals(raw, selected)
            compose.onNodeWithText("certificate-looking.pem · ${raw.size} 字节").performScrollTo().assertIsDisplayed()
            controller.pause()
            assertTrue(selected.all { it == 0.toByte() })
            assertNull(model.signingFiles.state.value.selection)
            controller.resume()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun restoredRegistryAfterProcessDeathCannotReadWithoutInMemoryOwner() = processDeath(SignatureFormat.RAW)
    @Test fun cmsProcessDeathCannotRevivePickerWithoutInMemoryOwner() = processDeath(SignatureFormat.CMS)
    private fun processDeath(format: SignatureFormat) {
        val original = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val model = model(original.get(), format)
        compose.onNodeWithTag("signing-file-picker").performScrollTo().performClick()
        val launch = shadowOf(original.get()).nextStartedActivityForResult
        val saved = Bundle()
        original.saveInstanceState(saved).pause().stop().destroy()
        val fresh = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            val nextModel = ViewModelProvider(fresh.get())[ManagerViewModel::class.java]
            assertNotSame(model, nextModel)
            var reads = 0
            val uri = Uri.parse("content://signing-activity/orphan.raw")
            shadowOf(fresh.get().contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return super.read(bytes, offset, length) }
            })
            assertTrue(fresh.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            compose.waitForIdle()
            assertEquals(0, reads)
            assertNull(nextModel.signingFiles.state.value.selection)
            model(fresh.get())
            compose.onNodeWithTag("signing-file-picker").performScrollTo().performClick()
            val newLaunch = shadowOf(fresh.get()).nextStartedActivityForResult
            assertNotEquals(launch.requestCode, newLaunch.requestCode)
            waitFor { nextModel.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
            assertEquals(0, reads)
            assertNull(nextModel.signingFiles.state.value.selection)
            fresh.get().activityResultRegistry.dispatchResult(newLaunch.requestCode, Activity.RESULT_CANCELED, null)
        } finally { fresh.pause().stop().destroy() }
    }

    @Test fun restoredSaveCallbackAfterProcessDeathCannotOpenAnOutputStream() {
        val original = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val owner = model(original.get())
        signedResult(owner)
        compose.onNodeWithText("导出").performScrollTo().performClick()
        val launch = shadowOf(original.get()).nextStartedActivityForResult
        val saved = Bundle()
        original.saveInstanceState(saved).pause().stop().destroy()
        val fresh = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            var opens = 0
            val uri = Uri.parse("content://signing-activity/orphan-signature.bin")
            shadowOf(fresh.get().contentResolver).registerOutputStreamSupplier(uri) { opens++; ByteArrayOutputStream() }
            assertTrue(fresh.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            compose.waitForIdle()
            assertEquals(0, opens)
            val model = ViewModelProvider(fresh.get())[ManagerViewModel::class.java]
            assertNull(model.signingFiles.state.value.exportOwner)
            assertNull(model.signingFiles.state.value.exportStatus)
        } finally { fresh.pause().stop().destroy() }
    }

    @Test fun oldPickerResultCannotReviveReaderOrActionAba() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val model = model(activity)
            for (readerChange in listOf(false, true)) {
                compose.onNodeWithTag("signing-file-picker").performScrollTo().performClick()
                val launch = shadowOf(activity).nextStartedActivityForResult
                if (readerChange) {
                    val reader = model.state.value.selectedReader
                    model.selectReader(null); model.selectReader(reader)
                } else {
                    model.fileSigningAction(ManagerOperation.PIV_INFO)
                    model.fileSigningAction(ManagerOperation.PIV_SIGN)
                }
                compose.waitForIdle()
                compose.onNodeWithTag("signing-file-picker").performScrollTo().performClick()
                val replacement = shadowOf(activity).nextStartedActivityForResult
                val uri = Uri.parse("content://signing-activity/stale-$readerChange.raw")
                var reads = 0
                shadowOf(activity.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return super.read(bytes, offset, length) }
                })
                activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
                waitFor { model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
                assertEquals(0, reads)
                assertNull(model.signingFiles.state.value.selection)
                val currentUri = Uri.parse("content://signing-activity/current-$readerChange.raw")
                shadowOf(activity.contentResolver).registerInputStream(currentUri, ByteArrayInputStream(byteArrayOf(9, 8)))
                activity.activityResultRegistry.dispatchResult(replacement.requestCode, Activity.RESULT_OK, Intent().setData(currentUri))
                waitFor { model.signingFiles.state.value.selection != null }
                assertEquals("current-$readerChange.raw", model.signingFiles.state.value.selection!!.name)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun saveRecreationWritesCapturedSignatureOnceAndCancellationKeepsVerifiedResult() = saveRecreation(false)
    @Test fun protectedExportsSurviveRecreationWithOriginalBytesSlotAndWriteOutcome() {
        for (operation in listOf(ManagerOperation.PIV_SIGN, ManagerOperation.PIV_SSH_EXPORT, ManagerOperation.PIV_CSR, ManagerOperation.PIV_SLOT)) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
            try {
                val original = controller.get()
                val model = model(original)
                val card = EnrollmentCard().apply { certificate = documentCertificate(this).encoded }
                val input = OperationInput(operation, slot = 0x9c, pin = "123456".toCharArray(),
                    bytes = byteArrayOf(1, 2, 3), text = "CN=Synthetic export")
                val result = try { CardSession(card).run { ManagerOperations().execute(it, input) } } finally { input.close() }
                assertTrue("$operation: $result", result is OperationResult.Completed)
                val report = (result as OperationResult.Completed).value.copy(protectedPivIdentity = "synthetic-card:issuer",
                    protectedPivSlot = 0x9c)
                val expected = report.export!!.copyOf()
                state(model).value = model.state.value.copy(operation = operation, result = OperationResult.Completed(report, Effect.NONE))
                ReflectionHelpers.setField(model, "signingAction", null)
                compose.waitForIdle()
                compose.onNodeWithText("导出", substring = false).performScrollTo().performClick()
                val launch = shadowOf(original).nextStartedActivityForResult
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.action)
                assertEquals(report.fileName, launch.intent.getStringExtra(Intent.EXTRA_TITLE))
                assertEquals(if (operation == ManagerOperation.PIV_SSH_EXPORT) "text/plain" else "application/octet-stream", launch.intent.type)
                val owner = model.signatureSaveRequestId
                controller.recreate()
                val recreated = controller.get()
                assertSame(model, ViewModelProvider(recreated)[ManagerViewModel::class.java])
                assertEquals(owner, model.signatureSaveRequestId)
                assertEquals(0x9c, model.signingFiles.state.value.slot)
                assertEquals(operation, model.signingFiles.state.value.restoreOperation)
                assertFalse(model.protectedPiv.value.profileReady)
                val uri = Uri.parse("content://protected-export/${operation.name}")
                var writes = 0
                val output = object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; super.write(bytes, offset, length) }
                }
                shadowOf(recreated.contentResolver).registerOutputStream(uri, output)
                assertTrue(recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
                waitFor { model.signingFiles.state.value.exportStatus == SignatureExportStatus.SAVED }
                assertArrayEquals(expected, output.toByteArray())
                recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
                compose.waitForIdle()
                assertEquals(1, writes)
                assertSame(report, (model.state.value.result as OperationResult.Completed).value)
                compose.onNodeWithText("导出", substring = false).performScrollTo().performClick()
                val cancelled = shadowOf(recreated).nextStartedActivityForResult
                recreated.activityResultRegistry.dispatchResult(cancelled.requestCode, Activity.RESULT_CANCELED, null)
                compose.waitForIdle()
                assertEquals(SignatureExportStatus.CANCELLED, model.signingFiles.state.value.exportStatus)
                compose.onNodeWithText("导出", substring = false).performScrollTo().performClick()
                val uncertain = shadowOf(recreated).nextStartedActivityForResult
                shadowOf(recreated.contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { throw java.io.IOException("Synthetic output failure") }
                })
                recreated.activityResultRegistry.dispatchResult(uncertain.requestCode, Activity.RESULT_OK, Intent().setData(uri))
                waitFor { model.signingFiles.state.value.exportStatus == SignatureExportStatus.UNKNOWN }
                assertSame(report, (model.state.value.result as OperationResult.Completed).value)
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Test fun saveFromReaderPageKeepsCapturedSignatureAcrossRecreation() = saveRecreation(true)
    @Test fun cmsSaveRecreationKeepsMimeFilenameCapturedBytesAndCancellationReceipt() = saveRecreation(false, SignatureFormat.CMS)
    private fun saveRecreation(readerPage: Boolean, format: SignatureFormat = SignatureFormat.RAW) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val original = controller.get()
            val model = model(original, format)
            val report = signedResult(model, format)
            val expected = report.export!!.copyOf()
            compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText(ManagerOperation.PIV_INFO.title).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            if (readerPage) {
                compose.onNode(hasText("卡片") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
                compose.waitForIdle()
            }
            assertFalse(model.signingFiles.state.value.active)
            compose.onNodeWithText("导出").performScrollTo().performClick()
            val launch = shadowOf(original).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.action)
            assertEquals(format.fileName, launch.intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(format.mimeType, launch.intent.type)
            controller.recreate()
            val recreated = controller.get()
            assertSame(report, (model.state.value.result as OperationResult.Completed).value)
            val newer = signedResult(model, format)
            val uri = Uri.parse("content://signing-activity/${format.fileName}")
            var writes = 0
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; super.write(bytes, offset, length) }
            }
            shadowOf(recreated.contentResolver).registerOutputStream(uri, output)
            recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            waitFor {
                if (model.signingFiles.state.value.exportStatus == SignatureExportStatus.UNKNOWN)
                    throw AssertionError("save became unknown")
                model.signingFiles.state.value.exportStatus == SignatureExportStatus.SAVED
            }
            recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            compose.waitForIdle()
            assertArrayEquals(expected, output.toByteArray())
            assertEquals(1, writes)
            assertSame(newer, (model.state.value.result as OperationResult.Completed).value)
            compose.onNodeWithText("已导出签名。").assertDoesNotExist()
            compose.onNodeWithText("导出").performScrollTo().performClick()
            val cancelled = shadowOf(recreated).nextStartedActivityForResult
            recreated.activityResultRegistry.dispatchResult(cancelled.requestCode, Activity.RESULT_CANCELED, null)
            compose.waitForIdle()
            assertEquals(SignatureExportStatus.CANCELLED, model.signingFiles.state.value.exportStatus)
            assertSame(newer, (model.state.value.result as OperationResult.Completed).value)
            compose.onNodeWithText("已取消导出。").performScrollTo().assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun oldSaveCallbackCannotConsumeANewerCapturedSignature() = oldSaveCallback(SignatureFormat.RAW)
    @Test fun retiredCmsCallbackCannotConsumeNewerCapturedRawOutput() = oldSaveCallback(SignatureFormat.CMS)
    private fun oldSaveCallback(firstFormat: SignatureFormat) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val model = model(activity, firstFormat)
            signedResult(model, firstFormat)
            compose.onNodeWithText("导出").performScrollTo().performClick()
            val oldLaunch = shadowOf(activity).nextStartedActivityForResult
            model.cancel()
            val newer = signedResult(model)
            compose.onNodeWithText("导出").performScrollTo().performClick()
            val currentLaunch = shadowOf(activity).nextStartedActivityForResult
            assertEquals("application/octet-stream", currentLaunch.intent.type)
            assertNotEquals(oldLaunch.requestCode, currentLaunch.requestCode)
            val oldUri = Uri.parse("content://signing-activity/old-output.bin")
            var oldOpens = 0
            shadowOf(activity.contentResolver).registerOutputStreamSupplier(oldUri) { oldOpens++; ByteArrayOutputStream() }
            activity.activityResultRegistry.dispatchResult(oldLaunch.requestCode, Activity.RESULT_OK, Intent().setData(oldUri))
            waitFor { model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
            assertEquals(0, oldOpens)
            assertNotNull(model.signatureSaveRequestId)
            val currentUri = Uri.parse("content://signing-activity/current-output.bin")
            val output = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(currentUri, output)
            activity.activityResultRegistry.dispatchResult(currentLaunch.requestCode, Activity.RESULT_OK, Intent().setData(currentUri))
            waitFor { model.signingFiles.state.value.exportStatus == SignatureExportStatus.SAVED }
            assertArrayEquals(newer.export, output.toByteArray())
            assertSame(newer, model.signingFiles.state.value.exportOwner)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun actualSaveCallbacksReportNullWriteAndCloseFailuresWithoutRetry() = actualSaveCallbacks(SignatureFormat.RAW)
    @Test fun cmsActualCallbacksPreserveUnknownForNullWriteAndCloseFailures() = actualSaveCallbacks(SignatureFormat.CMS)
    private fun actualSaveCallbacks(format: SignatureFormat) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val model = model(activity, format)
            val nullProvider = NullSigningOutputProvider().apply {
                attachInfo(activity, ProviderInfo().apply { authority = "null-signing-output" })
            }
            ShadowContentResolver.registerProviderInternal("null-signing-output", nullProvider)
            for (mode in listOf("null-output", "write", "close")) {
                val report = signedResult(model, format)
                compose.onNodeWithText("导出").performScrollTo().performClick()
                val launch = shadowOf(activity).nextStartedActivityForResult
                val uri = Uri.parse(if (mode == "null-output") "content://null-signing-output/signature.bin" else "content://signing-activity/$mode")
                var writes = 0
                if (mode != "null-output") shadowOf(activity.contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        writes++; super.write(bytes, offset, if (mode == "write") 1 else length)
                        if (mode == "write") throw java.io.IOException()
                    }
                    override fun close() { if (mode == "close") throw java.io.IOException() }
                })
                activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
                waitFor { model.signingFiles.state.value.exportStatus == SignatureExportStatus.UNKNOWN }
                assertEquals("mode=$mode", if (mode == "null-output") 0 else 1, writes)
                if (mode == "null-output") assertEquals(1, nullProvider.opens)
                assertSame(report, (model.state.value.result as OperationResult.Completed).value)
                compose.onNodeWithText("未确认导出结果。请检查所选文件。").performScrollTo().assertIsDisplayed()
            }
        } finally { controller.pause().stop().destroy() }
    }
}

class NullSigningOutputProvider : ContentProvider() {
    var opens = 0
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? { opens++; return null }
}
