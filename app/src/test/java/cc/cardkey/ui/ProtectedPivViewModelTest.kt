package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protectedpiv.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProtectedPivViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun model() = ManagerViewModel(RuntimeEnvironment.getApplication())
    private fun outcome(model: ManagerViewModel, result: OperationResult<ManagerReport>, route: String = "nfc") {
        ReflectionHelpers.setField(model, "operationRoute", route)
        ReflectionHelpers.setField(model, "operationWire", CardWire.APDU)
        val mutable = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        mutable.value = mutable.value.copy(operation = ManagerOperation.PIV_INFO)
        ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java).apply { isAccessible = true }
            .invoke(model, result)
    }
    @Test fun unknownProtectedOperationRequiresAuthenticatedOriginalIdentityInspection() {
        val model = model()
        ReflectionHelpers.setField(model, "operationProtectedIdentity", "original-guid:issuer-fingerprint")
        outcome(model, OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN))
        assertTrue(model.state.value.requiresInspection)
        assertTrue(model.state.value.requiresProtectedInspection)
        outcome(model, OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE))
        assertTrue(model.state.value.requiresInspection)
        assertTrue(model.state.value.requiresProtectedInspection)
        outcome(model, OperationResult.Completed(ManagerReport(emptyList(), protectedPivIdentity = "other-guid:issuer-fingerprint"), Effect.NONE))
        assertTrue(model.state.value.requiresInspection)
        assertTrue(model.state.value.requiresProtectedInspection)
        outcome(model, OperationResult.Completed(ManagerReport(emptyList(), protectedPivIdentity = "original-guid:issuer-fingerprint"), Effect.NONE), "contact-reader")
        assertFalse(model.state.value.requiresInspection)
        assertFalse(model.state.value.requiresProtectedInspection)
    }
    @Test fun protectedStatusCannotClearOrdinaryUnknownOnSharedNfcRoute() {
        protectedStatusCannotClearOrdinaryUnknown("nfc")
    }
    @Test fun protectedStatusCannotClearOrdinaryUnknownOnSharedContactRoute() {
        protectedStatusCannotClearOrdinaryUnknown("contact-reader")
    }
    private fun protectedStatusCannotClearOrdinaryUnknown(route: String) {
        val model = model()
        outcome(model, OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN), route)
        assertTrue(model.state.value.requiresInspection)
        assertFalse(model.state.value.requiresProtectedInspection)
        ReflectionHelpers.setField(model, "operationProtectedIdentity", "other-guid:issuer-fingerprint")
        outcome(model, OperationResult.Completed(ManagerReport(emptyList(),
            protectedPivIdentity = "other-guid:issuer-fingerprint"), Effect.NONE), route)
        assertTrue(model.state.value.requiresInspection)
        assertFalse(model.state.value.requiresProtectedInspection)
        ReflectionHelpers.setField(model, "operationProtectedIdentity", null)
        outcome(model, OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE), route)
        assertFalse(model.state.value.requiresInspection)
        assertFalse(model.state.value.requiresProtectedInspection)
    }
    @Test fun profilePickerOwnerSurvivesItsPauseButClearAndOrdinaryBackgroundRetireIt() {
        val model = model()
        val request = model.requestProtectedPivDocument(ProtectedPivDocument.ISSUER)!!
        model.pauseForActivity(false)
        assertEquals(request, model.protectedPiv.value.documentId)
        model.receiveProtectedPivDocument(request, null)
        assertEquals(request, model.protectedPiv.value.documentId)
        model.resumeProtectedPiv()
        assertNull(model.protectedPiv.value.documentId)
        val next = model.requestProtectedPivDocument(ProtectedPivDocument.ISSUER)!!
        model.clearProtectedPiv()
        model.receiveProtectedPivDocument(next, null)
        model.resumeProtectedPiv()
        assertNull(model.protectedPiv.value.documentId)
        assertFalse(model.protectedPiv.value.profileReady)
    }
    @Test fun absentProfileCannotStartPrivateOperationOrKeepCallerPin() {
        val model = model()
        model.recognizeProtectedPiv(ProtectedPivAction.VERIFY_PIN, 0x9a, "")
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
        val pin = "123456".toCharArray()
        model.executeProtectedPiv(pin)
        assertArrayEquals(CharArray(6), pin)
        assertFalse(model.state.value.busy)
        assertNull(model.pendingNfcOwner)
    }
    @Test fun privateRecognitionNeverPublishesCompletionOrClearsRecovery() {
        val model = model()
        val mutable = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        val finish = ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java).apply { isAccessible = true }
        for (operation in listOf(ManagerOperation.PIV_CSR, ManagerOperation.PIV_SIGN, ManagerOperation.PIV_ECDH, ManagerOperation.PIV_DECRYPT)) {
            mutable.value = mutable.value.copy(operation = operation, busy = true, awaitingNfc = true, requiresInspection = true)
            finish.invoke(model, OperationResult.Completed(ManagerReport(emptyList(), protectedPivIdentity = "same-card",
                protectedPivRecognition = true), Effect.NONE))
            assertNull(model.state.value.result)
            assertNull(model.state.value.operation)
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.awaitingNfc)
            assertTrue(model.state.value.requiresInspection)
            mutable.value = mutable.value.copy(operation = operation, requiresInspection = false)
            val completed = OperationResult.Completed(ManagerReport(listOf(ResultRow("signature", "verified")),
                protectedPivIdentity = "same-card"), Effect.NONE)
            finish.invoke(model, completed)
            assertSame(completed, model.state.value.result)
            assertEquals(operation, model.state.value.operation)
        }
    }
    @Test fun signingPickerSanitizesWholeCodePointsBeforeApplyingDisplayLimit() {
        val provider = ProtectedSigningNameProvider()
        ShadowContentResolver.registerProviderInternal("protected-signing-unicode", provider)
        val model = model()
        model.resumeProtectedPiv()
        val symbol = String(Character.toChars(0x1f512))
        val format = String(Character.toChars(0xe0001))
        val cases = listOf(
            "contract\u202e\u2066\u2069\u200b\u061c\n.pdf" to "contract.pdf",
            "a".repeat(254) + symbol + "z" to "a".repeat(254) + symbol,
            "\u202e".repeat(255) + symbol + "文.pdf" to symbol + "文.pdf",
            "issuer" + format + "-עברית.pdf" to "issuer-עברית.pdf",
            "\ud800name\udc00.pdf" to "name.pdf",
            "\u202e\u2066\u200b\n" to null
        )
        val actual = mutableListOf<String?>()
        for ((index, entry) in cases.withIndex()) {
            provider.name = entry.first
            val uri = Uri.parse("content://protected-signing-unicode/$index")
            shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            val request = model.requestProtectedPivDocument(ProtectedPivDocument.SIGNING_FILE)!!
            model.receiveProtectedPivDocument(request, uri)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (model.protectedPiv.value.fileSize == null && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(3, model.protectedPiv.value.fileSize)
            actual.add(model.protectedPiv.value.fileName)
        }
        assertEquals(cases.map { it.second }, actual)
    }

    @Test fun signingPickerPublishesCleanActualNameAndOmitsUnavailableName() {
        val provider = ProtectedSigningNameProvider()
        ShadowContentResolver.registerProviderInternal("protected-signing-name", provider)
        val model = model()
        model.resumeProtectedPiv()
        val names = listOf("contract-A.pdf" to "contract-A.pdf", "contract-B.pdf" to "contract-B.pdf",
            "a\n" + "b".repeat(300) to "a" + "b".repeat(254), null to null, "\n\t" to null)
        for ((index, entry) in names.withIndex()) {
            provider.name = entry.first
            val uri = Uri.parse("content://protected-signing-name/$index")
            shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            val request = model.requestProtectedPivDocument(ProtectedPivDocument.SIGNING_FILE)!!
            model.receiveProtectedPivDocument(request, uri)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (model.protectedPiv.value.fileSize == null && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(3, model.protectedPiv.value.fileSize)
            assertEquals(entry.second, model.protectedPiv.value.fileName)
            assertNotEquals("file", model.protectedPiv.value.fileName)
        }
    }
}

class ProtectedSigningNameProvider : ContentProvider() {
    var name: String? = null
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { name?.let { addRow(arrayOf(it)) } }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
