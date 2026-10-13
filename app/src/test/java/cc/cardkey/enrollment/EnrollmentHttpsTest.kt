package cc.cardkey.enrollment

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyStore
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

internal class EnrollmentHttpsServer(private val fixture: EnrollmentFixtures, private val response: () -> String) : AutoCloseable {
    private val keys = EnrollmentFixtures.key()
    private val builder = JcaX509v3CertificateBuilder(fixture.rootName, BigInteger.TEN,
        Date(fixture.now - 60000), Date(fixture.now + 3600000), X500Name("CN=localhost"), keys.public).apply {
        addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, "localhost")))
    }
    private val leaf = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(fixture.rootKey.private)).encoded
    private val context = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("server", keys.private, "synthetic".toCharArray(), arrayOf(EnrollmentProfile.certificate(leaf), EnrollmentProfile.certificate(fixture.root)))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "synthetic".toCharArray()) }
        init(factory.keyManagers, null, null)
    }
    private val socket = context.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val address = "https://localhost:${socket.localPort}"
    val requests = AtomicInteger()
    private val worker = Thread {
        while (!socket.isClosed) {
            runCatching {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: return@use
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    var remaining = length
                    val buffer = CharArray(1024)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) return@use
                        remaining -= read
                    }
                    requests.incrementAndGet()
                    client.getOutputStream().write(response().toByteArray())
                    client.getOutputStream().flush()
                }
            }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(6000); check(!worker.isAlive) }
}

class EnrollmentHttpsTest {
    private val fixture = EnrollmentFixtures()
    private fun request(server: EnrollmentHttpsServer, ca: EnrollmentCA = EnrollmentCA(server.address, fixture.root)): EnrollmentException {
        val token = fixture.token(address = ca.endpoint.toString().removeSuffix("/1.0/sign"))
        return runCatching { ca.execute(ca.call(fixture.csr, token), fixture.csr) }.exceptionOrNull() as EnrollmentException
    }
    @Test fun selectedRootAndNormalHostnameChecksRejectBeforeHttp() {
        EnrollmentHttpsServer(fixture) { error("HTTP must not be dispatched") }.use { server ->
            assertEquals(IssuanceFailure.UNKNOWN, request(server, EnrollmentCA(server.address, EnrollmentFixtures().root)).failure)
            assertEquals(IssuanceFailure.UNKNOWN, request(server, EnrollmentCA(server.address.replace("localhost", "127.0.0.1"), fixture.root)).failure)
            assertEquals(0, server.requests.get())
        }
    }
    @Test fun redirectServerFailureAndLostResponseAreUnknownWithoutRetry() {
        for (response in listOf("HTTP/1.1 302 Found\r\nLocation: https://localhost:1/1.0/sign\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 503 Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 201 Created\r\nContent-Length: 20\r\n\r\n{")) {
            EnrollmentHttpsServer(fixture) { response }.use { server ->
                assertEquals(IssuanceFailure.UNKNOWN, request(server).failure)
                assertEquals(1, server.requests.get())
            }
        }
    }
    @Test fun explicitRejectionsAndInvalidOversizeResponsesStayDistinct() {
        val cases = listOf(
            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n" to IssuanceFailure.REJECTED,
            "HTTP/1.1 201 Created\r\nContent-Length: 131073\r\n\r\n" to IssuanceFailure.INVALID,
            "HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\n{}" to IssuanceFailure.INVALID,
            "HTTP/1.1 201 Created\r\nConnection: close\r\n\r\n" + "x".repeat(131073) to IssuanceFailure.INVALID)
        for ((response, expected) in cases) EnrollmentHttpsServer(fixture) { response }.use { server ->
            assertEquals(expected, request(server).failure)
            assertEquals(1, server.requests.get())
        }
    }
}
