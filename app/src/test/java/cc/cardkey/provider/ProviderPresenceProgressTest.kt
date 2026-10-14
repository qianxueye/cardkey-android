package cc.cardkey.provider

import cc.cardkey.protocol.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ProviderPresenceProgressTest {
    private val data = ClientData(byteArrayOf(), ByteArray(32), true)
    private fun request() = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED)
    private open class Channel : CardChannel {
        override val wire = CardWire.CTAP
        override val supportsCtap2 = true
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 7609
        var listener: ((Boolean) -> Unit)? = null
        override fun setUserPresenceListener(listener: ((Boolean) -> Unit)?) { this.listener = listener }
        override fun transmit(command: ByteArray): ByteArray = error("No APDU")
        override fun exchangeCtap2(command: ByteArray): ByteArray = byteArrayOf(1)
        override fun close() = Unit
        override fun cancel() = Unit
    }

    @Test fun listenerStartsAfterClaimReservationAndTerminalRetiresIt() {
        val registry = RequestRegistry()
        val claim = registry.claim(registry.offer(OfferedRequest(ProviderRequestKind.GET, "{}", data.hash,
            CallerSnapshot("test.package", setOf("certificate"), null), "option")))
        val updates = mutableListOf<Boolean>()
        var saved: ((Boolean) -> Unit)? = null
        val channel = object : Channel() {
            override fun setUserPresenceListener(listener: ((Boolean) -> Unit)?) {
                if (listener != null) {
                    assertThrows(IllegalArgumentException::class.java) { registry.claim(claim.id) }
                    saved = listener
                }
                super.setUserPresenceListener(listener)
            }
            override fun exchangeCtap2(command: ByteArray): ByteArray {
                listener!!(true); listener!!(false); listener!!(true)
                return byteArrayOf(1)
            }
        }
        val result = ProviderOperationRunner().executeAndConfirm(null, request(), data, null, claim = claim,
            onUserPresenceRequired = { assertNull(CardOperationGate.tryAcquire()); updates += it }) {
            assertFalse(claim.reserveTransport())
            assertTrue(updates.isEmpty())
            channel
        }
        assertEquals(FailureCode.UNSUPPORTED, (result as OperationResult.Failed).code)
        assertEquals(listOf(true, false, true, false), updates)
        assertNull(channel.listener)
        saved!!(true)
        assertEquals(listOf(true, false, true, false), updates)
    }

    @Test fun cancellationClearsProgressWithoutReleasingInFlightLeaseAndRejectsLateCallbacks() {
        val runner = ProviderOperationRunner()
        val updates = Collections.synchronizedList(mutableListOf<Boolean>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = AtomicReference<((Boolean) -> Unit)?>(null)
        val result = AtomicReference<OperationResult<ProviderOutput>?>(null)
        val channel = object : Channel() {
            override fun exchangeCtap2(command: ByteArray): ByteArray {
                saved.set(listener)
                listener!!(true)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return byteArrayOf(0x2d)
            }
        }
        val worker = Thread { result.set(runner.executeAndConfirm(null, request(), data, null,
            onUserPresenceRequired = { updates += it }) { channel }) }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runner.cancel()
            assertEquals(listOf(true, false), updates)
            assertNull(channel.listener)
            saved.get()!!(true)
            assertEquals(listOf(true, false), updates)
            assertNull(CardOperationGate.tryAcquire())
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertEquals(FailureCode.CANCELLED, (result.get() as OperationResult.Failed).code)
        saved.get()!!(true)
        assertEquals(listOf(true, false), updates)
        CardOperationGate.tryAcquire()!!.close()
    }

    @Test fun cancelledClaimCannotConnectOrSubscribeToProgress() {
        val registry = RequestRegistry()
        val claim = registry.claim(registry.offer(OfferedRequest(ProviderRequestKind.GET, "{}", data.hash,
            CallerSnapshot("test.package", setOf("certificate"), null), "option")))
        claim.finish(cancelled = true)
        val updates = mutableListOf<Boolean>()
        val result = ProviderOperationRunner().executeAndConfirm(null, request(), data, null, claim = claim,
            onUserPresenceRequired = { updates += it }) { error("Cancelled claim connected") }
        assertEquals(FailureCode.CANCELLED, (result as OperationResult.Failed).code)
        assertTrue(updates.isEmpty())
    }
    @Test fun providerTerminalFailuresUseApprovedHidHintsWithoutRetrying() {
        for ((status, expected) in listOf(0x3b to cc.cardkey.R.string.error_hidPhysicalActionRequired,
                0x34 to cc.cardkey.R.string.error_hidPowerCycleRequired)) {
            var exchanges = 0
            val channel = object : Channel() {
                override fun exchangeCtap2(command: ByteArray): ByteArray {
                    exchanges++
                    return byteArrayOf(status.toByte())
                }
            }
            val result = ProviderOperationRunner().executeAndConfirm(null, request(), data, null) { channel }
            assertTrue(result is OperationResult.Failed)
            assertEquals(listOf(expected), cc.cardkey.ui.ConfirmationPolicy.resultMessages(result, channel.wire))
            assertEquals(1, exchanges)
            assertNull(channel.listener)
        }
    }
}
