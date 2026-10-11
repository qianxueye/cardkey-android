package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
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

class ManagerFidoVerificationTest {
    private val manager = ManagerOperations()
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private val publicKey = pair.public as ECPublicKey
    private val point = byteArrayOf(4) + fixed32(publicKey.w.affineX) + fixed32(publicKey.w.affineY)
    private val key = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
        -2 to fixed32(publicKey.w.affineX).cbor(), -3 to fixed32(publicKey.w.affineY).cbor())
    private val rpHash = sha256("cardkey.example".toByteArray())
    private val id = byteArrayOf(1, 2, 3)
    private val otherId = byteArrayOf(4, 5, 6)
    private val certificate = X500Name("CN=Synthetic manager diagnostic").let { name ->
        JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)).encoded
    }
    private fun sign(message: ByteArray, valid: Boolean) = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(if (valid) message else message + byteArrayOf(0))
        sign()
    }
    private fun execute(operation: ManagerOperation, channel: CardChannel) =
        CardSession(channel).run { manager.execute(it, OperationInput(operation)) }

    private fun registration(credentialId: ByteArray = id, valid: Boolean = true, none: Boolean = false): FidoChannel =
        FidoChannel { code, parameters -> when (code) {
            4 -> success(info())
            1 -> {
                val auth = rpHash + byteArrayOf(0x41, 0, 0, 0, 0) + ByteArray(16) +
                    byteArrayOf(0, credentialId.size.toByte()) + credentialId + CborCodec.encode(key)
                success(cborMap(1 to (if (none) "none" else "packed").cbor(), 2 to auth.cbor(),
                    3 to if (none) textMap() else textMap("alg" to (-7).cbor(),
                        "sig" to sign(auth + parameters!![1].bytes(), valid).cbor())))
            }
            else -> error("Unexpected CTAP command $code")
        } }

    private fun assertion(valid: Boolean, counter: Int): FidoChannel = FidoChannel { code, parameters -> when (code) {
        4 -> success(info())
        2 -> {
            assertArrayEquals(id, parameters!![3].array().single().map()["id"].bytes())
            val auth = rpHash + byteArrayOf(1, 0, 0, 0, counter.toByte())
            success(cborMap(1 to textMap("type" to "public-key".cbor(), "id" to id.cbor()),
                2 to auth.cbor(), 3 to sign(auth + parameters[2].bytes(), valid).cbor()))
        }
        else -> error("Unexpected CTAP command $code")
    } }

    private class U2fChannel(val respond: (ByteArray) -> ByteArray) : CardChannel {
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 261
        var requests = 0
        var closed = false
        override fun transmit(command: ByteArray): ByteArray {
            check(!closed)
            if (command[1].toInt() and 255 == 0xa4) return "U2F_V2".toByteArray() + byteArrayOf(0x90.toByte(), 0)
            requests++
            return respond(command) + byteArrayOf(0x90.toByte(), 0)
        }
        override fun close() { closed = true }
    }
    private fun u2fRegistration(credentialId: ByteArray = id, valid: Boolean = true) = U2fChannel { command ->
        assertEquals(1, command[1].toInt())
        val challenge = command.copyOfRange(5, 37)
        val application = command.copyOfRange(37, 69)
        byteArrayOf(5) + point + byteArrayOf(credentialId.size.toByte()) + credentialId + certificate +
            sign(byteArrayOf(0) + application + challenge + credentialId + point, valid)
    }
    private fun u2fAssertion(valid: Boolean, counter: Int) = U2fChannel { command ->
        assertEquals(2, command[1].toInt())
        assertArrayEquals(id, command.copyOfRange(70, command.size - 1))
        val presenceCounter = byteArrayOf(1, 0, 0, 0, counter.toByte())
        presenceCounter + sign(command.copyOfRange(37, 69) + presenceCounter + command.copyOfRange(5, 37), valid)
    }
    private fun rejected(result: OperationResult<*>) {
        assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED, writeAccepted = true), result)
        assertFalse(ConfirmationPolicy.resultMessages(result).contains(cc.cardkey.R.string.operation_completed))
    }

    @Test fun invalidCtapRegistrationDoesNotReplaceVerifiedCredential() {
        assertTrue(execute(ManagerOperation.FIDO_REGISTER, registration()) is OperationResult.Completed)
        val invalid = registration(otherId, valid = false)
        rejected(execute(ManagerOperation.FIDO_REGISTER, invalid))
        assertEquals(listOf(4, 1), invalid.commands.map { it.first }); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.FIDO_ASSERT, assertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun invalidCtapAssertionDoesNotAdvanceVerifiedCounter() {
        execute(ManagerOperation.FIDO_REGISTER, registration())
        val invalid = assertion(false, 9)
        rejected(execute(ManagerOperation.FIDO_ASSERT, invalid))
        assertEquals(listOf(4, 2), invalid.commands.map { it.first }); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.FIDO_ASSERT, assertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun invalidU2fRegistrationDoesNotReplaceVerifiedCredential() {
        assertTrue(execute(ManagerOperation.U2F_REGISTER, u2fRegistration()) is OperationResult.Completed)
        val invalid = u2fRegistration(otherId, false)
        rejected(execute(ManagerOperation.U2F_REGISTER, invalid))
        assertEquals(1, invalid.requests); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.U2F_ASSERT, u2fAssertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun invalidU2fAssertionDoesNotAdvanceVerifiedCounter() {
        execute(ManagerOperation.U2F_REGISTER, u2fRegistration())
        val invalid = u2fAssertion(false, 9)
        rejected(execute(ManagerOperation.U2F_ASSERT, invalid))
        assertEquals(1, invalid.requests); assertTrue(invalid.closed)
        assertTrue(execute(ManagerOperation.U2F_ASSERT, u2fAssertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun noneAttestationRetainsCredentialForVerifiedAssertion() {
        val result = execute(ManagerOperation.FIDO_REGISTER, registration(none = true)) as OperationResult.Completed
        assertEquals("NONE", result.value.rows.single { it.label == "证明" }.value)
        assertTrue(execute(ManagerOperation.FIDO_ASSERT, assertion(true, 1)) is OperationResult.Completed)
    }
    @Test fun lostRegistrationResponseIsUnknownAndNotRetried() {
        val channel = FidoChannel { code, _ -> when (code) {
            4 -> success(info())
            1 -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
            else -> error("Unexpected command")
        } }
        assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
            dispatch = Dispatch.RESPONSE_UNAVAILABLE), execute(ManagerOperation.FIDO_REGISTER, channel))
        assertEquals(listOf(4, 1), channel.commands.map { it.first }); assertTrue(channel.closed)
        val untouched = assertion(true, 1)
        assertEquals(OperationResult.Failed(FailureCode.INVALID_INPUT, Effect.NONE), execute(ManagerOperation.FIDO_ASSERT, untouched))
        assertTrue(untouched.commands.isEmpty())
    }
}
