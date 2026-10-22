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
        outcome(model, OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE))
        assertTrue(model.state.value.requiresInspection)
        outcome(model, OperationResult.Completed(ManagerReport(emptyList(), protectedPivIdentity = "other-guid:issuer-fingerprint"), Effect.NONE))
        assertTrue(model.state.value.requiresInspection)
        outcome(model, OperationResult.Completed(ManagerReport(emptyList(), protectedPivIdentity = "original-guid:issuer-fingerprint"), Effect.NONE), "contact-reader")
        assertFalse(model.state.value.requiresInspection)
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
}
