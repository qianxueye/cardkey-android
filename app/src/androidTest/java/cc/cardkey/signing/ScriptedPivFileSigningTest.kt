package cc.cardkey.signing

import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.ui.*
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.StandardDSAEncoding
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher

@RunWith(AndroidJUnit4::class)
class ScriptedPivFileSigningTest {
    @Test fun rawFileSignaturesVerifyWithAndroidProvidersForAllThreeAlgorithms() {
        for (algorithm in KeyAlgorithm.entries) {
            val card = SyntheticPivCard(algorithm)
            val source = "-----BEGIN CERTIFICATE-----\nraw synthetic file bytes\n-----END CERTIFICATE-----\n".toByteArray()
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = source.copyOf())
            val result = try { CardSession(card).run { ManagerOperations().execute(it, input) } } finally { input.close() }
            val report = (result as OperationResult.Completed).value
            val signature = requireNotNull(report.export)
            assertEquals("signature.bin", report.fileName)
            assertTrue(Signature.getInstance(algorithm.signature).run {
                initVerify(card.keyPair.public); update(source); verify(signature)
            })
            if (algorithm == KeyAlgorithm.RSA2048) assertEquals(256, signature.size)
            else {
                val key = PrivateKeyFactory.createKey(card.keyPair.private.encoded) as ECPrivateKeyParameters
                assertEquals(2, StandardDSAEncoding.INSTANCE.decode(key.parameters.n, signature).size)
            }
            assertEquals(1, card.verifies)
            assertEquals(1, card.signatures)
            assertTrue(card.closed)
            assertTrue(input.pin.all { it == '\u0000' })
            assertTrue(input.bytes.all { it == 0.toByte() })
        }
    }

    private class SyntheticPivCard(private val algorithm: KeyAlgorithm) : CardChannel {
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 261
        val keyPair = KeyPairGenerator.getInstance(if (algorithm == KeyAlgorithm.RSA2048) "RSA" else "EC").apply {
            if (this@SyntheticPivCard.algorithm == KeyAlgorithm.RSA2048) initialize(2048)
            else initialize(ECGenParameterSpec(this@SyntheticPivCard.algorithm.curve))
        }.generateKeyPair()
        private val fragments = ByteArrayOutputStream()
        var verifies = 0
        var signatures = 0
        var closed = false
        override fun close() { closed = true; fragments.reset() }
        override fun transmit(command: ByteArray): ByteArray {
            val size = if (command.size > 5) command[4].toInt() and 255 else 0
            if (size > 0) fragments.write(command, 5, size)
            if (command[0].toInt() and 0x10 != 0) return byteArrayOf(0x90.toByte(), 0)
            val data = fragments.toByteArray()
            fragments.reset()
            val response = when (command[1].toInt() and 255) {
                0xa4 -> byteArrayOf()
                0xf7 -> PivTlv.encode(1, byteArrayOf(algorithm.code.toByte())) +
                    PivTlv.encode(2, byteArrayOf(2, 1)) + PivTlv.encode(3, byteArrayOf(0)) + PivTlv.encode(4, publicKey())
                0x20 -> { verifies++; assertEquals(8, data.size); data.fill(0); byteArrayOf() }
                0x87 -> {
                    signatures++
                    val input = PivTlv.parse(PivTlv.one(data, 0x7c)).getValue(0x81)
                    val signature = if (algorithm == KeyAlgorithm.RSA2048) Cipher.getInstance("RSA/ECB/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, keyPair.private); doFinal(input)
                    } else {
                        assertEquals(if (algorithm == KeyAlgorithm.P256) 32 else 48, input.size)
                        val key = PrivateKeyFactory.createKey(keyPair.private.encoded) as ECPrivateKeyParameters
                        val values = ECDSASigner().apply { init(true, key) }.generateSignature(input)
                        StandardDSAEncoding.INSTANCE.encode(key.parameters.n, values[0], values[1])
                    }
                    PivTlv.encode(0x7c, PivTlv.encode(0x82, signature))
                }
                else -> error("unexpected synthetic APDU")
            }
            return response + byteArrayOf(0x90.toByte(), 0)
        }
        private fun publicKey(): ByteArray {
            val key = keyPair.public
            if (key is RSAPublicKey) return PivTlv.encode(0x81, unsigned(key.modulus, 256)) +
                PivTlv.encode(0x82, key.publicExponent.toByteArray())
            key as ECPublicKey
            val size = if (algorithm == KeyAlgorithm.P256) 32 else 48
            return PivTlv.encode(0x86, byteArrayOf(4) + unsigned(key.w.affineX, size) + unsigned(key.w.affineY, size))
        }
        private fun unsigned(value: BigInteger, size: Int): ByteArray = value.toByteArray().let {
            if (it.size > size) it.copyOfRange(it.size - size, it.size) else ByteArray(size - it.size) + it
        }
    }
}
