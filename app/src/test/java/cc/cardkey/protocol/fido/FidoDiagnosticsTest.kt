package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class FidoDiagnosticsTest {
    private val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private val pub = pair.public as ECPublicKey
    private val key = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(pub.w.affineX).cbor(), -3 to fixed32(pub.w.affineY).cbor())
    private val rp = "cardkey.example"
    private val hash = ByteArray(32) { it.toByte() }
    private val id = byteArrayOf(1, 2, 3)
    private fun sign(message: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message); sign() }
    @Test fun packedSelfAttestationVerifiesAndTamperingFails() {
        val auth = sha256(rp.toByteArray()) + byteArrayOf(0x45, 0, 0, 0, 0) + ByteArray(16) + byteArrayOf(0, 3) + id + CborCodec.encode(key)
        val signature = sign(auth + hash)
        val result = MakeCredentialResult("packed", auth, textMap("alg" to (-7).cbor(), "sig" to signature.cbor()), null, null)
        val diagnostic = FidoDiagnostics.verifyRegistration(result, rp, hash)
        assertEquals(true, diagnostic.signatureValid); assertEquals(AttestationKind.SELF, diagnostic.kind)
        assertArrayEquals(id, diagnostic.credentialId)
        assertEquals(false, FidoDiagnostics.verifyRegistration(result, rp, ByteArray(32)).signatureValid)
        assertThrows(CardFailure::class.java) { FidoDiagnostics.verifyRegistration(result, "other.example", hash) }
    }
    @Test fun assertionChecksSignatureRpPresenceUvAndCounter() {
        val auth = sha256(rp.toByteArray()) + byteArrayOf(5, 0, 0, 0, 4)
        val assertion = Assertion(id, auth, sign(auth + hash), null, null, null)
        assertEquals(AssertionDiagnostic(true, true, 4), FidoDiagnostics.verifyAssertion(assertion, key, rp, hash, 3))
        assertEquals(false, FidoDiagnostics.verifyAssertion(assertion, key, rp, hash, 4).counterAdvanced)
        assertFalse(FidoDiagnostics.verifyAssertion(assertion, key, rp, ByteArray(32)).signatureValid)
        val noUv = auth.copyOf().also { it[32] = 1 }
        assertThrows(CardFailure::class.java) { FidoDiagnostics.verifyAssertion(assertion.copy(authData = noUv), key, rp, hash) }
    }
    @Test fun explicitNoPresenceVerificationStillChecksUvRpSignatureAndCounter() {
        val auth = sha256(rp.toByteArray()) + byteArrayOf(4, 0, 0, 0, 4)
        val assertion = Assertion(id, auth, sign(auth + hash), null, null, null)
        assertThrows(CardFailure::class.java) { FidoDiagnostics.verifyAssertion(assertion, key, rp, hash) }
        assertEquals(AssertionDiagnostic(true, true, 4),
            FidoDiagnostics.verifyAssertion(assertion, key, rp, hash, 3, requireUp = false))
        assertFalse(FidoDiagnostics.verifyAssertion(assertion, key, rp, ByteArray(32), requireUp = false).signatureValid)
        assertThrows(CardFailure::class.java) {
            FidoDiagnostics.verifyAssertion(assertion, key, "wrong.example", hash, requireUp = false)
        }
        val noUv = auth.copyOf().also { it[32] = 0 }
        assertThrows(CardFailure::class.java) {
            FidoDiagnostics.verifyAssertion(assertion.copy(authData = noUv), key, rp, hash, requireUp = false)
        }
    }

    @Test fun authDataRejectsTruncatedKeyAndTrailingData() {
        val base = sha256(rp.toByteArray()) + byteArrayOf(5, 0, 0, 0, 4)
        assertThrows(CardFailure::class.java) { AuthenticatorData.parse(base + byteArrayOf(0)) }
        assertThrows(CardFailure::class.java) { AuthenticatorData.parse(base.copyOf().also { it[32] = 0x45 }) }
        val extension = base.copyOf().also { it[32] = 0x85.toByte() } + CborCodec.encode(textMap("credBlob" to byteArrayOf(7).cbor()))
        assertArrayEquals(byteArrayOf(7), AuthenticatorData.parse(extension).extensions!!["credBlob"].bytes())
    }
    @Test fun wrongCurveAndOffCurvePointsAreRejected() {
        val invalid = Cbor.Map(key.value + cborMap(-2 to ByteArray(32).cbor(), -3 to ByteArray(32).cbor()).value)
        assertThrows(CardFailure::class.java) { coseEcPublic(invalid, -7) }
        assertThrows(CardFailure::class.java) { coseEcPublic(key, -25) }
    }
}
