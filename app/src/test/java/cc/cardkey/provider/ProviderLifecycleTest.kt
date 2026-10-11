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

    @Test fun `selection token is unique consumed once and cannot survive process recreation`() {
        val registry = RequestRegistry()
        val one = registry.offer(offer())
        val two = registry.offer(offer())
        assertNotEquals(one, two)
        assertEquals("option-1", registry.claim(one).optionId)
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
        assertEquals("other", registry.claim(preserved).caller.packageName)
    }

    @Test fun `registry copies delegated hash to prevent mutable caller extras replacing binding`() {
        val request = offer()
        val registry = RequestRegistry()
        val token = registry.offer(request)
        request.hash!![0] = 2
        assertArrayEquals(byteArrayOf(1), registry.claim(token).hash)
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
