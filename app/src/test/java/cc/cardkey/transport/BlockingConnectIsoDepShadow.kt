package cc.cardkey.transport

import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@Implements(IsoDep::class)
class BlockingConnectIsoDepShadow {
    companion object {
        lateinit var entered: CountDownLatch
        lateinit var closed: CountDownLatch
        lateinit var exit: CountDownLatch
        val commands = AtomicInteger()
        val connections = AtomicInteger()
        @Volatile var returnsAfterClose = false
        @JvmStatic @Implementation fun get(@Suppress("UNUSED_PARAMETER") tag: Tag): IsoDep =
            Shadow.newInstanceOf(IsoDep::class.java)
    }

    @Implementation fun connect() {
        connections.incrementAndGet()
        entered.countDown()
        check(closed.await(10, TimeUnit.SECONDS))
        check(exit.await(10, TimeUnit.SECONDS))
        if (!returnsAfterClose) throw IOException("Synthetic connection closed")
    }
    @Implementation fun close() { closed.countDown() }
    @Implementation fun getMaxTransceiveLength() = 261
    @Implementation fun isExtendedLengthApduSupported() = false
    @Implementation fun transceive(@Suppress("UNUSED_PARAMETER") data: ByteArray): ByteArray {
        commands.incrementAndGet()
        error("No APDU after cancellation")
    }
}

