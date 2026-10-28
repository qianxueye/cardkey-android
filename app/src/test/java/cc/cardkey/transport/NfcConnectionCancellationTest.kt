package cc.cardkey.transport

import android.nfc.Tag
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
    private val tag get() = Shadow.newInstanceOf(Tag::class.java)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        BlockingConnectIsoDepShadow.entered = CountDownLatch(1)
        BlockingConnectIsoDepShadow.closed = CountDownLatch(1)
        BlockingConnectIsoDepShadow.exit = CountDownLatch(1)
        BlockingConnectIsoDepShadow.commands.set(0)
        BlockingConnectIsoDepShadow.connections.set(0)
        BlockingConnectIsoDepShadow.returnsAfterClose = false
    }

    @After fun cleanup() {
        BlockingConnectIsoDepShadow.closed.countDown()
        BlockingConnectIsoDepShadow.exit.countDown()
        worker?.join(5000)
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
