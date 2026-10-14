package cc.cardkey.tls

import cc.cardkey.enrollment.EnrollmentImport
import cc.cardkey.enrollment.EnrollmentProfile
import cc.cardkey.enrollment.EnrollmentSelection
import java.net.URI
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.util.Date
import java.util.Locale

class PivTlsIdentity(imported: EnrollmentImport) {
    private val csr = imported.csr
    private val root = imported.ca.anchor
    private val bundle = imported.bundle
    val leaf get() = bundle.leaf
    val spki get() = csr.spki
    val chain get() = bundle.chain
    val subject get() = csr.cn
    fun validate(now: Long = System.currentTimeMillis()) {
        require(csr.selection == EnrollmentSelection())
        EnrollmentProfile.validate(bundle.chain, root, csr, now, bundle.verifiedAt)
    }
    init { validate() }
}

class PivTlsEndpoint(address: String, serverRoot: ByteArray) {
    val uri = URI(address)
    val host: String
    val port: Int
    private val root = EnrollmentProfile.root(serverRoot)
    init {
        require(address.length <= 2048 && address.all { it.code in 33..126 })
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
        host = requireNotNull(uri.host).lowercase(Locale.ROOT)
        require(host.length in 1..253 && !host.endsWith('.') && host.any { it in 'a'..'z' })
        require(host.split('.').all { label -> label.length in 1..63 && label.first().isLetterOrDigit() &&
            label.last().isLetterOrDigit() && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } })
        port = if (uri.port == -1) 443 else uri.port.also { require(it in 1..65535) }
        require(uri.rawPath.isEmpty() || uri.rawPath.startsWith('/'))
    }
    val request: ByteArray get() {
        val authority = if (port == 443) host else "$host:$port"
        return "GET ${uri.rawPath.ifEmpty { "/" }} HTTP/1.1\r\nHost: $authority\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
    internal fun validateServer(chain: List<ByteArray>, now: Long = System.currentTimeMillis()) {
        require(chain.size in 1..5 && chain.sumOf { it.size } <= EnrollmentProfile.MAX_BUNDLE)
        val certificates = chain.map(EnrollmentProfile::certificate)
        val leaf = certificates.first()
        require(leaf.basicConstraints == -1 && leaf.keyUsage?.getOrNull(0) == true)
        require(leaf.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") == true)
        require(leaf.subjectAlternativeNames?.any { it[0] == 2 &&
            (it[1] as? String)?.lowercase(Locale.ROOT) == host } == true)
        val anchor = EnrollmentProfile.certificate(root)
        anchor.checkValidity(Date(now))
        certificates.forEach { it.checkValidity(Date(now)) }
        val path = if (certificates.last().encoded.contentEquals(root)) certificates.dropLast(1) else certificates
        require(path.isNotEmpty())
        val parameters = PKIXParameters(setOf(TrustAnchor(anchor, null))).apply {
            isRevocationEnabled = false
            date = Date(now)
        }
        CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(path), parameters)
    }
}
