package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test

class U2fClientTest {
    private val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private val pub = pair.public as ECPublicKey
    private val key = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(pub.w.affineX).cbor(), -3 to fixed32(pub.w.affineY).cbor())
    private fun sign(message: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message); sign() }
    private class Channel(val response: (ByteArray) -> ByteArray): CardChannel {
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 261
        var count = 0; var closed = false
        override fun transmit(command: ByteArray): ByteArray {
            count++
            return if (command[1].toInt() and 255 == 0xa4) "U2F_V2".toByteArray() + byteArrayOf(0x90.toByte(), 0)
            else response(command)
        }
        override fun close() { closed = true }
    }
    @Test fun registrationVerifiesSyntheticCertificateSignature() {
        val challenge = ByteArray(32) { 2 }; val application = ByteArray(32) { 3 }; val handle = byteArrayOf(4, 5)
        val point = byteArrayOf(4) + fixed32(pub.w.affineX) + fixed32(pub.w.affineY)
        val name = X500Name("CN=Synthetic test")
        val cert = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)).encoded
        val signature = sign(byteArrayOf(0) + application + challenge + handle + point)
        val channel = Channel { command ->
            assertEquals(1, command[1].toInt()); assertEquals(0, command[2].toInt())
            assertArrayEquals(challenge + application, command.copyOfRange(5, command.size - 1))
            byteArrayOf(5) + point + byteArrayOf(2) + handle + cert + signature + byteArrayOf(0x90.toByte(), 0)
        }
        val result = CardSession(channel).run { session -> U2fClient(session).apply { select() }.register(challenge, application) }
        assertTrue((result as OperationResult.Completed).value.signatureValid)
        assertArrayEquals(handle, result.value.keyHandle); assertEquals(Effect.APPLIED, result.effect)
    }
    @Test fun authenticateBindsApplicationChallengePresenceAndCounter() {
        val challenge = ByteArray(32) { 1 }; val application = ByteArray(32) { 2 }; val handle = byteArrayOf(3)
        val presenceCounter = byteArrayOf(1, 0, 0, 0, 9)
        val signature = sign(application + presenceCounter + challenge)
        val channel = Channel { command ->
            assertEquals(2, command[1].toInt()); assertEquals(3, command[2].toInt())
            presenceCounter + signature + byteArrayOf(0x90.toByte(), 0)
        }
        val result = CardSession(channel).run { session -> U2fClient(session).apply { select() }.authenticate(challenge, application, handle, key, 8) }
        val data = (result as OperationResult.Completed).value
        assertTrue(data.signatureValid); assertEquals(true, data.counterAdvanced); assertEquals(9L, data.counter)
    }
    @Test fun presenceRequiredClosesWithoutAutomaticRetry() {
        val channel = Channel { byteArrayOf(0x69, 0x85.toByte()) }
        val result = CardSession(channel).run { session -> U2fClient(session).apply { select() }.register(ByteArray(32), ByteArray(32)) }
        assertEquals(OperationResult.Failed(FailureCode.PHYSICAL_ACTION_REQUIRED, Effect.NONE, 0x6985), result)
        assertEquals(2, channel.count); assertTrue(channel.closed)
    }
}
