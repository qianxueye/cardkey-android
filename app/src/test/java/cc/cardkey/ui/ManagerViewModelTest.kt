package cc.cardkey.ui

import android.nfc.NfcAdapter
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNfcAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ManagerViewModelTest {
    private val application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(application.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun absentNfcEndsOperationAndWipesPendingInput() {
        ShadowNfcAdapter.setNfcHardwareExists(false)
        val model = ManagerViewModel(application)
        val input = OperationInput(ManagerOperation.FIDO_INFO, pin = "1234".toCharArray())
        model.submit(input)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.awaitingNfc)
        assertEquals(FailureCode.NFC_UNAVAILABLE, (model.state.value.result as OperationResult.Failed).code)
        assertArrayEquals(CharArray(4), input.pin)
    }

    @Test fun disabledNfcEndsOperationAndCanBeEnabledForNewRequest() {
        val adapter = NfcAdapter.getDefaultAdapter(application)
        shadowOf(adapter).setEnabled(false)
        val model = ManagerViewModel(application)
        model.submit(OperationInput(ManagerOperation.FIDO_INFO))
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.awaitingNfc)
        assertEquals(FailureCode.NFC_DISABLED, (model.state.value.result as OperationResult.Failed).code)
        shadowOf(adapter).setEnabled(true)
        model.submit(OperationInput(ManagerOperation.FIDO_INFO))
        assertTrue(model.state.value.busy)
        assertTrue(model.state.value.awaitingNfc)
        model.cancel()
    }

    @Test fun switchingNfcOffReleasesAnAlreadyWaitingRequest() {
        val adapter = NfcAdapter.getDefaultAdapter(application)
        shadowOf(adapter).setEnabled(true)
        val model = ManagerViewModel(application)
        val input = OperationInput(ManagerOperation.FIDO_INFO, pin = "1234".toCharArray())
        model.submit(input)
        assertTrue(model.state.value.awaitingNfc)
        shadowOf(adapter).setEnabled(false)
        model.refreshNfc()
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.awaitingNfc)
        assertEquals(OperationResult.Failed(FailureCode.NFC_DISABLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED),
            model.state.value.result)
        assertArrayEquals(CharArray(4), input.pin)
    }

    @Test fun cancellationRacePreservesKnownPinFailure() {
        val model = ManagerViewModel(application)
        val server = FidoClientTest.PinServer(2)
        val dispatched = CountDownLatch(1)
        val released = CountDownLatch(1)
        val delegate = FidoChannel { code, map -> when (code) {
            4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true)))
            6 -> if (map!![2].number() == 9L) {
                dispatched.countDown()
                check(released.await(5, TimeUnit.SECONDS))
                byteArrayOf(0x31)
            } else server.respond(map, 4, null)
            else -> error("No mutation after incorrect PIN")
        } }
        val channel = object : CardChannel by delegate {
            override fun close() { released.countDown(); delegate.close() }
        }
        val input = OperationInput(ManagerOperation.FIDO_DELETE, pin = "9999".toCharArray(), text = "01")
        val run = ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java)
        run.isAccessible = true
        run.invoke(model, input, { channel })
        assertTrue(dispatched.await(5, TimeUnit.SECONDS))
        model.cancel()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.state.value.result == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(OperationResult.Failed(FailureCode.PIN_INVALID, Effect.APPLIED, 0x31), model.state.value.result)
        assertArrayEquals(CharArray(4), input.pin)
    }
    private fun show(model: ManagerViewModel, report: ManagerReport) {
        val finish = ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java)
        finish.isAccessible = true
        finish.invoke(model, OperationResult.Completed(report, Effect.NONE))
    }
    @Test fun everyResultReplacementWipesRetiredProtectedPlaintext() {
        for (action in listOf("submit", "reader", "replacement")) {
            shadowOf(NfcAdapter.getDefaultAdapter(application)).setEnabled(true)
            val model = ManagerViewModel(application)
            val plaintext = byteArrayOf(9, 8, 7)
            show(model, ManagerReport(emptyList(), plaintext, protectedExport = true))
            try {
                when(action) {
                    "submit" -> model.submit(OperationInput(ManagerOperation.FIDO_INFO))
                    "reader" -> model.selectReader(null)
                    else -> show(model, ManagerReport(emptyList()))
                }
                assertArrayEquals(ByteArray(3), plaintext)
            } finally { model.cancel() }
        }
    }
    @Test fun resultRetirementPreservesTransferredBufferAndAuthorizedExportCopy() {
        val model = ManagerViewModel(application)
        val plaintext = byteArrayOf(9, 8, 7)
        val first = ManagerReport(emptyList(), plaintext, protectedExport = true)
        val authorizedExport = first.copy(export = plaintext.copyOf())
        show(model, first)
        show(model, first.copy(rows = listOf(ResultRow("bytes", "3"))))
        assertArrayEquals(byteArrayOf(9, 8, 7), plaintext)
        model.selectReader(null)
        assertArrayEquals(ByteArray(3), plaintext)
        assertArrayEquals(byteArrayOf(9, 8, 7), authorizedExport.export)
        authorizedExport.export!!.fill(0)
    }

}
