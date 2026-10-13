package cc.cardkey.enrollment

import cc.cardkey.protocol.piv.*

import cc.cardkey.protocol.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.Assert.*
import org.junit.Test

internal class EnrollmentCard(override val medium: Medium = Medium.CONTACT,
                           val algorithm: KeyAlgorithm = KeyAlgorithm.P256) : CardChannel {
        override val maxCommandBytes = 261
        val commands = mutableListOf<Apdu>()
        val rawCommands = mutableListOf<ByteArray>()
        var closed = false
        var pinRemaining = 3
        var pukRemaining = 3
        var corruptSignature = false
        var pinStatus = 0x9000
        var lostWrite = false
        var corruptReadback = false
        var certificate: ByteArray? = null
        var slotMetadata: ByteArray? = null
        var managementAlgorithm = ManagementAlgorithm.TDES
        var managementKey = ByteArray(24) { (it % 8 + 1).toByte() }
        private val witness = ByteArray(16) { (it + 1).toByte() }
        private val fragments = ByteArrayOutputStream()
        val keyPair: KeyPair = KeyPairGenerator.getInstance(if (algorithm == KeyAlgorithm.RSA2048) "RSA" else "EC").apply {
            if (this@EnrollmentCard.algorithm == KeyAlgorithm.RSA2048) initialize(2048)
            else initialize(ECGenParameterSpec(this@EnrollmentCard.algorithm.curve))
        }.generateKeyPair()
        override fun close() { closed = true }
        override fun transmit(command: ByteArray): ByteArray {
            rawCommands += command.copyOf()
            val length = if (command.size > 5) command[4].toInt() and 255 else 0
            val data = if (length == 0) byteArrayOf() else command.copyOfRange(5, 5 + length)
            fragments.write(data)
            if (command[0].toInt() and 0x10 != 0) return "9000".hexBytes()
            val apdu = Apdu(ins = command[1].toInt() and 255, p1 = command[2].toInt() and 255,
                p2 = command[3].toInt() and 255, data = fragments.toByteArray())
            fragments.reset()
            commands += apdu
            val result = when (apdu.ins) {
                0xa4 -> { assertArrayEquals("a000000308000010000100".hexBytes(), data); byteArrayOf() }
                0xfd -> byteArrayOf(1, 2, 3)
                0xf8 -> byteArrayOf(0, 0, 0, 7)
                0xf7 -> when (apdu.p2) {
                    0x80, 0x81 -> PivTlv.encode(5, byteArrayOf(1)) + PivTlv.encode(6, byteArrayOf(3,
                        (if (apdu.p2 == 0x80) pinRemaining else pukRemaining).toByte()))
                    0x9b -> PivTlv.encode(1, byteArrayOf(managementAlgorithm.code.toByte())) + PivTlv.encode(5, byteArrayOf(0))
                    else -> slotMetadata ?: metadata()
                }
                0xcb -> certificate?.let { PivTlv.encode(0x53, PivTlv.encode(0x70, it) + PivTlv.encode(0x71, byteArrayOf(0))) }
                    ?: return "6a82".hexBytes()
                0x20 -> { assertEquals(8, data.size); if (pinStatus != 0x9000) return byteArrayOf((pinStatus ushr 8).toByte(), pinStatus.toByte()); byteArrayOf() }
                0x24, 0x2c -> { assertEquals(16, data.size); byteArrayOf() }
                0x47 -> PivTlv.encode(0x7f49, encodedKey())
                0xdb -> {
                    val fields = PivTlv.parse(apdu.data)
                    certificate = PivTlv.parse(fields.getValue(0x53)).getValue(0x70)
                    if (lostWrite) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    if (corruptReadback) certificate = byteArrayOf(1)
                    byteArrayOf()
                }
                0xff -> {
                    managementAlgorithm = ManagementAlgorithm.entries.single { it.code == data[0].toInt() and 255 }
                    managementKey = data.copyOfRange(3, data.size)
                    byteArrayOf()
                }
                0xfb -> { pinRemaining = 3; pukRemaining = 3; byteArrayOf() }
                0x87 -> {
                    val fields = PivTlv.parse(PivTlv.one(apdu.data, 0x7c))
                    if (apdu.p2 == 0x9b) {
                        val cipher = Cipher.getInstance("${managementAlgorithm.cipher}/ECB/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(managementKey, managementAlgorithm.cipher))
                        if (fields[0x80]?.isEmpty() == true) PivTlv.encode(0x7c,
                            PivTlv.encode(0x80, cipher.doFinal(witness.copyOf(managementAlgorithm.blockSize))))
                        else {
                            assertArrayEquals(witness.copyOf(managementAlgorithm.blockSize), fields[0x80])
                            PivTlv.encode(0x7c, PivTlv.encode(0x82, cipher.doFinal(fields.getValue(0x81))))
                        }
                    } else {
                        val input = fields[0x81]
                        val output = when {
                            algorithm == KeyAlgorithm.RSA2048 -> Cipher.getInstance("RSA/ECB/NoPadding").run {
                                init(Cipher.DECRYPT_MODE, keyPair.private); doFinal(input)
                            }
                            fields.containsKey(0x85) -> {
                                val point = fields.getValue(0x85)
                                val ec = keyPair.public as ECPublicKey
                                val size = (point.size - 1) / 2
                                val publicKey = KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(
                                    java.security.spec.ECPoint(BigInteger(1, point.copyOfRange(1, size + 1)),
                                        BigInteger(1, point.copyOfRange(size + 1, point.size))), ec.params))
                                KeyAgreement.getInstance("ECDH").run { init(keyPair.private); doPhase(publicKey, true); generateSecret() }
                            }
                            else -> Signature.getInstance("NONEwithECDSA").run { initSign(keyPair.private); update(input); sign() }
                        }
                        if (corruptSignature) output[output.lastIndex] = (output.last().toInt() xor 1).toByte()
                        PivTlv.encode(0x7c, PivTlv.encode(0x82, output))
                    }
                }
                else -> error("Unexpected INS ${apdu.ins}")
            }
            return result + "9000".hexBytes()
        }
        fun metadata() = PivTlv.encode(1, byteArrayOf(algorithm.code.toByte())) +
            PivTlv.encode(2, byteArrayOf(2, 1)) + PivTlv.encode(3, byteArrayOf(0)) + PivTlv.encode(4, encodedKey())
        private fun encodedKey(): ByteArray {
            val key = keyPair.public
            if (key is RSAPublicKey) return PivTlv.encode(0x81, unsigned(key.modulus, 256)) + PivTlv.encode(0x82, key.publicExponent.toByteArray())
            key as ECPublicKey
            val size = if (algorithm == KeyAlgorithm.P256) 32 else 48
            return PivTlv.encode(0x86, byteArrayOf(4) + unsigned(key.w.affineX, size) + unsigned(key.w.affineY, size))
        }
        private fun unsigned(value: BigInteger, size: Int) = value.toByteArray().let {
            if (it.size > size) it.copyOfRange(it.size - size, it.size) else ByteArray(size - it.size) + it
        }
    }
