package cc.cardkey.tls

import android.net.DnsResolver
import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [TlsDnsShadow::class])
class PivTlsDnsTest {
    @Before fun resetDns() { TlsDnsShadow.reset() }
    private val loopback get() = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private fun awaitQuery() {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (TlsDnsShadow.answer == null && System.nanoTime() < until) Thread.sleep(5)
        assertNotNull(TlsDnsShadow.answer)
    }
    private fun attempt(f: TlsFixture, server: TlsServer, deadline: Long = 45000) = PivTlsAttempt(
        f.identity, PivTlsEndpoint(server.address, f.issued.root), "123456".toCharArray(), { true }, { f.card },
        PivTlsConnection(deadline))

    @Test fun cancellationWakesNeverReturningDnsAndLateAnswerCannotConnectOrSign() {
        TlsDnsShadow.defer = true
        val f = TlsFixture()
        TlsServer(f).use { server ->
            val attempt = attempt(f, server)
            val ownedPin = org.robolectric.util.ReflectionHelpers.getField<CharArray>(attempt, "secret")
            assertTrue(attempt.preflight() is OperationResult.Completed)
            val result = AtomicReference<PivTlsResult>()
            val worker = Thread { result.set(attempt.execute(false)) }.apply { start() }
            try {
                awaitQuery()
                assertEquals("localhost", TlsDnsShadow.host)
                assertTrue(f.card.closed)
                CardOperationGate.tryAcquire()!!.close()
                attempt.close()
                worker.join(3000)
                assertFalse("DNS cancellation must finish without a callback", worker.isAlive)
                assertTrue(TlsDnsShadow.cancellation!!.isCanceled)
                assertTrue(ownedPin.all { it == '\u0000' })
                assertEquals(PivTlsOutcome.CANCELLED, result.get().outcome)
                assertEquals(Effect.NONE, (result.get().card as OperationResult.Completed).effect)
                TlsDnsShadow.answer!!.invoke(listOf(loopback), 0)
                assertEquals(0, server.accepted.get())
                assertEquals(0, f.card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
                assertThrows(IllegalStateException::class.java) { attempt.execute(false) }
            } finally { attempt.close(); worker.join(3000) }
        }
    }

    @Test fun overallDeadlineCancelsDnsWithoutCallbackAndRetainsTimeoutReceipt() {
        TlsDnsShadow.defer = true
        val f = TlsFixture()
        TlsServer(f).use { server ->
            val attempt = attempt(f, server, 1000)
            val ownedPin = org.robolectric.util.ReflectionHelpers.getField<CharArray>(attempt, "secret")
            assertTrue(attempt.preflight() is OperationResult.Completed)
            val result = AtomicReference<PivTlsResult>()
            val worker = Thread { result.set(attempt.execute(false)) }.apply { start() }
            try {
                awaitQuery()
                worker.join(3000)
                assertFalse("The overall deadline must include DNS", worker.isAlive)
                assertEquals(PivTlsOutcome.TIMED_OUT, result.get().outcome)
                assertTrue(ownedPin.all { it == '\u0000' })
                assertTrue(TlsDnsShadow.cancellation!!.isCanceled)
                TlsDnsShadow.answer!!.invoke(listOf(loopback), 0)
                assertEquals(0, server.accepted.get())
                assertEquals(0, f.card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
            } finally { attempt.close(); worker.join(3000) }
        }
    }

    @Test fun dnsErrorsAndEmptyOrNonzeroAnswersFailBeforeConnection() {
        for (mode in listOf("error", "empty", "rcode")) {
            TlsDnsShadow.reset(); TlsDnsShadow.defer = true
            val f = TlsFixture()
            TlsServer(f).use { server ->
                val attempt = attempt(f, server)
                assertTrue(attempt.preflight() is OperationResult.Completed)
                val result = AtomicReference<PivTlsResult>()
                val worker = Thread { result.set(attempt.execute(false)) }.apply { start() }
                try {
                    awaitQuery()
                    when (mode) {
                        "error" -> TlsDnsShadow.error!!.invoke(DnsResolver.DnsException(DnsResolver.ERROR_SYSTEM, java.io.IOException()))
                        "empty" -> TlsDnsShadow.answer!!.invoke(emptyList(), 0)
                        else -> TlsDnsShadow.answer!!.invoke(listOf(loopback), 3)
                    }
                    worker.join(3000)
                    assertFalse(worker.isAlive)
                    assertEquals(PivTlsOutcome.FAILED, result.get().outcome)
                    assertEquals(1, TlsDnsShadow.queries)
                    assertEquals(0, server.accepted.get())
                    assertEquals(0, f.card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
                } finally { attempt.close(); worker.join(3000) }
            }
        }
    }

    @Test fun onlyFirstAddressIsConnectedWhileOriginalHostRemainsSanAndSniIdentity() {
        for (goodFirst in listOf(false, true)) {
            TlsDnsShadow.reset(); TlsDnsShadow.defer = true
            val f = TlsFixture()
            TlsServer(f).use { server ->
                val attempt = attempt(f, server)
                assertTrue(attempt.preflight() is OperationResult.Completed)
                val result = AtomicReference<PivTlsResult>()
                val worker = Thread { result.set(attempt.execute(false)) }.apply { start() }
                try {
                    awaitQuery()
                    val unavailable = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
                    TlsDnsShadow.answer!!.invoke(if (goodFirst) listOf(loopback, unavailable) else listOf(unavailable, loopback), 0)
                    worker.join(5000)
                    assertFalse(worker.isAlive)
                    assertEquals(if (goodFirst) PivTlsOutcome.QUALIFIED else PivTlsOutcome.FAILED, result.get().outcome)
                    assertEquals(1, TlsDnsShadow.queries)
                    assertEquals(if (goodFirst) 1 else 0, server.accepted.get())
                    assertEquals(if (goodFirst) 1 else 0, f.card.commands.count { it.ins == 0x87 })
                    if (goodFirst) assertEquals("localhost", (server.requestedNames.single() as javax.net.ssl.SNIHostName).asciiName)
                } finally { attempt.close(); worker.join(3000) }
            }
        }
    }
}
