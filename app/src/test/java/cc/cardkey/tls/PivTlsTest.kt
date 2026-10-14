package cc.cardkey.tls

import cc.cardkey.enrollment.*
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

internal class TlsFixture {
    val issued = EnrollmentFixtures()
    val card = EnrollmentCard()
    val csr = issued.csr(key = card.keyPair)
    val leaf = issued.leaf(key = card.keyPair)
    val bundle = EnrollmentBundle(listOf(leaf, issued.intermediate), issued.now)
    val identity = PivTlsIdentity(EnrollmentImport(csr, EnrollmentCA("https://localhost", issued.root), bundle))
    init { card.certificate = leaf }
    fun attempt(endpoint: PivTlsEndpoint, current: () -> Boolean = { true }, connect: () -> CardChannel = { card }) =
        PivTlsAttempt(identity, endpoint, "123456".toCharArray(), current, connect)
}

internal class TlsServer(
    private val fixture: TlsFixture,
    name: String = "localhost",
    private val response: String = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n",
    private val expectedClient: ByteArray = fixture.leaf,
    private val requestClient: Boolean = true,
) : AutoCloseable {
    private val issuer = fixture.issued
    private val key = EnrollmentFixtures.key()
    val leaf = JcaX509v3CertificateBuilder(issuer.rootName, BigInteger.TEN,
        Date(issuer.now - 60000), Date(issuer.now + 3600000), X500Name("CN=localhost"), key.public).apply {
        addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, name)))
    }.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuer.rootKey.private)).encoded
    private val context = SSLContext.getInstance("TLSv1.3").apply {
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("server", key.private, "test".toCharArray(), arrayOf(EnrollmentProfile.certificate(leaf)))
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test".toCharArray()) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("client", EnrollmentProfile.certificate(issuer.root)) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val delegate = tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        val exact = object : X509TrustManager {
            override fun getAcceptedIssuers() = delegate.acceptedIssuers
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = throw AssertionError()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                delegate.checkClientTrusted(chain, authType)
                if (!chain.first().encoded.contentEquals(expectedClient)) throw java.security.cert.CertificateException("wrong leaf")
            }
        }
        init(km.keyManagers, arrayOf(exact), null)
    }
    private val socket = (context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
        enabledProtocols = arrayOf("TLSv1.3")
        needClientAuth = requestClient
    }
    val address = "https://localhost:${socket.localPort}/qualify"
    val requests = AtomicInteger()
    val accepted = AtomicInteger()
    @Volatile var failure: Throwable? = null
    private val worker = Thread {
        try {
            socket.accept().use { raw ->
                accepted.incrementAndGet()
                val client = raw as SSLSocket
                client.soTimeout = 5000
                client.startHandshake()
                val input = client.inputStream.bufferedReader()
                assertEquals("GET /qualify HTTP/1.1", input.readLine())
                while (input.readLine()?.isNotEmpty() == true) { }
                requests.incrementAndGet()
                client.outputStream.write(response.toByteArray())
                client.outputStream.flush()
            }
        } catch (error: Throwable) { failure = error }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(6000); check(!worker.isAlive) }
}

class PivTlsTest {
    @Test fun realTlsHandshakeSignsOneDigestAndPreservesProviders() {
        val providers = Security.getProviders().map { it.name }
        val f = TlsFixture()
        TlsServer(f).use { server ->
            val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root))
            assertTrue(attempt.preflight() is OperationResult.Completed)
            assertTrue(f.card.closed)
            assertNotNull(CardOperationGate.tryAcquire()?.also { it.close() })
            val result = attempt.execute(false)
            assertEquals(server.failure?.toString(), PivTlsOutcome.QUALIFIED, result.outcome)
            assertEquals(Effect.APPLIED, (result.card as OperationResult.Completed).effect)
            assertEquals(1, server.requests.get())
            assertEquals(1, f.card.commands.count { it.ins == 0x20 })
            val signature = f.card.commands.single { it.ins == 0x87 }
            assertEquals(0x9a, signature.p2)
            assertEquals(0x11, signature.p1)
            assertEquals(32, PivTlv.parse(PivTlv.one(signature.data, 0x7c)).getValue(0x81).size)
            assertThrows(IllegalStateException::class.java) { attempt.execute(false) }
        }
        assertEquals(providers, Security.getProviders().map { it.name })
    }

    @Test fun wrongServerTrustAndSanCannotConsumePin() {
        for (wrongName in listOf(false, true)) {
            val f = TlsFixture()
            TlsServer(f, name = if (wrongName) "wrong.invalid" else "localhost").use { server ->
                val root = if (wrongName) f.issued.root else EnrollmentFixtures().root
                val attempt = f.attempt(PivTlsEndpoint(server.address, root))
                assertTrue(attempt.preflight() is OperationResult.Completed)
                assertEquals(PivTlsOutcome.FAILED, attempt.execute(false).outcome)
                assertEquals(0, f.card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun wrongClientAndServerWithoutClientRequestFail() {
        for (request in listOf(false, true)) {
            val f = TlsFixture()
            TlsServer(f, requestClient = request, expectedClient = EnrollmentFixtures().leaf()).use { server ->
                val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root))
                assertTrue(attempt.preflight() is OperationResult.Completed)
                assertEquals(PivTlsOutcome.FAILED, attempt.execute(false).outcome)
                assertEquals(if (request) 1 else 0, f.card.commands.count { it.ins == 0x20 })
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun actualServerRejectsDoubleHashedCertificateVerifyAndMissingClient() {
        for (doubleHash in listOf(true, false)) {
            val f = TlsFixture()
            TlsServer(f).use { server ->
                if (doubleHash) {
                    var signatures = 0
                    assertThrows(Exception::class.java) {
                        PivTlsConnection().execute(f.identity, PivTlsEndpoint(server.address, f.issued.root), { digest ->
                            signatures++
                            assertEquals(32, digest.size)
                            Signature.getInstance("SHA256withECDSA").run { initSign(f.card.keyPair.private); update(digest); sign() }
                        }, {})
                    }
                    assertEquals(1, signatures)
                } else {
                    val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null); setCertificateEntry("server", EnrollmentProfile.certificate(f.issued.root))
                    }
                    val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                    val context = SSLContext.getInstance("TLSv1.3").apply { init(null, managers.trustManagers, null) }
                    assertThrows(Exception::class.java) {
                        (context.socketFactory.createSocket("localhost", java.net.URI(server.address).port) as SSLSocket).use {
                            it.soTimeout = 5000
                            it.startHandshake()
                            it.outputStream.write("GET /qualify HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                            it.inputStream.read()
                        }
                    }
                }
                assertEquals(0, server.requests.get())
                assertEquals(1, server.accepted.get())
            }
        }
    }

    @Test fun deadlineClosesOwnedSocketAndCancellationCallback() {
        val f = TlsFixture()
        val socket = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            socket.accept().use { client ->
                accepted.countDown()
                client.soTimeout = 3000
                while (client.inputStream.read() >= 0) { }
            }
        }.apply { isDaemon = true; start() }
        val cancelled = java.util.concurrent.CountDownLatch(1)
        try {
            val connection = PivTlsConnection(300)
            assertThrows(Exception::class.java) {
                connection.execute(f.identity, PivTlsEndpoint("https://localhost:${socket.localPort}", f.issued.root),
                    { error("no PIN before authenticated server") }, { cancelled.countDown() })
            }
            assertTrue(accepted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            worker.join(4000)
            assertFalse(worker.isAlive)
            assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { socket.close(); worker.join(4000) }
    }

    @Test fun digestIsUnchangedAndDoubleHashCannotVerify() {
        val f = TlsFixture()
        val session = CardSession(f.card)
        val client = PivClient(session)
        val facts = client.checkTlsIdentity(f.leaf, f.csr.spki)
        val digest = MessageDigest.getInstance("SHA-256").digest("TLS context plus transcript".toByteArray())
        val result = session.run { client.signTlsDigest("123456".toCharArray(), digest, f.leaf, f.csr.spki, facts, false) }
        val signature = (result as OperationResult.Completed).value
        fun verifies(input: ByteArray): Boolean = Signature.getInstance("NONEwithECDSA").run {
            initVerify(f.card.keyPair.public); update(input); this.verify(signature)
        }
        assertTrue(verifies(digest))
        assertFalse(verifies(MessageDigest.getInstance("SHA-256").digest(digest)))
        assertFalse(verifies(digest.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertArrayEquals(digest, PivTlv.parse(PivTlv.one(f.card.commands.single { it.ins == 0x87 }.data, 0x7c)).getValue(0x81))
    }

    @Test fun retriesLeafAndRouteAreRecheckedBeforePin() {
        for (change in 0..3) {
            val f = TlsFixture()
            var current = true
            TlsServer(f).use { server ->
                val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root), { current })
                assertTrue(attempt.preflight() is OperationResult.Completed)
                when (change) {
                    0 -> f.card.pinRemaining = 1
                    1 -> f.card.certificate = f.issued.leaf()
                    2 -> current = false
                    3 -> attempt.close()
                }
                assertNotEquals(PivTlsOutcome.QUALIFIED, attempt.execute(false).outcome)
                current = true
                assertEquals(0, f.card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
            }
        }
    }

    @Test fun lastRetryApprovalAndBusyGateFailBeforePin() {
        val f = TlsFixture()
        f.card.pinRemaining = 1
        f.card.pukRemaining = 0
        TlsServer(f).use { server ->
            val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root))
            assertTrue(attempt.preflight() is OperationResult.Completed)
            assertEquals(PivTlsOutcome.FAILED, attempt.execute(false).outcome)
            assertEquals(0, f.card.commands.count { it.ins == 0x20 })
        }
        val busy = f.attempt(PivTlsEndpoint("https://localhost:1", f.issued.root))
        CardOperationGate.tryAcquire()!!.use {
            assertEquals(FailureCode.BUSY, (busy.preflight() as OperationResult.Failed).code)
        }
    }

    @Test fun cancellationAtPinKeepsOwnedCopyAndStopsGa() {
        val f = TlsFixture()
        lateinit var attempt: PivTlsAttempt
        val atPin = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var correctPin = false
        val controlled = object : CardChannel by f.card {
            override fun transmit(command: ByteArray): ByteArray {
                if (command[1].toInt() and 255 == 0x20) {
                    atPin.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    correctPin = command.copyOfRange(5, 11).contentEquals("123456".toByteArray())
                }
                return f.card.transmit(command)
            }
            override fun cancel() { release.countDown() }
        }
        TlsServer(f).use { server ->
            attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root), connect = { controlled })
            assertTrue(attempt.preflight() is OperationResult.Completed)
            val answer = java.util.concurrent.atomic.AtomicReference<PivTlsResult>()
            val worker = Thread { answer.set(attempt.execute(false)) }.apply { start() }
            try {
                assertTrue(atPin.await(5, java.util.concurrent.TimeUnit.SECONDS))
                attempt.close()
                worker.join(6000)
                assertFalse(worker.isAlive)
                assertTrue(correctPin)
                assertEquals(PivTlsOutcome.CANCELLED, answer.get().outcome)
                val card = answer.get().card as OperationResult.Failed
                assertEquals(Effect.APPLIED, card.effect)
                assertEquals(Dispatch.NOT_DISPATCHED, card.dispatch)
                assertEquals(0, f.card.commands.count { it.ins == 0x87 })
            } finally { release.countDown(); attempt.close(); worker.join(6000) }
        }
    }

    @Test fun lostPinOrGaResponseRetainsUnknownAndDispatchWithoutRetry() {
        for (ins in listOf(0x20, 0x87)) {
            val f = TlsFixture()
            val lossy = object : CardChannel by f.card {
                override fun transmit(command: ByteArray): ByteArray {
                    val response = f.card.transmit(command)
                    if (command[1].toInt() and 255 == ins) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    return response
                }
            }
            TlsServer(f).use { server ->
                val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root), connect = { lossy })
                assertTrue(attempt.preflight() is OperationResult.Completed)
                val result = attempt.execute(false)
                assertEquals(PivTlsOutcome.FAILED, result.outcome)
                val card = result.card as OperationResult.Failed
                assertEquals(Effect.UNKNOWN, card.effect)
                assertEquals(Dispatch.RESPONSE_UNAVAILABLE, card.dispatch)
                assertEquals(1, f.card.commands.count { it.ins == ins })
                assertEquals(if (ins == 0x20) 0 else 1, f.card.commands.count { it.ins == 0x87 })
            }
        }
    }

    @Test fun busyAtActualSignatureAndWrongDigestNeverSpendAnotherAttempt() {
        val f = TlsFixture()
        TlsServer(f).use { server ->
            val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root))
            assertTrue(attempt.preflight() is OperationResult.Completed)
            CardOperationGate.tryAcquire()!!.use {
                val result = attempt.execute(false)
                assertEquals(FailureCode.BUSY, (result.card as OperationResult.Failed).code)
                assertEquals(0, f.card.commands.count { it.ins == 0x20 })
            }
        }
        val session = CardSession(f.card)
        val client = PivClient(session)
        val facts = client.checkTlsIdentity(f.leaf, f.csr.spki)
        val invalid = session.run { client.signTlsDigest("123456".toCharArray(), ByteArray(31), f.leaf, f.csr.spki, facts, false) }
        assertEquals(FailureCode.INVALID_INPUT, (invalid as OperationResult.Failed).code)
        assertEquals(0, f.card.commands.count { it.ins == 0x20 })
    }

    @Test fun peerRejectsUnsupportedRepeatedCredentialsSignAndResumption() {
        val f = TlsFixture()
        TlsServer(f).use { server ->
            var signs = 0
            fun peer() = PivTlsPeer(f.identity, PivTlsEndpoint(server.address, f.issued.root)) { signs++; byteArrayOf() }
            val peer = peer()
            val auth = peer.authentication
            val serverCertificate = object : org.bouncycastle.tls.TlsServerCertificate {
                override fun getCertificate() = org.bouncycastle.tls.Certificate(arrayOf(peer.crypto.createCertificate(server.leaf)))
                override fun getCertificateStatus(): org.bouncycastle.tls.CertificateStatus? = null
            }
            auth.notifyServerCertificate(serverCertificate)
            val algorithms = java.util.Vector(listOf(PIV_TLS_ALGORITHM))
            val request = org.bouncycastle.tls.CertificateRequest(byteArrayOf(), algorithms, null, null)
            val unsupported = org.bouncycastle.tls.CertificateRequest(byteArrayOf(), java.util.Vector(listOf(
                org.bouncycastle.tls.SignatureAndHashAlgorithm.rsa_pss_rsae_sha256)), null, null)
            val rejected = peer().authentication
            rejected.notifyServerCertificate(serverCertificate)
            assertThrows(java.io.IOException::class.java) { rejected.getClientCredentials(unsupported) }
            assertThrows(java.io.IOException::class.java) { rejected.getClientCredentials(request) }
            val credentials = auth.getClientCredentials(request) as org.bouncycastle.tls.TlsCredentialedSigner
            assertNull(credentials.streamSigner)
            assertThrows(java.io.IOException::class.java) { auth.getClientCredentials(request) }
            credentials.generateRawSignature(ByteArray(32))
            assertThrows(java.io.IOException::class.java) { credentials.generateRawSignature(ByteArray(32)) }
            assertEquals(1, signs)
            val badDigestAuth = peer().authentication
            badDigestAuth.notifyServerCertificate(serverCertificate)
            val badDigest = badDigestAuth.getClientCredentials(request) as org.bouncycastle.tls.TlsCredentialedSigner
            assertThrows(java.io.IOException::class.java) { badDigest.generateRawSignature(ByteArray(31)) }
            assertThrows(java.io.IOException::class.java) { badDigest.generateRawSignature(ByteArray(32)) }
            assertEquals(1, signs)
            assertThrows(java.io.IOException::class.java) { peer.notifySessionToResume(org.bouncycastle.tls.TlsUtils.importSession(byteArrayOf(1), null)) }
            peer.notifyHandshakeBeginning()
            assertThrows(java.io.IOException::class.java) { peer.notifyHandshakeBeginning() }
        }
    }

    @Test fun issuanceTimeIsPreservedAndCurrentExpiryIsEnforced() {
        val f = TlsFixture()
        f.identity.validate(f.issued.now + 600000)
        assertThrows(Exception::class.java) { f.identity.validate(f.issued.now + 3600001) }
        assertThrows(Exception::class.java) {
            PivTlsIdentity(EnrollmentImport(f.csr, EnrollmentCA("https://localhost", f.issued.root), EnrollmentBundle(f.bundle.chain, f.issued.now + 600000)))
        }
    }

    @Test fun realHttpFailureRetainsSuccessfulCardEffectWithoutRetry() {
        for (response in listOf("HTTP/1.1 302 Found\r\nLocation: /again\r\n\r\n",
            "HTTP/1.1 500 Failed\r\n\r\n", "HTTP/1.1 204 No Content\r\n")) {
            val f = TlsFixture()
            TlsServer(f, response = response).use { server ->
                val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root))
                assertTrue(attempt.preflight() is OperationResult.Completed)
                val result = attempt.execute(false)
                assertEquals(PivTlsOutcome.FAILED, result.outcome)
                assertEquals(Effect.APPLIED, (result.card as OperationResult.Completed).effect)
                assertEquals(1, server.requests.get())
                assertEquals(1, server.accepted.get())
                assertEquals(1, f.card.commands.count { it.ins == 0x87 })
            }
        }
    }

    @Test fun approvedLastRetryIsSingleAndPinFailureOrBadSignatureRetainFacts() {
        for (mode in listOf("approved", "wrong-pin", "bad-signature")) {
            val f = TlsFixture()
            if (mode == "approved") { f.card.pinRemaining = 1; f.card.pukRemaining = 0 }
            if (mode == "wrong-pin") f.card.pinStatus = 0x63c2
            if (mode == "bad-signature") f.card.corruptSignature = true
            TlsServer(f).use { server ->
                val attempt = f.attempt(PivTlsEndpoint(server.address, f.issued.root))
                assertTrue(attempt.preflight() is OperationResult.Completed)
                val result = attempt.execute(true)
                assertEquals(1, f.card.commands.count { it.ins == 0x20 })
                if (mode == "approved") assertEquals(PivTlsOutcome.QUALIFIED, result.outcome)
                else {
                    assertEquals(PivTlsOutcome.FAILED, result.outcome)
                    val failure = result.card as OperationResult.Failed
                    assertEquals(Effect.APPLIED, failure.effect)
                    assertEquals(if (mode == "wrong-pin") FailureCode.PIN_INVALID else FailureCode.MALFORMED_RESPONSE, failure.code)
                    assertEquals(if (mode == "wrong-pin") 0 else 1, f.card.commands.count { it.ins == 0x87 })
                }
            }
        }
    }

    @Test fun endpointAndContactFencesRejectBeforeSensitiveApdu() {
        val f = TlsFixture()
        for (address in listOf("http://localhost", "https://127.0.0.1", "https://[::1]", "https://*.example.org",
            "https://localhost.", "https://a..b", "https://localhost?x=1", "https://localhost/#x", "https://user@localhost",
            "https://localhost:0", "https://localhost:65536", "https://例子.org")) {
            assertThrows(IllegalArgumentException::class.java) { PivTlsEndpoint(address, f.issued.root) }
        }
        val nfc = object : CardChannel by f.card { override val medium = Medium.NFC }
        val attempt = f.attempt(PivTlsEndpoint("https://localhost", f.issued.root), connect = { nfc })
        assertEquals(FailureCode.CONTACT_REQUIRED, (attempt.preflight() as OperationResult.Failed).code)
        assertTrue(f.card.commands.isEmpty())
    }

    @Test fun boundedBodyless204Only() {
        QualificationResponse.read("HTTP/1.1 204 No Content\r\n\r\n".byteInputStream())
        for (response in listOf("HTTP/1.1 302 Found\r\nLocation: /next\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n",
            "HTTP/1.1 204 No Content\r\n", "HTTP/1.1 204 No Content\r\nTransfer-Encoding: chunked\r\n\r\n",
            "HTTP/1.1 204 No Content\r\nContent-Length: 1\r\n\r\nx", "HTTP/1.1 204 No Content\r\n\r\nx",
            "HTTP/1.1 204 No Content\r\nX: " + "a".repeat(8192) + "\r\n\r\n")) {
            assertThrows(java.io.IOException::class.java) { QualificationResponse.read(response.byteInputStream()) }
        }
    }
}
