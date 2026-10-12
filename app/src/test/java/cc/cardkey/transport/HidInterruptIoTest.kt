package cc.cardkey.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class HidInterruptIoTest(private val numbered: Boolean) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "numbered={0}")
        fun modes() = listOf(false, true)
    }
    private fun layout(reports: FidoHidReports = FidoHidReports()) =
        if (numbered) reports.copy(inputReportID = 3, outputReportID = 9) else reports
    @Test fun inputAndOutputUseQueuedInterruptRequestsAndWipeNativeBuffersAfterCompletion() {
        val native = FakeConnection(numbered)
        val io = HidInterruptIo(native, layout())
        val output = ByteArray(64) { 42 }
        assertEquals(64, io.write(output, 1000))
        val input = ByteArray(64)
        assertEquals(64, io.read(input, 1000))
        assertArrayEquals(ByteArray(64) { 7 }, input)
        io.close()
        assertEquals(listOf(false, true), native.inputs)
        assertEquals(listOf("queue", "complete", "request-close", "queue", "complete", "request-close", "release", "connection-close"), native.events)
        assertTrue(native.buffers.all { buffer -> (0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() } })
    }

    @Test fun timeoutCancelsAndReapsBeforeRequestAndBufferAreRetired() {
        val native = FakeConnection(numbered)
        native.waits += { throw TimeoutException() }
        native.waits += { native.events += "reap"; native.current }
        expectFailure { HidInterruptIo(native, layout()).read(ByteArray(64), 50) }
        assertEquals(listOf("queue", "cancel", "reap", "request-close"), native.events)
        assertTrue(native.buffers[0].get(0) == 0.toByte())
    }

    @Test fun missingCancellationCompletionClosesConnectionBeforeRetiringNativeBuffer() {
        val native = FakeConnection(numbered)
        native.waits += { throw TimeoutException() }
        native.waits += { throw TimeoutException() }
        val io = HidInterruptIo(native, layout())
        expectFailure { io.read(ByteArray(64), 50) }
        io.close()
        assertEquals(listOf("queue", "cancel", "connection-close", "request-close"), native.events)
        assertEquals(0, native.events.count { it == "release" })
    }

    @Test fun throwingQueueIsTreatedAsPossibleNativeOwnership() {
        val native = FakeConnection(numbered)
        native.throwQueue = true
        native.waits += {
            val buffer = native.current.buffer!!
            assertEquals(if (numbered) 1025 else 1024, buffer.capacity())
            assertEquals(42.toByte(), buffer.get(buffer.capacity() - 1))
            assertEquals((if (numbered) 9 else 42).toByte(), buffer.get(0))
            native.events += "reap"
            native.current
        }
        expectFailure { HidInterruptIo(native, layout(FidoHidReports(7, 1024))).write(ByteArray(1024) { 42 }, 50) }
        assertEquals(listOf("queue", "cancel", "reap", "request-close"), native.events)
        assertTrue((0 until native.buffers.single().capacity()).all { native.buffers.single().get(it) == 0.toByte() })
    }

    @Test fun rejectedQueueDoesNotWaitAndClosesRequest() {
        val native = FakeConnection(numbered)
        native.queueResult = false
        expectFailure { HidInterruptIo(native, layout()).write(ByteArray(64), 50) }
        assertEquals(listOf("queue", "request-close"), native.events)
    }

    @Test fun closeWaitsForNativeRetirementAndLateReadIsNotReturned() {
        val native = FakeConnection(numbered)
        val waiting = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        native.onCancel = { cancelled.countDown() }
        native.waits += {
            waiting.countDown()
            check(allowCompletion.await(5, TimeUnit.SECONDS))
            native.current.buffer!!.put(ByteArray(if (numbered) 65 else 64) { 77 })
            native.events += "late-complete"
            native.current
        }
        val io = HidInterruptIo(native, layout())
        val target = ByteArray(64)
        val failure = AtomicReference<Exception>()
        val reader = Thread { try { io.read(target, 1000); fail() } catch (e: Exception) { failure.set(e) } }
        reader.start()
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        val closer = Thread { io.close() }
        closer.start()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(closer.isAlive)
        assertFalse(native.events.contains("release"))
        assertFalse(native.events.contains("request-close"))
        allowCompletion.countDown()
        reader.join(5000); closer.join(5000)
        assertFalse(reader.isAlive); assertFalse(closer.isAlive)
        assertNotNull(failure.get())
        assertArrayEquals(ByteArray(64), target)
        assertTrue(native.events.indexOf("late-complete") < native.events.indexOf("request-close"))
        assertTrue(native.events.indexOf("request-close") < native.events.indexOf("release"))
    }

    @Test fun interruptedIoRejectsNewReportsButAllowsCancelAfterPreviousRetires() {
        val native = FakeConnection(numbered)
        val io = HidInterruptIo(native, layout())
        io.interrupt()
        expectFailure { io.write(ByteArray(64), 50) }
        io.sendCancel(ByteArray(64), 50)
        io.close()
        assertEquals(1, native.inputs.size)
        assertEquals(listOf("queue", "complete", "request-close", "release", "connection-close"), native.events)
    }

    @Test fun unequalReportsUseExactNativeAllocationsAndRejectPartialCompletions() {
        for (reports in listOf(FidoHidReports(7, 8), FidoHidReports(16, 32), FidoHidReports(1024, 7))) {
            val native = FakeConnection(numbered)
            val io = HidInterruptIo(native, layout(reports))
            assertEquals(reports.outputBytes, io.write(ByteArray(reports.outputBytes), 100))
            assertEquals(reports.inputBytes, io.read(ByteArray(reports.inputBytes), 100))
            assertEquals(listOf(layout(reports).outputNativeBytes, layout(reports).inputNativeBytes), native.buffers.map { it.capacity() })
            assertTrue(native.buffers.all { buffer -> (0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() } })
            for (input in listOf(false, true)) {
                native.waits += { native.current.buffer!!.position(1); native.current }
                val target = ByteArray(if (input) reports.inputBytes else reports.outputBytes)
                expectFailure { if (input) io.read(target, 100) else io.write(target, 100) }
                assertArrayEquals(ByteArray(target.size), target)
            }
            io.close()
        }
    }

    @Test fun unequalCancelledReportsReapOrCloseBeforeWipingEntireAllocation() {
        for (reap in listOf(false, true)) {
            val native = FakeConnection(numbered)
            native.waits += { throw TimeoutException() }
            native.waits += {
                val buffer = native.current.buffer!!
                assertEquals(if (numbered) 1025 else 1024, buffer.capacity())
                assertTrue((0 until buffer.capacity()).all { buffer.get(it) == (if (numbered && it == 0) 9 else 42).toByte() })
                if (reap) { native.events += "reap"; native.current } else null
            }
            val io = HidInterruptIo(native, layout(FidoHidReports(7, 1024)))
            expectFailure { io.write(ByteArray(1024) { 42 }, 100) }
            assertTrue(native.events.indexOf(if (reap) "reap" else "connection-close") < native.events.indexOf("request-close"))
            assertTrue((0 until native.buffers.single().capacity()).all { native.buffers.single().get(it) == 0.toByte() })
            io.close()
        }
    }

    @Test fun lateUnequalInputCannotReleaseOutputBeforeNativeRetirement() {
        val native = FakeConnection(numbered)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val complete = CountDownLatch(1)
        native.onCancel = { cancelled.countDown() }
        native.waits += {
            entered.countDown()
            check(complete.await(5, TimeUnit.SECONDS))
            native.current.buffer!!.put(ByteArray(if (numbered) 1025 else 1024) { 77 })
            native.events += "late-complete"
            native.current
        }
        val io = HidInterruptIo(native, layout(FidoHidReports(1024, 7)))
        val target = ByteArray(1024)
        val failure = AtomicReference<Exception>()
        val reader = Thread { try { io.read(target, 1000) } catch (e: Exception) { failure.set(e) } }
        reader.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread { io.close() }
        closer.start()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(closer.isAlive)
        assertFalse(native.events.contains("request-close"))
        complete.countDown()
        reader.join(5000); closer.join(5000)
        assertFalse(reader.isAlive); assertFalse(closer.isAlive)
        assertNotNull(failure.get())
        assertArrayEquals(ByteArray(1024), target)
        assertTrue(native.events.indexOf("late-complete") < native.events.indexOf("request-close"))
        assertTrue((0 until native.buffers.single().capacity()).all { native.buffers.single().get(it) == 0.toByte() })
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: IOException) { } catch (_: TimeoutException) { }
    }

    private class FakeConnection(private val numbered: Boolean) : HidInterruptConnection {
        val inputs = mutableListOf<Boolean>()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val buffers = mutableListOf<ByteBuffer>()
        val waits = ArrayDeque<() -> HidInterruptRequest?>()
        lateinit var current: Request
        var queueResult = true
        var throwQueue = false
        var onCancel: () -> Unit = {}
        override fun request(input: Boolean): HidInterruptRequest {
            inputs += input
            return Request(input).also { current = it }
        }
        override fun await(timeoutMillis: Int): HidInterruptRequest? {
            if (waits.isNotEmpty()) return waits.removeFirst()()
            val buffer = current.buffer!!
            if (current.input) buffer.put(ByteArray(buffer.capacity()) { if (numbered && it == 0) 3 else 7 })
            else buffer.position(buffer.capacity())
            events += "complete"
            return current
        }
        override fun releaseInterface() { events += "release" }
        override fun close() { events += "connection-close" }
        inner class Request(val input: Boolean) : HidInterruptRequest {
            var buffer: ByteBuffer? = null
            override fun queue(buffer: ByteBuffer): Boolean {
                this.buffer = buffer
                buffers += buffer
                events += "queue"
                if (throwQueue) throw IOException("Queue outcome unavailable")
                return queueResult
            }
            override fun cancel() { events += "cancel"; onCancel() }
            override fun close() { events += "request-close" }
        }
    }
}
