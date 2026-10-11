package cc.cardkey.protocol.fido

import cc.cardkey.protocol.Apdu
import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.CardSession
import cc.cardkey.protocol.FailureCode
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class U2fRegistration(val keyHandle: ByteArray, val publicKey: Cbor.Map, val certificate: ByteArray,
    val signature: ByteArray, val signatureValid: Boolean)
data class U2fAuthentication(val presence: Int, val counter: Long, val signature: ByteArray,
    val signatureValid: Boolean, val counterAdvanced: Boolean?)

class U2fClient(private val session: CardSession) {
    private var selected = false
    fun select() { FidoClient(session).select(); selected = true }
    fun version(): String {
        check(selected)
        val data = session.exchange(Apdu(ins = 3)).requireSuccess()
        if (!data.contentEquals("U2F_V2".toByteArray(Charsets.US_ASCII))) malformed()
        return "U2F_V2"
    }
    private fun exchange(ins: Int, p1: Int, data: ByteArray): ByteArray {
        check(selected)
        val response = session.exchange(Apdu(ins = ins, p1 = p1, data = data), effectful = true)
        if (response.status == 0x6985) throw CardFailure(FailureCode.PHYSICAL_ACTION_REQUIRED, response.status)
        val bytes = response.requireSuccess()
        session.applied()
        return bytes
    }
    fun register(challenge: ByteArray, application: ByteArray): U2fRegistration {
        require(challenge.size == 32 && application.size == 32)
        val response = exchange(1, 0, challenge + application)
        if (response.size < 68 || response[0] != 5.toByte() || response[1] != 4.toByte()) malformed()
        val key = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
            -2 to response.copyOfRange(2, 34).cbor(), -3 to response.copyOfRange(34, 66).cbor())
        coseEcPublic(key, -7)
        val handleLength = response[66].toInt() and 255
        if (handleLength == 0 || response.size <= 67 + handleLength) malformed()
        val handle = response.copyOfRange(67, 67 + handleLength)
        val certificateStart = 67 + handleLength
        val input = ByteArrayInputStream(response, certificateStart, response.size - certificateStart)
        val certificate = try { CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate }
        catch (_: java.security.cert.CertificateException) { malformed() }
        val certificateEnd = response.size - input.available()
        if (certificateEnd >= response.size) malformed()
        val signature = response.copyOfRange(certificateEnd, response.size)
        val signed = byteArrayOf(0) + application + challenge + handle + response.copyOfRange(1, 66)
        return U2fRegistration(handle, key, response.copyOfRange(certificateStart, certificateEnd), signature,
            FidoDiagnostics.verify(certificate.publicKey, -7, signed, signature))
    }
    fun authenticate(challenge: ByteArray, application: ByteArray, keyHandle: ByteArray,
        publicKey: Cbor.Map, previousCounter: Long? = null): U2fAuthentication {
        require(challenge.size == 32 && application.size == 32 && keyHandle.size in 1..255)
        require(previousCounter == null || previousCounter in 0..0xffffffffL)
        val key = coseEcPublic(publicKey, -7)
        val response = exchange(2, 3, challenge + application + byteArrayOf(keyHandle.size.toByte()) + keyHandle)
        if (response.size < 6 || response[0] != 1.toByte()) malformed()
        var counter = 0L
        for (i in 1..4) counter = (counter shl 8) or (response[i].toLong() and 255)
        val signed = application + response.copyOfRange(0, 5) + challenge
        val signature = response.copyOfRange(5, response.size)
        return U2fAuthentication(1, counter, signature, FidoDiagnostics.verify(key, -7, signed, signature),
            previousCounter?.let { if (it == 0L && counter == 0L) null else counter > it })
    }
}
