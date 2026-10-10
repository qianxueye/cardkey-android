package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object LargeBlobs {
    const val MAX_PLAINTEXT = 1024 * 1024
    fun encode(entries: List<Cbor.Map>): ByteArray {
        entries.forEach(::validate)
        val bytes = CborCodec.encode(Cbor.Array(entries))
        require(bytes.size <= CborCodec.MAX_BYTES - 16)
        return bytes + sha256(bytes).copyOf(16)
    }
    fun decode(bytes: ByteArray): List<Cbor.Map> {
        if (bytes.size !in 17..CborCodec.MAX_BYTES) malformed()
        val payload = bytes.copyOfRange(0, bytes.size - 16)
        if (!MessageDigest.isEqual(sha256(payload).copyOf(16), bytes.copyOfRange(bytes.size - 16, bytes.size))) malformed()
        return CborCodec.decode(payload).array().map { it.map().also(::validate) }
    }
    private fun validate(map: Cbor.Map) {
        if (map[1].bytes().size < 16 || map[2].bytes().size != 12 || map[3].number() < 0) malformed()
    }
    fun encrypt(key: ByteArray, plaintext: ByteArray): Cbor.Map {
        require(key.size == 32 && plaintext.size <= MAX_PLAINTEXT)
        val compressor = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val buffer = ByteArray(CborCodec.MAX_BYTES - 64)
        val compressed = try {
            compressor.setInput(plaintext); compressor.finish()
            var offset = 0
            while (!compressor.finished()) {
                if (offset == buffer.size) throw CardFailure(FailureCode.CAPACITY_EXCEEDED)
                val count = compressor.deflate(buffer, offset, buffer.size - offset)
                if (count == 0 && !compressor.finished()) throw CardFailure(FailureCode.CAPACITY_EXCEEDED)
                offset += count
            }
            buffer.copyOf(offset)
        } finally { compressor.end(); buffer.fill(0) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = try { cipher(Cipher.ENCRYPT_MODE, key, nonce, plaintext.size.toLong()).doFinal(compressed) }
        finally { compressed.fill(0) }
        return cborMap(1 to ciphertext.cbor(), 2 to nonce.cbor(), 3 to plaintext.size.cbor())
    }
    fun decrypt(key: ByteArray, map: Cbor.Map): ByteArray? {
        require(key.size == 32)
        validate(map)
        val originalSize = map[3].number()
        val compressed = try { cipher(Cipher.DECRYPT_MODE, key, map[2].bytes(), originalSize).doFinal(map[1].bytes()) }
        catch (_: AEADBadTagException) { return null }
        if (originalSize > MAX_PLAINTEXT) { compressed.fill(0); throw CardFailure(FailureCode.CAPACITY_EXCEEDED) }
        val inflater = Inflater(true); val result = ByteArray(originalSize.toInt()); val sentinel = ByteArray(1)
        var success = false
        return try {
            inflater.setInput(compressed)
            var offset = 0
            while (!inflater.finished()) {
                val count = if (offset < result.size) inflater.inflate(result, offset, result.size - offset) else inflater.inflate(sentinel)
                if (count == 0 && !inflater.finished()) malformed()
                if (offset + count > result.size) malformed()
                offset += count
            }
            if (offset != result.size || inflater.remaining != 0) malformed()
            success = true; result
        } catch (_: java.util.zip.DataFormatException) { malformed() }
        finally { inflater.end(); compressed.fill(0); sentinel.fill(0); if (!success) result.fill(0) }
    }
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, size: Long): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD("blob".toByteArray(Charsets.US_ASCII) + ByteArray(8) { (size ushr (it * 8)).toByte() })
    }
}
