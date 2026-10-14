package cc.cardkey.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import cc.cardkey.MainActivity
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.AssertionExtensions
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InventoryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    // Robolectric replaces Applications; AndroidX caches the factory's first Application.
    @Before @After fun clearCachedApplicationFactory() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
    }
    private fun choose(operation: String) {
        compose.onNode(hasText("操作") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(operation).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }
    private fun model(activity: MainActivity): ManagerViewModel = ViewModelProvider(activity)[ManagerViewModel::class.java].also {
        assertSame("The Activity fixture must use this test's Application", activity.application,
            it.getApplication<android.app.Application>())
    }
    private fun prepare(activity: MainActivity): Pair<ManagerViewModel, InventoryCard> {
        val model = model(activity); val card = InventoryCard()
        seedInventory(model.inventory, card)
        compose.waitForIdle(); choose("扩展数据 · 设置")
        return model to card
    }
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idle(); test() }
    private fun pick() { compose.onNodeWithText("导入").performScrollTo().performClick() }
    private fun report(model: ManagerViewModel, card: InventoryCard): ManagerReport {
        for (op in listOf(ManagerOperation.FIDO_VERIFY_CREDENTIAL, ManagerOperation.FIDO_BLOB_READ)) {
            OperationInput(op, pin = "1234".toCharArray(), inventoryGeneration = model.inventory.state.value.generation,
                assertionExtensions = AssertionExtensions(largeBlobKey = true)).use { input ->
                input.inventoryRequest = model.inventory.begin(input)
                val result = CardSession(card.channel()).run { ManagerOperations().execute(it, input) }
                assertTrue(result is OperationResult.Completed)
                ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable").value =
                    model.state.value.copy(operation = op, result = result)
            }
        }
        compose.waitForIdle()
        return (model.state.value.result as OperationResult.Completed).value
    }
    private fun export() {
        compose.onNodeWithText("导出").performScrollTo().performClick()
        compose.onNodeWithText("RP · login.example.test").assertExists()
        compose.onNodeWithText("确认").performClick()
    }

    @Test fun pickerRecreationKeepsExactPublicOwnerAndRawBytesThenBackgroundWipes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val (owner, _) = prepare(controller.get())
            pick(); val launch = shadowOf(controller.get()).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            val selected = owner.inventory.state.value.credential
            controller.recreate()
            assertSame(owner, model(controller.get())); assertEquals(selected, owner.inventory.state.value.credential)
            val uri = Uri.parse("content://inventory-activity/raw.pem")
            val raw = "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----".toByteArray()
            shadowOf(controller.get().contentResolver).registerInputStream(uri, ByteArrayInputStream(raw))
            controller.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            waitFor { owner.inventory.state.value.fileSize != null }
            val owned = ReflectionHelpers.getField<ByteArray>(owner.inventory, "file")
            assertArrayEquals(raw, owned); assertFalse(owner.inventory.state.value.blobReady)
            compose.onNodeWithText("${raw.size} bytes").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("manager-run").assertIsNotEnabled()
            controller.pause()
            assertTrue(owned.all { it == 0.toByte() }); assertNull(owner.inventory.state.value.credential)
            controller.resume()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun oldPickerCannotConsumeNewRequestAfterSelectionAba() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val (owner, _) = prepare(controller.get())
            pick(); val old = shadowOf(controller.get()).nextStartedActivityForResult
            val selected = owner.inventory.state.value.credential!!
            owner.selectInventoryCredential(owner.inventory.state.value.generation, selected); owner.selectInventoryCredential(owner.inventory.state.value.generation, selected)
            compose.waitForIdle(); pick(); val current = shadowOf(controller.get()).nextStartedActivityForResult
            assertNotEquals(old.requestCode, current.requestCode)
            var reads = 0
            val uri = Uri.parse("content://inventory-activity/late")
            shadowOf(controller.get().contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(9, 8)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return super.read(bytes, offset, length) }
            })
            controller.get().activityResultRegistry.dispatchResult(old.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            compose.waitForIdle(); assertEquals(0, reads); assertNotNull(owner.inventory.fileRequestId)
            controller.get().activityResultRegistry.dispatchResult(current.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            waitFor { owner.inventory.state.value.fileSize == 2 }; assertTrue(reads > 0)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun processDeathDropsOldPickerAndDoesNotRouteItsResultToNewOwner() {
        val original = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val oldModel = prepare(original.get()).first
        pick(); val launch = shadowOf(original.get()).nextStartedActivityForResult
        val saved = Bundle(); original.saveInstanceState(saved).pause().stop().destroy()
        val fresh = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            var reads = 0
            val uri = Uri.parse("content://inventory-activity/orphan")
            shadowOf(fresh.get().contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return super.read(bytes, offset, length) }
            })
            fresh.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            val next = prepare(fresh.get()).first
            assertNotSame(oldModel, next); pick()
            compose.waitForIdle(); assertEquals(0, reads); assertNull(next.inventory.state.value.fileSize)
        } finally { fresh.pause().stop().destroy() }
    }
    @Test fun explicitExportUsesCapturedRealRpResultAcrossRecreationAndWritesOnlyOnce() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val (owner, card) = prepare(controller.get())
            val source = report(owner, card)
            assertEquals(card.rp, source.rows.first().value)
            export(); val launch = shadowOf(controller.get()).nextStartedActivityForResult
            controller.recreate(); assertSame(owner, model(controller.get()))
            assertTrue(source.export!!.all { it == 0.toByte() })
            var opens = 0
            val sink = ByteArrayOutputStream(); val uri = Uri.parse("content://inventory-activity/export")
            shadowOf(controller.get().contentResolver).registerOutputStreamSupplier(uri) { opens++; sink }
            controller.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            waitFor { opens == 1 && sink.size() > 0 }
            assertArrayEquals("private".toByteArray(), sink.toByteArray())
            controller.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            compose.waitForIdle(); assertEquals(1, opens)
            assertNull(owner.inventoryExportId)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun exportProviderFailuresAndCancellationWipeSnapshotWithoutAutomaticRetry() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val (owner, card) = prepare(controller.get())
            val provider = cc.cardkey.signing.NullSigningOutputProvider().apply {
                attachInfo(controller.get(), android.content.pm.ProviderInfo().apply { authority = "null-inventory-output" })
            }
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("null-inventory-output", provider)
            for (mode in listOf("cancel", "null", "write", "close")) {
                val source = report(owner, card)
                export(); val launch = shadowOf(controller.get()).nextStartedActivityForResult
                val request = ReflectionHelpers.getField<Any>(owner, "inventoryExport")
                val bytes = ReflectionHelpers.getField<ByteArray>(request, "bytes")
                var writes = 0
                val uri = Uri.parse(if (mode == "null") "content://null-inventory-output/blob" else "content://inventory-activity/$mode")
                if (mode != "null") shadowOf(controller.get().contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        writes++; if (mode == "write") throw java.io.IOException()
                        super.write(bytes, offset, length)
                    }
                    override fun close() { if (mode == "close") throw java.io.IOException() }
                })
                controller.get().activityResultRegistry.dispatchResult(launch.requestCode,
                    if (mode == "cancel") Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    if (mode == "cancel") null else Intent().setData(uri))
                waitFor { bytes.all { it == 0.toByte() } }
                assertEquals(if (mode in listOf("cancel", "null")) 0 else 1, writes)
                assertSame(source, (owner.state.value.result as OperationResult.Completed).value)
                assertNull(owner.inventoryExportId)
            }
            assertEquals(1, provider.opens)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun processDeathDropsAuthorizedExportSnapshotAndLateCallbackCannotOpenProvider() {
        val original = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val (owner, card) = prepare(original.get())
        report(owner, card); export(); val launch = shadowOf(original.get()).nextStartedActivityForResult
        val request = ReflectionHelpers.getField<Any>(owner, "inventoryExport")
        val bytes = ReflectionHelpers.getField<ByteArray>(request, "bytes")
        val saved = Bundle(); original.saveInstanceState(saved).pause().stop().destroy()
        assertTrue(bytes.all { it == 0.toByte() })
        val fresh = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            var opens = 0
            val uri = Uri.parse("content://inventory-activity/orphan-export")
            shadowOf(fresh.get().contentResolver).registerOutputStreamSupplier(uri) { opens++; ByteArrayOutputStream() }
            fresh.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            compose.waitForIdle(); assertEquals(0, opens); assertNull(model(fresh.get()).inventoryExportId)
        } finally { fresh.pause().stop().destroy() }
    }
    @Test fun nativeTagCallbackCapturesOwnerBeforePostingToMainThread() {
        org.robolectric.shadows.ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val owner = model(controller.get()); val card = InventoryCard()
            compose.waitForIdle()
            val application = owner.getApplication<android.app.Application>()
            assertSame(org.robolectric.RuntimeEnvironment.getApplication(), application)
            val applicationAdapter = android.nfc.NfcAdapter.getDefaultAdapter(application)
            assertSame(applicationAdapter, android.nfc.NfcAdapter.getDefaultAdapter(controller.get().application))
            shadowOf(applicationAdapter).setEnabled(true)
            val activityAdapter = android.nfc.NfcAdapter.getDefaultAdapter(controller.get())
            shadowOf(activityAdapter).setEnabled(true)
            assertTrue(applicationAdapter.isEnabled); assertTrue(activityAdapter.isEnabled)
            fun submit() = owner.submit(OperationInput(ManagerOperation.FIDO_VERIFY_CREDENTIAL,
                pin = "1234".toCharArray(), inventoryGeneration = owner.inventory.state.value.generation))
            seedInventory(owner.inventory, card); submit()
            assertTrue("Initial NFC request: ${owner.state.value}; app NFC enabled=${applicationAdapter.isEnabled}", owner.state.value.awaitingNfc)
            val tag = org.robolectric.shadow.api.Shadow.newInstanceOf(android.nfc.Tag::class.java)
            Thread { controller.get().onTagDiscovered(tag) }.apply { start(); join() }
            owner.cancel(); seedInventory(owner.inventory, card); submit()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A native callback posted for the cancelled owner must not dispatch the replacement", owner.state.value.awaitingNfc)
            assertNull(ReflectionHelpers.getField<android.nfc.Tag?>(controller.get(), "lastNfcTag"))
            assertEquals(0, card.assertions)
        } finally { model(controller.get()).cancel(); controller.pause().stop().destroy() }
    }
    @Test fun retiredExportCallbackCannotWriteOrConsumeNewerSnapshot() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val (owner, card) = prepare(controller.get())
            report(owner, card); export(); val old = shadowOf(controller.get()).nextStartedActivityForResult
            owner.cancel(); seedInventory(owner.inventory, card)
            report(owner, card); export(); val next = shadowOf(controller.get()).nextStartedActivityForResult
            var opens = 0
            val uri = Uri.parse("content://inventory-activity/late-export")
            shadowOf(controller.get().contentResolver).registerOutputStreamSupplier(uri) { opens++; ByteArrayOutputStream() }
            controller.get().activityResultRegistry.dispatchResult(old.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            compose.waitForIdle(); assertEquals(0, opens); assertNotNull(owner.inventoryExportId)
            controller.get().activityResultRegistry.dispatchResult(next.requestCode, Activity.RESULT_CANCELED, null)
            compose.waitForIdle(); assertEquals(0, opens); assertNull(owner.inventoryExportId)
        } finally { controller.pause().stop().destroy() }
    }
}
