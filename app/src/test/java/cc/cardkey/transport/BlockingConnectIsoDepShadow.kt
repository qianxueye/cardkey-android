package cc.cardkey.transport

import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowIsoDep

@Implements(IsoDep::class)
class BlockingConnectIsoDepShadow : ShadowIsoDep() {
    companion object {
        lateinit var entered: CountDownLatch
        lateinit var closed: CountDownLatch
        lateinit var exit: CountDownLatch
        val commands = AtomicInteger()
        val connections = AtomicInteger()
        val closeCalls = AtomicInteger()
        val activeCloses = AtomicInteger()
        lateinit var firstCloseEntered: CountDownLatch
        lateinit var firstCloseExit: CountDownLatch
        lateinit var connectFinished: CountDownLatch
        @Volatile var holdFirstClose = false
        fun reset() {
            entered = CountDownLatch(1)
            closed = CountDownLatch(1)
            exit = CountDownLatch(1)
            firstCloseEntered = CountDownLatch(1)
            firstCloseExit = CountDownLatch(1)
            connectFinished = CountDownLatch(1)
            commands.set(0)
            connections.set(0)
            closeCalls.set(0)
            activeCloses.set(0)
            holdFirstClose = false
            returnsAfterClose = false
        }
        @Volatile var returnsAfterClose = false
        @JvmStatic @Implementation fun get(@Suppress("UNUSED_PARAMETER") tag: Tag): IsoDep =
            newInstance()
    }

    @Implementation override fun connect() {
        connections.incrementAndGet()
        entered.countDown()
        try {
            check(closed.await(10, TimeUnit.SECONDS))
            check(exit.await(10, TimeUnit.SECONDS))
            if (!returnsAfterClose) throw IOException("Synthetic connection closed")
        } finally { connectFinished.countDown() }
    }
    @Implementation override fun close() {
        val first = closeCalls.incrementAndGet() == 1
        val release = firstCloseExit
        activeCloses.incrementAndGet()
        try {
            closed.countDown()
            if (first && holdFirstClose) {
                firstCloseEntered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        } finally { activeCloses.decrementAndGet() }
    }
    @Implementation override fun getMaxTransceiveLength() = 261
    @Implementation override fun isExtendedLengthApduSupported() = false
    @Implementation override fun transceive(@Suppress("UNUSED_PARAMETER") data: ByteArray): ByteArray {
        commands.incrementAndGet()
        error("No APDU after cancellation")
    }
}

