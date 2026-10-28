package cc.cardkey.protocol.piv

import cc.cardkey.protocol.*
import java.io.Closeable
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter

/** Identity and issuer SPKI explicitly enrolled through a trusted issuance path. */
class EnrolledPivCard(issuerSpki: ByteArray, issuerId: ByteArray, guid: ByteArray, cardPoint: ByteArray) {
    internal val issuer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(issuerSpki)) as ECPublicKey
    internal val issuerId = issuerId.copyOf()
    internal val guid = guid.copyOf()
    internal val cardPoint = cardPoint.copyOf()
    init {
        require(issuerId.size == 8 && guid.size == 16)
        require(issuer.params.order == Cs7.curve.order && issuer.params.curve == Cs7.curve.curve &&
            issuer.params.generator == Cs7.curve.generator && issuer.params.cofactor == 1)
        Cs7.publicKey(cardPoint)
    }
}

/** One paired session; plain APDUs, selection, close or any protocol failure invalidate it. */
class PivSecureContext private constructor(private val session: CardSession, private val cipher: Cs7Cipher) : Closeable {
    private val medium = session.medium
    private var paired = false
    private var closed = false

    internal fun requireCurrent(expectedSession: CardSession) {
        if (expectedSession !== session || closed || !paired || !session.ownsSecure(this) || session.medium != medium) {
            close(); throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        }
    }

    internal fun exchange(apdu: Apdu, effectful: Boolean = false): ApduResponse = synchronized(session) {
        requireCurrent(session)
        val permitted = apdu.cla == 0 && when (apdu.ins) {
            0x20 -> apdu.p1 == 0 && apdu.p2 == 0x80
            0x87 -> apdu.p1 in listOf(0x07, 0x11, 0x14) && apdu.p2 in PivClient.slots
            0xcb -> apdu.p1 == 0x3f && apdu.p2 == 0xff
            0xf7, 0xfd, 0xf8 -> true
            else -> false
        }
        if (!permitted) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        protectedExchange(apdu, effectful)
    }

    private fun protectedExchange(apdu: Apdu, effectful: Boolean): ApduResponse {
        var sent = false
        try {
            val protected = cipher.protect(apdu)
            val outer = session.exchangeSecure(this, protected, effectful) { sent = true }
            val response = cipher.unprotect(outer)
            if (effectful && response.status and 0xfff0 == 0x63c0) session.securityStateChanged()
            return response
        } catch (e: Exception) {
            if (effectful && sent && !(e is TransportException && e.dispatch == Dispatch.NOT_DISPATCHED))
                session.securityStateUncertain()
            close()
            throw e
        }
    }

    override fun close() = synchronized(session) { closed = true; paired = false; cipher.close() }

    companion object {
        fun establish(session: CardSession, identity: EnrolledPivCard, pairing: CharArray): PivSecureContext =
            establish(session, identity, pairing, SecureRandom())

        internal fun establish(session: CardSession, identity: EnrolledPivCard, pairing: CharArray,
                               random: SecureRandom, host: KeyPair? = null, hostId: ByteArray? = null): PivSecureContext = synchronized(session) {
            session.invalidateSecure()
            var privateKey: PrivateKey? = null
            var z = byteArrayOf()
            var keys = byteArrayOf()
            var code = byteArrayOf()
            var context: PivSecureContext? = null
            try {
                require(pairing.size == 8 && pairing.all { it in '0'..'9' })
                session.exchange(Apdu(ins = 0xa4, p1 = 4, data = "a000000308000010000100".hexBytes())).requireSuccess()
                val ephemeral = host ?: KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp384r1"), random)
                }.generateKeyPair()
                privateKey = ephemeral.private
                val point = Cs7.point(ephemeral.public as ECPublicKey)
                val idsH = hostId?.copyOf() ?: ByteArray(8).also(random::nextBytes)
                require(idsH.size == 8)
                val request = PivTlv.encode(0x7c, PivTlv.encode(0x81, byteArrayOf(0) + idsH + point) +
                    PivTlv.encode(0x82, byteArrayOf()))
                val result = PivTlv.one(PivTlv.one(session.exchange(Apdu(ins = 0x87, p1 = 0x2e, p2 = 4,
                    data = request)).requireSuccess(), 0x7c), 0x82)
                if (result.size <= 41 || result[0] != 0.toByte()) malformed()
                val nonce = result.copyOfRange(1, 25)
                val confirmation = result.copyOfRange(25, 41)
                val cicc = result.copyOfRange(41, result.size)
                val cardKey = Cs7.verifyCicc(cicc, identity)
                val idsIcc = MessageDigest.getInstance("SHA-256").digest(cicc).copyOf(8)
                z = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(cardKey, true); generateSecret() }
                keys = Cs7.derive(z, idsH, idsIcc, nonce, point)
                val cfrm = keys.copyOfRange(0, 32)
                val actual = try { Cs7.mac(cfrm, "KC_1_V".toByteArray(Charsets.US_ASCII) + idsIcc + idsH + point.copyOfRange(1, 97)) }
                    finally { cfrm.fill(0) }
                if (!MessageDigest.isEqual(actual, confirmation)) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
                context = PivSecureContext(session, Cs7Cipher(keys))
                session.bindSecure(context)
                code = ByteArray(8) { pairing[it].code.toByte() }
                val paired = context.protectedExchange(Apdu(ins = 0x20, p2 = 0x98, data = code, le = null), false)
                if (paired.data.isNotEmpty()) malformed()
                if (paired.status != 0x9000) throw CardFailure(FailureCode.AUTHORIZATION_DENIED, paired.status)
                context.paired = true
                context
            } catch (e: Exception) {
                context?.close()
                session.invalidateSecure()
                if (session.medium == Medium.UNKNOWN && e is CardFailure && e.status == 0x6983)
                    throw CardFailure(FailureCode.CARD_REJECTED, e.status)
                throw e
            } finally {
                pairing.fill('\u0000'); code.fill(0); keys.fill(0); z.fill(0)
                try { privateKey?.destroy() } catch (_: javax.security.auth.DestroyFailedException) { }
            }
        }
    }
}

internal object Cs7 {
    val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp384r1"))
    }.getParameterSpec(ECParameterSpec::class.java)

    fun point(key: ECPublicKey): ByteArray {
        fun coordinate(n: BigInteger) = n.toByteArray().let { if (it.size > 48) it.copyOfRange(it.size - 48, it.size) else ByteArray(48 - it.size) + it }
        return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    }

    fun publicKey(point: ByteArray): ECPublicKey {
        if (point.size != 97 || point[0] != 4.toByte()) malformed()
        val x = BigInteger(1, point.copyOfRange(1, 49))
        val y = BigInteger(1, point.copyOfRange(49, 97))
        val p = (curve.curve.field as ECFieldFp).p
        if (x >= p || y >= p || y.modPow(BigInteger.TWO, p) !=
            x.modPow(BigInteger.valueOf(3), p).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)) malformed()
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    fun verifyCicc(encoded: ByteArray, identity: EnrolledPivCard): ECPublicKey {
        val fields = PivTlv.parse(PivTlv.one(encoded, 0x7f21))
        if (fields.keys.toList() != listOf(0x5f29, 0x42, 0x5f20, 0x7f49, 0x5f4c, 0x5f37)) malformed()
        if (!fields.getValue(0x5f29).contentEquals(byteArrayOf(0x80.toByte())) ||
            !fields.getValue(0x5f4c).contentEquals(byteArrayOf(0))) malformed()
        if (!MessageDigest.isEqual(fields.getValue(0x42), identity.issuerId) ||
            !MessageDigest.isEqual(fields.getValue(0x5f20), identity.guid)) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        val key = PivTlv.parse(fields.getValue(0x7f49))
        if (key.keys.toList() != listOf(6, 0x86) || !key.getValue(6).contentEquals("2b81040022".hexBytes())) malformed()
        if (!MessageDigest.isEqual(key.getValue(0x86), identity.cardPoint)) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        val signature = PivTlv.parse(PivTlv.one(fields.getValue(0x5f37), 0x30))
        if (signature.keys.toList() != listOf(0x30, 3) ||
            !signature.getValue(0x30).contentEquals("06082a8648ce3d040303".hexBytes())) malformed()
        val bits = signature.getValue(3)
        if (bits.size < 2 || bits[0] != 0.toByte()) malformed()
        val der = bits.copyOfRange(1, bits.size)
        // ECDSA contains two INTEGERs, so the general unique-tag parser does not apply.
        val sequence = try { org.bouncycastle.asn1.ASN1Sequence.getInstance(der) }
            catch (_: IllegalArgumentException) { malformed() }
        if (!sequence.getEncoded("DER").contentEquals(der) || sequence.size() != 2) malformed()
        for (item in sequence) {
            val integer = item as? org.bouncycastle.asn1.ASN1Integer ?: malformed()
            if (integer.value.signum() <= 0 || integer.value >= curve.order) malformed()
        }
        val signed = fields.entries.take(5).fold(byteArrayOf()) { acc, field -> acc + PivTlv.encode(field.key, field.value) }
        val valid = Signature.getInstance("SHA384withECDSA").run {
            initVerify(identity.issuer); update(signed); verify(der)
        }
        if (!valid) throw CardFailure(FailureCode.AUTHORIZATION_DENIED)
        return publicKey(key.getValue(0x86))
    }

    fun derive(z: ByteArray, hostId: ByteArray, cardId: ByteArray, nonce: ByteArray, point: ByteArray): ByteArray {
        require(z.size == 48 && hostId.size == 8 && cardId.size == 8 && nonce.size == 24 && point.size == 97)
        val info = "040d0d0d0d08".hexBytes() + hostId + "010010".hexBytes() + point.copyOfRange(1, 17) +
            byteArrayOf(8) + cardId + byteArrayOf(24) + nonce + byteArrayOf(1, 0)
        val output = ByteArray(128)
        for (i in 1..3) {
            val digest = MessageDigest.getInstance("SHA-384").run { update(byteArrayOf(0, 0, 0, i.toByte())); update(z); digest(info) }
            digest.copyInto(output, (i - 1) * 48, 0, minOf(48, 128 - (i - 1) * 48))
            digest.fill(0)
        }
        return output
    }

    fun mac(key: ByteArray, input: ByteArray): ByteArray = CMac(AESEngine.newInstance()).run {
        init(KeyParameter(key)); update(input, 0, input.size); ByteArray(16).also { doFinal(it, 0) }
    }
}

internal class Cs7Cipher(keys: ByteArray) : Closeable {
    private val mac = keys.copyOfRange(32, 64)
    private val enc = keys.copyOfRange(64, 96)
    private val rmac = keys.copyOfRange(96, 128)
    private var commandChain = ByteArray(16)
    private var responseChain = ByteArray(16)
    private val counter = ByteArray(16).also { it[15] = 1 }
    private var pending = false
    private var closed = false

    internal fun iv(response: Boolean): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"))
        doFinal(counter.copyOf().also { if (response) it[0] = 0x80.toByte() })
    }

    fun protect(apdu: Apdu): Apdu {
        check(!closed && !pending)
        require(apdu.cla == 0 && apdu.le in listOf(null, 256) && apdu.data.size <= 65487)
        val encrypted = if (apdu.data.isEmpty()) byteArrayOf() else {
            val padded = (apdu.data + byteArrayOf(0x80.toByte())).copyOf((apdu.data.size / 16 + 1) * 16)
            try { PivTlv.encode(0x87, byteArrayOf(1) + crypt(padded, false, Cipher.ENCRYPT_MODE)) }
            finally { padded.fill(0) }
        }
        val objects = encrypted + if (apdu.le == null) byteArrayOf() else "970100".hexBytes()
        val header = byteArrayOf(0x0c, apdu.ins.toByte(), apdu.p1.toByte(), apdu.p2.toByte(), 0x80.toByte()) + ByteArray(11)
        commandChain = Cs7.mac(mac, commandChain + header + objects)
        pending = true
        return apdu.copy(cla = 0x0c, data = objects + PivTlv.encode(0x8e, commandChain.copyOf(8)), le = 256)
    }

    fun unprotect(outer: ApduResponse): ApduResponse {
        check(!closed && pending)
        try {
            if (outer.status != 0x9000) throw CardFailure(FailureCode.CARD_REJECTED, outer.status)
            val fields = PivTlv.parse(outer.data)
            if (fields.keys.toList() !in listOf(listOf(0x99, 0x8e), listOf(0x87, 0x99, 0x8e))) malformed()
            val status = fields.getValue(0x99)
            val received = fields.getValue(0x8e)
            if (status.size != 2 || received.size != 8) malformed()
            val authenticated = outer.data.copyOf(outer.data.size - 10)
            val full = Cs7.mac(rmac, responseChain + authenticated)
            if (!MessageDigest.isEqual(full.copyOf(8), received)) malformed()
            val data = fields[0x87]?.let {
                if (it.size < 17 || it.size % 16 != 1 || it[0] != 1.toByte()) malformed()
                val plain = crypt(it.copyOfRange(1, it.size), true, Cipher.DECRYPT_MODE)
                try {
                    var end = plain.lastIndex
                    while (end >= 0 && plain[end] == 0.toByte()) end--
                    if (end < plain.size - 16 || end < 0 || plain[end] != 0x80.toByte()) malformed()
                    plain.copyOf(end)
                } finally { plain.fill(0) }
            } ?: byteArrayOf()
            responseChain = full
            pending = false
            for (i in 15 downTo 0) { counter[i]++; if (counter[i] != 0.toByte()) break }
            if (counter[0] != 0.toByte() || counter.all { it == 0.toByte() }) { data.fill(0); malformed() }
            return ApduResponse(data, ((status[0].toInt() and 255) shl 8) or (status[1].toInt() and 255))
        } catch (e: Exception) { close(); throw e }
    }

    private fun crypt(data: ByteArray, response: Boolean, mode: Int): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").run {
        init(mode, SecretKeySpec(enc, "AES"), IvParameterSpec(iv(response))); doFinal(data)
    }

    override fun close() { closed = true; pending = false; listOf(mac, enc, rmac, commandChain, responseChain, counter).forEach { it.fill(0) } }
}
