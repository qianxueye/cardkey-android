package cc.cardkey.ssh

import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.util.PublicKeyFactory

class PivSshPublicKeyTest {
    @Test fun freshOperationExportsAllAlgorithmsWithoutCertificateOrPrivateApdu() {
        for (algorithm in KeyAlgorithm.entries) {
            val card = EnrollmentCard(algorithm = algorithm)
            val result = CardSession(card).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_SSH_EXPORT, slot = 0x9c)) }
            val report = (result as OperationResult.Completed).value
            assertEquals(Effect.NONE, result.effect)
            assertTrue(report.sshPublicKey)
            assertEquals("piv-9c-ssh.pub", report.fileName)
            assertTrue(card.commands.all { it.ins in setOf(0xa4, 0xf7, 0xc0) })
            assertTrue(card.commands.any { it.ins == 0xf7 && it.p2 == 0x9c })
            assertArrayEquals(card.keyPair.public.encoded, reconstruct(requireNotNull(report.export)).encoded)
        }
    }

    @Test fun malformedAbsentUnsupportedAndCancelledReadNeverExports() {
        val card = EnrollmentCard()
        val cases = listOf(
            PivTlv.encode(2, byteArrayOf(2, 1)) + PivTlv.encode(3, byteArrayOf(0)),
            card.metadata().let { PivTlv.parse(it).entries.fold(byteArrayOf()) { bytes, (tag, value) ->
                bytes + PivTlv.encode(tag, if (tag == 1) byteArrayOf(0x99.toByte()) else value) } },
            card.metadata().let { PivTlv.parse(it).entries.fold(byteArrayOf()) { bytes, (tag, value) ->
                bytes + PivTlv.encode(tag, if (tag == 4) PivTlv.encode(0x86, ByteArray(65)) else value) } })
        for (metadata in cases) {
            val fixture = EnrollmentCard().apply { slotMetadata = metadata }
            assertTrue(CardSession(fixture).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_SSH_EXPORT)) } is OperationResult.Failed)
            assertTrue(fixture.commands.all { it.ins in setOf(0xa4, 0xf7, 0xc0) })
        }
        assertTrue(CardSession(EnrollmentCard()).run { ManagerOperations { true }.execute(it, OperationInput(ManagerOperation.PIV_SSH_EXPORT)) } is OperationResult.Failed)
    }

    @Test fun exactSpkiAndMetadataAgreementRequired() {
        for (algorithm in KeyAlgorithm.entries) {
            val key = EnrollmentCard(algorithm = algorithm).keyPair.public
            for (wrong in KeyAlgorithm.entries.filter { it != algorithm }) reject(SlotMetadata(wrong, 2, 1, true, key))
            val trailing = object : PublicKey {
                override fun getAlgorithm() = key.algorithm
                override fun getFormat() = key.format
                override fun getEncoded() = key.encoded + byteArrayOf(0)
            }
            reject(SlotMetadata(algorithm, 2, 1, true, trailing))
        }
        reject(SlotMetadata(null, 2, 1, true, null))
    }

    @Test fun sshKeygenInteroperabilityExplicitGate() {
        val executable = System.getenv("CARDKEY_SSH_KEYGEN")
        org.junit.Assume.assumeTrue("Set CARDKEY_SSH_KEYGEN to require the external OpenSSH gate", !executable.isNullOrBlank())
        for (algorithm in KeyAlgorithm.entries) {
            val card = EnrollmentCard(algorithm = algorithm)
            val report = (CardSession(card).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.PIV_SSH_EXPORT)) } as OperationResult.Completed).value
            val directory = Files.createTempDirectory("cardkey-ssh-")
            try {
                val file = directory.resolve(requireNotNull(report.fileName))
                Files.write(file, requireNotNull(report.export))
                val process = ProcessBuilder(requireNotNull(executable), "-e", "-m", "PKCS8", "-f", file.toString()).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.waitFor())
                val spki = Base64.getMimeDecoder().decode(output.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""))
                val expected = card.keyPair.public.encoded
                if (algorithm == KeyAlgorithm.RSA2048) assertArrayEquals(expected, spki)
                else {
                    val imported = PublicKeyFactory.createKey(spki) as ECPublicKeyParameters
                    val original = PublicKeyFactory.createKey(expected) as ECPublicKeyParameters
                    assertEquals(original.parameters.curve, imported.parameters.curve)
                    assertEquals(original.parameters.g, imported.parameters.g)
                    assertEquals(original.parameters.n, imported.parameters.n)
                    assertEquals(original.parameters.h, imported.parameters.h)
                    assertEquals(original.q, imported.q)
                    val canonical = SubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(expected).algorithm,
                        SubjectPublicKeyInfo.getInstance(spki).publicKeyData.bytes).encoded
                    assertArrayEquals(expected, canonical)
                }
            } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
        }
    }

    private fun reject(metadata: SlotMetadata) {
        try { PivSshPublicKey.export(metadata); fail("accepted malformed key") } catch (failure: CardFailure) { assertEquals(FailureCode.MALFORMED_RESPONSE, failure.code) }
    }

    private fun reconstruct(line: ByteArray): PublicKey {
        val text = line.toString(Charsets.US_ASCII)
        assertTrue(text.endsWith("\n") && text.count { it == '\n' } == 1)
        val parts = text.trimEnd().split(' ')
        assertEquals(2, parts.size)
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(parts[1])))
        fun field() = ByteArray(input.readInt()).also(input::readFully)
        assertEquals(parts[0], field().toString(Charsets.US_ASCII))
        val key = if (parts[0] == "ssh-rsa") {
            val exponent = field(); val modulus = field()
            assertEquals(257, modulus.size); assertEquals(0, modulus[0].toInt()); assertTrue(modulus[1].toInt() < 0)
            assertArrayEquals(BigInteger(exponent).toByteArray(), exponent)
            assertArrayEquals(BigInteger(modulus).toByteArray(), modulus)
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(modulus), BigInteger(exponent)))
        } else {
            val curve = field().toString(Charsets.US_ASCII)
            val point = field(); val size = (point.size - 1) / 2
            val params = java.security.AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(if (curve == "nistp256") "secp256r1" else "secp384r1")) }
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, point.copyOfRange(1, 1 + size)), BigInteger(1, point.copyOfRange(1 + size, point.size))), params.getParameterSpec(ECParameterSpec::class.java)))
        }
        assertEquals(0, input.available())
        return key
    }
}
