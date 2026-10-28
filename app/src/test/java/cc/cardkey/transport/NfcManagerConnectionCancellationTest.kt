package cc.cardkey.transport

import android.nfc.NfcAdapter
import android.nfc.Tag
import cc.cardkey.protocol.*
import cc.cardkey.ui.ManagerOperation
import cc.cardkey.ui.ManagerViewModel
import cc.cardkey.ui.OperationInput
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [BlockingConnectIsoDepShadow::class])
class NfcManagerConnectionCancellationTest {
    private var model: ManagerViewModel? = null
    private val tag get() = Shadow.newInstanceOf(Tag::class.java)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        BlockingConnectIsoDepShadow.reset()
    }

    @After fun cleanup() {
        model?.cancel()
        BlockingConnectIsoDepShadow.closed.countDown()
        BlockingConnectIsoDepShadow.exit.countDown()
        BlockingConnectIsoDepShadow.firstCloseExit.countDown()
        model?.let { current -> await { !current.state.value.busy } }
        Dispatchers.resetMain()
    }

    @Test fun managerCancellationClosesBlockedConnectAndRetainsLeaseUntilNativeExit() {
        val app = RuntimeEnvironment.getApplication()
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        val current = ManagerViewModel(app).also { model = it }
        current.submit(OperationInput(ManagerOperation.FIDO_INFO))
        assertTrue(current.onTag(tag, requireNotNull(current.pendingNfcOwner)))
        assertTrue(BlockingConnectIsoDepShadow.entered.await(5, TimeUnit.SECONDS))
        current.cancel()
        assertTrue("Cancel must close native connect", BlockingConnectIsoDepShadow.closed.await(5, TimeUnit.SECONDS))
        assertNull("Native call has not exited", CardOperationGate.tryAcquire())
        BlockingConnectIsoDepShadow.exit.countDown()
        await { !current.state.value.busy }
        assertCancelled(current.state.value.result)
        assertEquals(0, BlockingConnectIsoDepShadow.commands.get())
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    @Test fun managerRetainsLeaseUntilFirstNativeCloseFinishes() {
        BlockingConnectIsoDepShadow.holdFirstClose = true
        BlockingConnectIsoDepShadow.returnsAfterClose = true
        val app = RuntimeEnvironment.getApplication()
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        val current = ManagerViewModel(app).also { model = it }
        current.submit(OperationInput(ManagerOperation.FIDO_INFO))
        assertTrue(current.onTag(tag, requireNotNull(current.pendingNfcOwner)))
        assertTrue(BlockingConnectIsoDepShadow.entered.await(5, TimeUnit.SECONDS))
        BlockingConnectIsoDepShadow.exit.countDown()
        current.cancel()
        assertTrue(BlockingConnectIsoDepShadow.firstCloseEntered.await(5, TimeUnit.SECONDS))
        assertTrue(BlockingConnectIsoDepShadow.connectFinished.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            val next = CardOperationGate.tryAcquire()
            next?.close()
            assertNull("Lease released while the first native close is still blocked", next)
            Thread.sleep(5)
        }
        assertTrue(current.state.value.busy)
        assertEquals(1, BlockingConnectIsoDepShadow.activeCloses.get())
        assertEquals(0, BlockingConnectIsoDepShadow.commands.get())
        BlockingConnectIsoDepShadow.firstCloseExit.countDown()
        await { !current.state.value.busy }
        assertCancelled(current.state.value.result)
        assertEquals(0, BlockingConnectIsoDepShadow.activeCloses.get())
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    private fun assertCancelled(result: OperationResult<*>?) {
        val failure = result as OperationResult.Failed
        assertEquals(FailureCode.CANCELLED, failure.code)
        assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
