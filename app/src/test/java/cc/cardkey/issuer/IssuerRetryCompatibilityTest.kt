package cc.cardkey.issuer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IssuerRetryCompatibilityTest {
    @Test fun issuer503WithRetryAfterZeroMakesExactlyOneHttpAttempt() = runBlocking {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            while (!server.isClosed && requests.get() < 2) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    if (input.readLine() == null) return@use
                    while (true) { val line = input.readLine() ?: return@use; if (line.isEmpty()) break }
                    requests.incrementAndGet()
                    val body = """{"error":{"code":"busy"}}""".toByteArray()
                    val headers = "HTTP/1.1 503 Busy\r\nContent-Type: application/json\r\nRetry-After: 0\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
                }
            }
        }
        try {
            val client = IssuerClient("http://127.0.0.1:${server.localPort}", "synthetic-token")
            val error = runCatching { client.me() }.exceptionOrNull()
            assertTrue(error is IssuerFailure)
            assertEquals(503, (error as IssuerFailure).status)
            assertEquals("A single issuer call must produce one HTTP attempt", 1, requests.get())
        } finally {
            server.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
