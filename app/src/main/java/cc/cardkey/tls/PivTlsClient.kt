package cc.cardkey.tls

import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.Vector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal val PIV_TLS_ALGORITHM: SignatureAndHashAlgorithm =
    SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)

internal class PivTlsPeer(
    private val identity: PivTlsIdentity,
    private val endpoint: PivTlsEndpoint,
    private val sign: (ByteArray) -> ByteArray,
) : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {
    private var authenticatedServer = false
    private var credentialsIssued = false
    private var signed = false
    private var begun = false
    override fun getSupportedVersions() = arrayOf(ProtocolVersion.TLSv13)
    override fun getSupportedCipherSuites() = intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256)
    override fun getSupportedGroups(namedGroupRoles: Vector<*>): Vector<Int> = Vector(listOf(NamedGroup.secp256r1))
    override fun getSNIServerNames(): Vector<ServerName> = Vector(listOf(ServerName(NameType.host_name, endpoint.host.toByteArray(Charsets.US_ASCII))))
    override fun getProtocolNames(): Vector<ProtocolName> = Vector(listOf(ProtocolName.HTTP_1_1))
    override fun getCertificateStatusRequest(): CertificateStatusRequest? = null
    override fun getPskKeyExchangeModes() = shortArrayOf()
    override fun getMaxCertificateChainLength() = 5
    override fun getMaxHandshakeMessageSize() = 131072
    override fun getHandshakeTimeoutMillis() = 30000
    override fun notifyHandshakeBeginning() {
        if (begun) throw TlsFatalAlert(AlertDescription.unexpected_message)
        begun = true
        super.notifyHandshakeBeginning()
    }
    override fun notifySessionToResume(session: TlsSession?) {
        if (session != null) throw TlsFatalAlert(AlertDescription.illegal_parameter)
    }
    override fun notifySelectedPSK(selectedPSK: TlsPSK?) {
        if (selectedPSK != null) throw TlsFatalAlert(AlertDescription.illegal_parameter)
    }
    override fun notifyHandshakeComplete() {
        if (!signed || !authenticatedServer || context.securityParametersConnection.isResumedSession)
            throw TlsFatalAlert(AlertDescription.handshake_failure)
        val protocol = context.securityParametersConnection.applicationProtocol
        if (protocol != null && protocol != ProtocolName.HTTP_1_1) throw TlsFatalAlert(AlertDescription.no_application_protocol)
        super.notifyHandshakeComplete()
    }
    override fun getAuthentication() = object : TlsAuthentication {
        override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
            if (authenticatedServer) throw TlsFatalAlert(AlertDescription.unexpected_message)
            try { endpoint.validateServer(serverCertificate.certificate.certificateList.map { it.encoded }) }
            catch (error: Exception) { throw TlsFatalAlert(AlertDescription.bad_certificate, error) }
            authenticatedServer = true
        }
        override fun getClientCredentials(request: CertificateRequest): TlsCredentials {
            if (credentialsIssued) throw TlsFatalAlert(AlertDescription.unexpected_message)
            credentialsIssued = true
            if (!authenticatedServer || request.certificateRequestContext?.isEmpty() != true ||
                request.supportedSignatureAlgorithms?.contains(PIV_TLS_ALGORITHM) != true)
                throw TlsFatalAlert(AlertDescription.handshake_failure)
            identity.validate()
            return object : TlsCredentialedSigner {
                override fun getCertificate() = Certificate(byteArrayOf(), identity.chain.map {
                    CertificateEntry(crypto.createCertificate(it), null)
                }.toTypedArray())
                override fun getSignatureAndHashAlgorithm() = PIV_TLS_ALGORITHM
                override fun getStreamSigner(): org.bouncycastle.tls.crypto.TlsStreamSigner? = null
                override fun generateRawSignature(hash: ByteArray): ByteArray {
                    if (signed) throw TlsFatalAlert(AlertDescription.internal_error)
                    signed = true
                    if (hash.size != 32) throw TlsFatalAlert(AlertDescription.internal_error)
                    val digest = hash.copyOf()
                    return try { sign(digest) } finally { digest.fill(0) }
                }
            }
        }
    }
}

internal object QualificationResponse {
    fun read(input: InputStream) {
        var total = 0
        fun line(): String {
            val result = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || ++total > 8192) throw IOException("response envelope")
                if (b == 13) {
                    if (input.read() != 10 || ++total > 8192) throw IOException("response line")
                    return result.toString()
                }
                if (b !in 32..126 || result.length >= 2048) throw IOException("response line")
                result.append(b.toChar())
            }
        }
        if (!line().matches(Regex("HTTP/1\\.1 204(?: [ -~]*)?"))) throw IOException("qualification status")
        val seen = mutableSetOf<String>()
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("response header")
            val name = line.substring(0, colon).lowercase(java.util.Locale.ROOT)
            if (!name.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) || !seen.add(name)) throw IOException("response header")
            val value = line.substring(colon + 1).trim()
            if (name in setOf("transfer-encoding", "location") || (name == "content-length" && value != "0"))
                throw IOException("response body")
        }
        // A controlled endpoint closes after its bodyless response. Extra bytes and truncation fail.
        if (input.read() != -1) throw IOException("unexpected response body")
    }
}

internal class PivTlsConnection(private val deadlineMillis: Long = 45000,
                                private val dns: PivTlsDns = AndroidPivTlsDns()) : Closeable {
    private val closed = AtomicBoolean()
    private val used = AtomicBoolean()
    private val socket = AtomicReference<Socket?>()
    fun execute(identity: PivTlsIdentity, endpoint: PivTlsEndpoint, sign: (ByteArray) -> ByteArray,
                onDeadline: () -> Unit) {
        check(used.compareAndSet(false, true))
        if (closed.get()) throw IOException("cancelled")
        val timer = Timer("piv-tls-deadline", true)
        timer.schedule(object : TimerTask() { override fun run() { try { onDeadline() } finally { close() } } }, deadlineMillis)
        try {
            if (closed.get()) throw IOException("cancelled")
            identity.validate()
            val address = dns.resolve(endpoint.host)
            if (closed.get()) throw IOException("cancelled")
            val transport = Socket()
            socket.set(transport)
            if (closed.get()) throw IOException("cancelled")
            transport.connect(InetSocketAddress(address, endpoint.port), 10000)
            transport.soTimeout = 15000
            val protocol = TlsClientProtocol(transport.getInputStream(), transport.getOutputStream())
            protocol.connect(PivTlsPeer(identity, endpoint, sign))
            protocol.outputStream.write(endpoint.request)
            protocol.outputStream.flush()
            QualificationResponse.read(protocol.inputStream)
            if (closed.get()) throw IOException("cancelled")
        } finally { timer.cancel(); close() }
    }
    override fun close() {
        closed.set(true)
        dns.close()
        socket.getAndSet(null)?.let { runCatching { it.close() } }
    }
}
