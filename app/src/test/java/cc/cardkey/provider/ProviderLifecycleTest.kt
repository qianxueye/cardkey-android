package cc.cardkey.provider

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ProviderLifecycleTest {
    private fun getRequest() = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED)
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
        val result = ProviderOperationRunner().execute(null, getRequest(),
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
            result.set(runner.execute(null, getRequest(), ClientData(byteArrayOf(), ByteArray(32), true),
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
            val result = runner.execute(null, getRequest(), data, pin) { connections++; error("must not connect") }
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
            val result = runner.execute(null, getRequest(), data, null) { error("must not connect") }
            assertEquals(FailureCode.BUSY, (result as OperationResult.Failed).code)
        } finally { lease.close() }
        assertEquals(FailureCode.CANCELLED, (runner.execute(null, getRequest(), data, null) {
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

    @Test fun `refusing blob write confirmation prevents credential proof and write before card connection`() {
        for (plaintext in listOf(byteArrayOf(), byteArrayOf(9))) {
            val runner = ProviderOperationRunner()
            val request = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.REQUIRED,
                extensions = GetExtensionInputs(largeBlobWrite = plaintext))
            var connections = 0; var apdus = 0
            val pin = "123456".toCharArray()
            runner.cancel()
            val result = runner.execute(null, request, ClientData(byteArrayOf(), ByteArray(32), true), pin) {
                connections++
                object : CardChannel {
                    override val medium = Medium.CONTACT
                    override val maxCommandBytes = 261
                    override fun transmit(command: ByteArray): ByteArray { apdus++; error("Refused request reached the card") }
                    override fun close() = Unit
                }
            }
            assertEquals(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE, dispatch = Dispatch.NOT_DISPATCHED), result)
            assertEquals(0, connections); assertEquals(0, apdus)
            assertArrayEquals(CharArray(6), pin)
        }
    }

    @Test fun cancellationDoesNotWipeInputsUntilActiveConsumerExits() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val runner = ProviderOperationRunner()
        val registry = RequestRegistry()
        val id = registry.offer(offer())
        val claim = registry.claim(id)
        val inputs = GetExtensionInputs(prfInputs = PrfInputs(byteArrayOf(3), byteArrayOf(4)),
            largeBlobWrite = byteArrayOf(5))
        val prf = requireNotNull(inputs.prfInputs)
        val request = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)),
            UserVerificationRequirement.DISCOURAGED, inputs)
        val channel = FidoChannel { code, _ ->
            assertEquals(4, code)
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(3), prf.first)
            assertArrayEquals(byteArrayOf(5), inputs.largeBlobWrite)
            throw CardFailure(FailureCode.CANCELLED)
        }
        val worker = Thread {
            runner.execute(null, request, ClientData(byteArrayOf(), ByteArray(32), true), null, claim = claim) { channel }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            runner.cancel()
            assertFalse(claim.finish(cancelled = true))
            assertThrows(IllegalArgumentException::class.java) { registry.claim(id) }
            assertArrayEquals(byteArrayOf(3), prf.first)
            assertArrayEquals(byteArrayOf(5), inputs.largeBlobWrite)
            val duplicate = runner.execute(null, request, ClientData(byteArrayOf(), ByteArray(32), true), null, claim = claim) { error("no replay") }
            assertEquals(FailureCode.CANCELLED, (duplicate as OperationResult.Failed).code)
            assertArrayEquals(byteArrayOf(3), prf.first)
        } finally { resume.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertArrayEquals(byteArrayOf(0), prf.first)
        assertArrayEquals(byteArrayOf(0), prf.second)
        assertArrayEquals(byteArrayOf(0), inputs.largeBlobWrite)
    }

    @Test fun preOperationCancellationContentionAndConnectionFailureWipeInputs() {
        for (kind in listOf("cancel", "busy", "connect")) {
            val runner = ProviderOperationRunner()
            val inputs = GetExtensionInputs(prfInputs = PrfInputs(byteArrayOf(3)), largeBlobWrite = byteArrayOf(5))
            val request = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED, inputs)
            val lease = if (kind == "busy") CardOperationGate.tryAcquire() else null
            if (kind == "cancel") runner.cancel()
            try {
                assertTrue(runner.execute(null, request, ClientData(byteArrayOf(), ByteArray(32), true), null) {
                    assertEquals("connect", kind)
                    throw TransportException(Dispatch.NOT_DISPATCHED)
                } is OperationResult.Failed)
            } finally { lease?.close() }
            assertArrayEquals(byteArrayOf(0), inputs.prfInputs!!.first)
            assertArrayEquals(byteArrayOf(0), inputs.largeBlobWrite)
        }
    }

    @Test fun staleClaimCleansOnlyItsOwnInputsAndCannotReleaseReselectedClaim() {
        val registry = RequestRegistry()
        val id = registry.offer(offer())
        val stale = registry.claim(id)
        assertTrue(stale.finish(cancelled = true))
        val selected = registry.claim(id)
        val oldInputs = GetExtensionInputs(prfInputs = PrfInputs(byteArrayOf(3)), largeBlobWrite = byteArrayOf(5))
        val newInputs = GetExtensionInputs(prfInputs = PrfInputs(byteArrayOf(7)), largeBlobWrite = byteArrayOf(9))
        val oldRequest = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED, oldInputs)
        val result = ProviderOperationRunner().execute(null, oldRequest,
            ClientData(byteArrayOf(), ByteArray(32), true), null, claim = stale) { error("stale request connected") }
        assertEquals(FailureCode.CANCELLED, (result as OperationResult.Failed).code)
        assertArrayEquals(byteArrayOf(0), oldInputs.prfInputs!!.first)
        assertArrayEquals(byteArrayOf(0), oldInputs.largeBlobWrite)
        assertArrayEquals(byteArrayOf(7), newInputs.prfInputs!!.first)
        assertArrayEquals(byteArrayOf(9), newInputs.largeBlobWrite)
        assertFalse(stale.finish(cancelled = true))
        val newRequest = oldRequest.copy(extensions = newInputs)
        val selectedResult = ProviderOperationRunner().execute(null, newRequest,
            ClientData(byteArrayOf(), ByteArray(32), true), null, claim = selected) {
            assertArrayEquals(byteArrayOf(7), newInputs.prfInputs.first)
            assertArrayEquals(byteArrayOf(9), newInputs.largeBlobWrite)
            throw TransportException(Dispatch.NOT_DISPATCHED)
        }
        assertEquals(FailureCode.TRANSPORT_FAILURE, (selectedResult as OperationResult.Failed).code)
        assertArrayEquals(byteArrayOf(0), newInputs.prfInputs.first)
        assertArrayEquals(byteArrayOf(0), newInputs.largeBlobWrite)
        assertFalse(selected.finish(cancelled = true))
        assertThrows(IllegalArgumentException::class.java) { registry.claim(id) }
    }

}
