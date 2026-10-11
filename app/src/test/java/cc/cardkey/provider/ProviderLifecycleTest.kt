package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ProviderLifecycleTest {
    private fun offer() = OfferedRequest(ProviderRequestKind.GET, "{}", byteArrayOf(1),
        CallerSnapshot("test.package", setOf("certificate"), null), "option-1")

    @Test fun `selection token is unique excludes concurrent claims and cannot survive process recreation`() {
        val registry = RequestRegistry()
        val one = registry.offer(offer())
        val two = registry.offer(offer())
        assertNotEquals(one, two)
        assertEquals("option-1", registry.claim(one).request.optionId)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(one) }
        assertThrows(IllegalArgumentException::class.java) { RequestRegistry().claim(two) }
    }

    @Test fun `expiry clear and malformed token reject without retaining a request`() {
        var time = 1L
        val registry = RequestRegistry { time }
        val expired = registry.offer(offer())
        time += 300_000_000_000L
        assertThrows(IllegalArgumentException::class.java) { registry.claim(expired) }
        val cleared = registry.offer(offer())
        val preserved = registry.offer(offer().copy(caller = CallerSnapshot("other", setOf("certificate"), null)))
        registry.clear("test.package")
        assertThrows(IllegalArgumentException::class.java) { registry.claim(cleared) }
        assertEquals("other", registry.claim(preserved).request.caller.packageName)
    }

    @Test fun `registry copies delegated hash to prevent mutable caller extras replacing binding`() {
        val request = offer()
        val registry = RequestRegistry()
        val token = registry.offer(request)
        request.hash!![0] = 2
        assertArrayEquals(byteArrayOf(1), registry.claim(token).request.hash)
    }

    @Test fun `four abandoned sixteen-option queries do not prevent a new caller offer`() {
        val registry = RequestRegistry()
        val old = (1..64).map { registry.offer(offer()) }
        val fresh = registry.offer(offer().copy(caller = CallerSnapshot("new.caller", setOf("certificate"), null)))
        assertEquals("new.caller", registry.claim(fresh).request.caller.packageName)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(old.first()) }
        assertEquals("option-1", registry.claim(old.last()).request.optionId)
    }

    @Test fun `safe cancellation permits reselection but stale owners cannot reserve or release it`() {
        val registry = RequestRegistry()
        val id = registry.offer(offer())
        val first = registry.claim(id)
        first.finish(cancelled = true)
        val second = registry.claim(id)
        assertFalse(first.reserveTransport())
        first.finish(cancelled = true)
        first.finish(cancelled = false)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(id) }
        assertTrue(second.reserveTransport())
        assertFalse(second.reserveTransport())
        second.finish(cancelled = true)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(id) }
    }

    @Test fun `capacity and expiry never evict an active or reserved operation`() {
        var time = 1L
        val registry = RequestRegistry { time }
        val activeId = registry.offer(offer())
        val active = registry.claim(activeId)
        val dispatchedId = registry.offer(offer())
        val dispatched = registry.claim(dispatchedId)
        assertTrue(dispatched.reserveTransport())
        repeat(128) { registry.offer(offer()) }
        time += 300_000_000_000L
        registry.offer(offer())
        assertThrows(IllegalArgumentException::class.java) { registry.claim(activeId) }
        assertThrows(IllegalArgumentException::class.java) { registry.claim(dispatchedId) }
        assertTrue(active.reserveTransport())
        dispatched.finish(cancelled = true)
        active.finish(cancelled = true)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(activeId) }
        assertThrows(IllegalArgumentException::class.java) { registry.claim(dispatchedId) }
    }

    @Test fun `all active entries stay bounded and expired cancellation cannot renew a token`() {
        var time = 1L
        val registry = RequestRegistry { time }
        val claims = (1..64).map { registry.claim(registry.offer(offer())) }
        assertThrows(IllegalStateException::class.java) { registry.offer(offer()) }
        time += 300_000_000_000L
        claims.first().finish(cancelled = true)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(claims.first().id) }
        registry.offer(offer())
    }

    @Test fun `cancelled claim fences a delayed worker even after the entry is selected again`() {
        val registry = RequestRegistry()
        val id = registry.offer(offer())
        val old = registry.claim(id)
        old.finish(cancelled = true)
        val current = registry.claim(id)
        val result = ProviderOperationRunner().execute(null, null,
            ClientData(byteArrayOf(), ByteArray(32), true), null, claim = old) { error("stale worker connected") }
        assertEquals(Dispatch.NOT_DISPATCHED, (result as OperationResult.Failed).dispatch)
        assertTrue(current.reserveTransport())
    }

    @Test fun `cancellation during connection never reopens even when no credential response is known`() {
        val registry = RequestRegistry()
        val id = registry.offer(offer())
        val claim = registry.claim(id)
        val runner = ProviderOperationRunner()
        val connecting = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val result = AtomicReference<OperationResult<ProviderOutput>>()
        val worker = Thread {
            result.set(runner.execute(null, null, ClientData(byteArrayOf(), ByteArray(32), true),
                null, claim = claim) {
                connecting.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            })
        }
        worker.start()
        assertTrue(connecting.await(5, TimeUnit.SECONDS))
        try {
            runner.cancel()
            claim.finish(cancelled = true)
            assertThrows(IllegalArgumentException::class.java) { registry.claim(id) }
            assertNull(CardOperationGate.tryAcquire())
        } finally { resume.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, (result.get() as OperationResult.Failed).dispatch)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(id) }
    }

    @Test fun `clear state invalidates active claim before any connection`() {
        val registry = RequestRegistry()
        val claim = registry.claim(registry.offer(offer()))
        registry.clear("test.package")
        assertFalse(claim.reserveTransport())
        claim.finish(cancelled = true)
        assertThrows(IllegalArgumentException::class.java) { registry.claim(claim.id) }
    }

    @Test fun `shared lease excludes manager provider and removal probe until operation finishes`() {
        val first = requireNotNull(CardOperationGate.tryAcquire())
        try { assertNull(CardOperationGate.tryAcquire()) } finally { first.close() }
        val second = requireNotNull(CardOperationGate.tryAcquire())
        first.close()
        assertNull(CardOperationGate.tryAcquire())
        second.close()
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    @Test fun `cancel and duplicate callbacks never acquire or connect again and clear pin`() {
        val runner = ProviderOperationRunner()
        val data = ClientData(byteArrayOf(), ByteArray(32), true)
        var connections = 0
        runner.cancel()
        repeat(2) {
            val pin = "123456".toCharArray()
            val result = runner.execute(null, null, data, pin) { connections++; error("must not connect") }
            assertEquals(FailureCode.CANCELLED, (result as OperationResult.Failed).code)
            assertTrue(pin.all { char -> char == '\u0000' })
        }
        assertEquals(0, connections)
    }

    @Test fun `contention fails before transport and never replays when lock becomes free`() {
        val runner = ProviderOperationRunner()
        val data = ClientData(byteArrayOf(), ByteArray(32), true)
        val lease = requireNotNull(CardOperationGate.tryAcquire())
        try {
            val result = runner.execute(null, null, data, null) { error("must not connect") }
            assertEquals(FailureCode.BUSY, (result as OperationResult.Failed).code)
        } finally { lease.close() }
        assertEquals(FailureCode.CANCELLED, (runner.execute(null, null, data, null) {
            error("must not replay")
        } as OperationResult.Failed).code)
    }

    @Test fun `cancel after assertion dispatch holds lease until worker exits and reports uncertain result`() {
        val dispatched = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val runner = ProviderOperationRunner()
        val channel = FidoChannel { code, _ -> when (code) {
            4 -> success(info())
            2 -> {
                dispatched.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            }
            else -> error("unexpected command")
        } }
        val result = AtomicReference<OperationResult<ProviderOutput>>()
        val worker = Thread {
            result.set(runner.execute(null, ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)),
                UserVerificationRequirement.DISCOURAGED), ClientData(byteArrayOf(), ByteArray(32), true), null) { channel })
        }
        worker.start()
        assertTrue(dispatched.await(5, TimeUnit.SECONDS))
        try {
            runner.cancel()
            assertTrue(channel.closed)
            assertNull(CardOperationGate.tryAcquire())
        } finally { resume.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        val failure = result.get() as OperationResult.Failed
        assertEquals(Effect.UNKNOWN, failure.effect)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure.dispatch)
        assertEquals(1, channel.commands.count { it.first == 2 })
        requireNotNull(CardOperationGate.tryAcquire()).close()
    }

    @Test fun `physical presence failure is shared across manager and provider until observed removal`() {
        val reader = "synthetic-reader"
        val channel = FidoChannel { code, _ -> when (code) {
            4 -> success(info())
            2 -> byteArrayOf(0x2f)
            else -> error("unexpected command")
        } }
        try {
            val result = ProviderOperationRunner().execute(null,
                ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED),
                ClientData(byteArrayOf(), ByteArray(32), true), null, reader) { channel }
            assertEquals(FailureCode.PHYSICAL_ACTION_REQUIRED, (result as OperationResult.Failed).code)
            assertTrue(CardOperationGate.isRemovalRequired(reader))
            assertEquals(listOf(4, 4, 2), channel.commands.map { it.first })
        } finally { CardOperationGate.observedRemoval(reader) }
        assertFalse(CardOperationGate.isRemovalRequired(reader))
    }

}
