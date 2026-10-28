package cc.cardkey.transport

import android.nfc.Tag
import android.os.Looper
import cc.cardkey.protocol.*
import cc.cardkey.provider.ClientData
import cc.cardkey.provider.ParsedGet
import cc.cardkey.provider.UserVerificationRequirement
import cc.cardkey.provider.ProviderOperationRunner
import cc.cardkey.provider.ProviderOutput
import cc.cardkey.provider.executeAndConfirm
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
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
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [BlockingConnectIsoDepShadow::class])
class NfcConnectionCancellationTest {
    private var worker: Thread? = null
    private var cancellationWatchdog: Thread? = null
    private val tag get() = Shadow.newInstanceOf(Tag::class.java)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        BlockingConnectIsoDepShadow.reset()
    }

    @After fun cleanup() {
        BlockingConnectIsoDepShadow.closed.countDown()
        BlockingConnectIsoDepShadow.exit.countDown()
        BlockingConnectIsoDepShadow.firstCloseExit.countDown()
        worker?.join(5000)
        cancellationWatchdog?.join(5000)
        Dispatchers.resetMain()
    }

    @Test fun providerCancellationClosesBlockedConnectEvenWhenNativeReturnsSuccess() {
        BlockingConnectIsoDepShadow.returnsAfterClose = true
        val runner = ProviderOperationRunner()
        val result = AtomicReference<OperationResult<ProviderOutput>>()
        val discovered = tag
        worker = Thread {
            result.set(runner.executeAndConfirm(null,
                ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED),
                ClientData(byteArrayOf(), ByteArray(32), true), null) { runner.connectNfc(discovered) })
        }.apply { start() }
        assertTrue(BlockingConnectIsoDepShadow.entered.await(5, TimeUnit.SECONDS))
        runner.cancel()
        assertTrue("Cancel must close native connect", BlockingConnectIsoDepShadow.closed.await(5, TimeUnit.SECONDS))
        assertNull("Native call has not exited", CardOperationGate.tryAcquire())
        BlockingConnectIsoDepShadow.exit.countDown()
        worker!!.join(5000)
        assertFalse(worker!!.isAlive)
        assertCancelled(result.get())
        assertEquals(0, BlockingConnectIsoDepShadow.commands.get())
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    @Test fun providerCancellationReturnsOnAndroidMainWhileNativeCloseBlocks() {
        BlockingConnectIsoDepShadow.holdFirstClose = true
        BlockingConnectIsoDepShadow.returnsAfterClose = true
        val runner = ProviderOperationRunner()
        val result = AtomicReference<OperationResult<ProviderOutput>>()
        val discovered = tag
        worker = Thread {
            result.set(runner.executeAndConfirm(null,
                ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED),
                ClientData(byteArrayOf(), ByteArray(32), true), null) { runner.connectNfc(discovered) })
        }.apply { start() }
        assertTrue(BlockingConnectIsoDepShadow.entered.await(5, TimeUnit.SECONDS))
        BlockingConnectIsoDepShadow.exit.countDown()
        val cancelReturned = CountDownLatch(1)
        val blockedMain = AtomicBoolean(false)
        cancellationWatchdog = Thread {
            if (BlockingConnectIsoDepShadow.firstCloseEntered.await(5, TimeUnit.SECONDS) &&
                !cancelReturned.await(1, TimeUnit.SECONDS)) {
                blockedMain.set(true)
                BlockingConnectIsoDepShadow.firstCloseExit.countDown()
            }
        }.apply { start() }
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        runner.cancel()
        cancelReturned.countDown()
        cancellationWatchdog!!.join(5000)
        assertFalse("Native close blocked Provider cancellation on Android main", blockedMain.get())
        assertTrue(BlockingConnectIsoDepShadow.firstCloseEntered.await(5, TimeUnit.SECONDS))
        assertTrue(BlockingConnectIsoDepShadow.connectFinished.await(5, TimeUnit.SECONDS))
        assertNull(CardOperationGate.tryAcquire())
        assertEquals(1, BlockingConnectIsoDepShadow.activeCloses.get())
        assertEquals(0, BlockingConnectIsoDepShadow.commands.get())
        BlockingConnectIsoDepShadow.firstCloseExit.countDown()
        worker!!.join(5000)
        assertFalse(worker!!.isAlive)
        assertCancelled(result.get())
        assertEquals(0, BlockingConnectIsoDepShadow.activeCloses.get())
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    @Test fun cancellationAtPublicationNeverEntersNativeConnect() {
        val failure = assertThrows(TransportException::class.java) {
            NfcCardChannel.connect(tag, onConnecting = { it.close() })
        }
        assertTrue(failure.cancelled)
        assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch)
        assertEquals(0, BlockingConnectIsoDepShadow.connections.get())
        assertEquals(0, BlockingConnectIsoDepShadow.commands.get())
    }

    @Test fun providerCancelledBeforePublicationClosesAttemptWithoutConnecting() {
        val runner = ProviderOperationRunner()
        runner.cancel()
        val failure = assertThrows(TransportException::class.java) { runner.connectNfc(tag) }
        assertTrue(failure.cancelled)
        assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch)
        assertEquals(0, BlockingConnectIsoDepShadow.connections.get())
        assertEquals(0, BlockingConnectIsoDepShadow.commands.get())
    }

    private fun assertCancelled(result: OperationResult<*>?) {
        val failure = result as OperationResult.Failed
        assertEquals(FailureCode.CANCELLED, failure.code)
        assertEquals(Dispatch.NOT_DISPATCHED, failure.dispatch)
    }

}
