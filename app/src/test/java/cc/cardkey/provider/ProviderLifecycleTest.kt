package cc.cardkey.provider

import cc.cardkey.protocol.*
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
}
