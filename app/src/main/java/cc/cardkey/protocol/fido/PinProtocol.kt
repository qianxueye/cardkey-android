package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import java.io.Closeable
import java.math.BigInteger
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
internal fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
}
internal fun fixed32(value: BigInteger): ByteArray {
    val raw = value.toByteArray()
    return if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
}
internal fun ecParameters(): ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
    init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
}
internal fun coseEcPublic(key: Cbor.Map, expectedAlgorithm: Long): ECPublicKey {
    if (key[1].number() != 2L || key[3].number() != expectedAlgorithm || key[-1].number() != 1L) malformed()
    val x = key[-2].bytes(); val y = key[-3].bytes()
    if (x.size != 32 || y.size != 32) malformed()
    val parameters = ecParameters()
    val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
    val prime = (parameters.curve.field as java.security.spec.ECFieldFp).p
    if (point.affineX >= prime || point.affineY >= prime ||
        point.affineY.modPow(BigInteger.valueOf(2), prime) !=
        (point.affineX.modPow(BigInteger.valueOf(3), prime) + parameters.curve.a * point.affineX + parameters.curve.b).mod(prime)) malformed()
    return try { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters)) as ECPublicKey }
    catch (_: java.security.GeneralSecurityException) { malformed() }
}

internal class PinProtocol(val version: Int, peer: Cbor.Map) : Closeable {
    private val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    val publicKey: Cbor.Map
    private val secret: ByteArray
    private var closed = false

    init {
        require(version in 1..2)
        val key = pair.public as ECPublicKey
        publicKey = cborMap(1 to 2.cbor(), 3 to (-25).cbor(), -1 to 1.cbor(),
            -2 to fixed32(key.w.affineX).cbor(), -3 to fixed32(key.w.affineY).cbor())
        val z = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(coseEcPublic(peer, -25), true); generateSecret() }
        secret = try { derive(version, z) } finally { z.fill(0) }
    }

    fun encrypt(plain: ByteArray): ByteArray { check(!closed); return encrypt(version, secret, plain) }
    fun decrypt(cipher: ByteArray): ByteArray { check(!closed); return decrypt(version, secret, cipher) }
    fun authenticate(message: ByteArray): ByteArray { check(!closed); return authenticate(version, secret, message) }
    override fun close() {
        secret.fill(0); closed = true
        try { pair.private.destroy() } catch (_: javax.security.auth.DestroyFailedException) { }
    }

    companion object {
        internal fun derive(version: Int, z: ByteArray): ByteArray {
            if (version == 1) return sha256(z)
            val prk = hmac(ByteArray(32), z)
            return try { hmac(prk, "CTAP2 HMAC key".toByteArray(Charsets.US_ASCII) + byteArrayOf(1)) +
                hmac(prk, "CTAP2 AES key".toByteArray(Charsets.US_ASCII) + byteArrayOf(1)) }
            finally { prk.fill(0) }
        }
        internal fun authenticate(version: Int, key: ByteArray, message: ByteArray): ByteArray {
            val authKey = if (version == 2 && key.size == 64) key.copyOfRange(0, 32) else key.copyOf()
            return try { val full = hmac(authKey, message); if (version == 1) full.copyOf(16).also { full.fill(0) } else full }
            finally { authKey.fill(0) }
        }
        internal fun encrypt(version: Int, key: ByteArray, plain: ByteArray): ByteArray {
            require(plain.isNotEmpty() && plain.size % 16 == 0)
            val iv = ByteArray(16).also { if (version == 2) SecureRandom().nextBytes(it) }
            val cipher = crypt(Cipher.ENCRYPT_MODE, version, key, iv, plain)
            return if (version == 2) iv + cipher else cipher
        }
        internal fun decrypt(version: Int, key: ByteArray, cipher: ByteArray): ByteArray {
            val offset = if (version == 2) 16 else 0
            if (cipher.size <= offset || cipher.size % 16 != 0) malformed()
            val iv = if (version == 2) cipher.copyOfRange(0, 16) else ByteArray(16)
            return crypt(Cipher.DECRYPT_MODE, version, key, iv, cipher.copyOfRange(offset, cipher.size))
        }
        private fun crypt(mode: Int, version: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
            val aes = if (version == 2) key.copyOfRange(32, 64) else key.copyOf()
            return try { Cipher.getInstance("AES/CBC/NoPadding").run { init(mode, SecretKeySpec(aes, "AES"), IvParameterSpec(iv)); doFinal(data) } }
            finally { aes.fill(0) }
        }
    }
}

internal fun pinBytes(pin: CharArray, minimum: Int): ByteArray {
    require(pin.none { it == '\u0000' } && Character.codePointCount(pin, 0, pin.size) >= minimum)
    val buffer = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(pin)) }
    catch (_: java.nio.charset.CharacterCodingException) { throw CardFailure(FailureCode.INVALID_INPUT) }
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    if (buffer.hasArray()) buffer.array().fill(0)
    if (bytes.size > 63) { bytes.fill(0); throw CardFailure(FailureCode.INVALID_INPUT) }
    return bytes
}

internal class PinToken(val version: Int, private val value: ByteArray) : Closeable {
    private var closed = false
    init { if (value.size != 32 && !(version == 1 && value.size == 16)) { value.fill(0); malformed() } }
    fun authenticate(message: ByteArray): ByteArray { check(!closed); return PinProtocol.authenticate(version, value, message) }
    override fun close() { value.fill(0); closed = true }
}
