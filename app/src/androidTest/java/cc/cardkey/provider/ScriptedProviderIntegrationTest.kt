package cc.cardkey.provider

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Parcel
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ScriptedProviderIntegrationTest {
    private val rpId = "cardkey.example"
    private val credentialId = byteArrayOf(0x11, 0x22, 0x33)
    private val user = FidoUser(byteArrayOf(0x44), "synthetic-user", "Synthetic User")
    private val createRequest = ParsedCreate(rpId, "Synthetic RP", user, "AQID",
        ResidentKeyRequirement.DISCOURAGED, UserVerificationRequirement.REQUIRED,
        emptyList(), AttestationPreference.NONE)

    @Test fun scriptedRegistrationAndNonresidentAssertionSurviveAndroidIntentRoundTrip() {
        val card = ScriptedAuthenticator()
        val createData = clientData("webauthn.create", "AQID")
        val createChannel = card.channel(createData.hash)
        val createdResult = ProviderOperationRunner().execute(create = createRequest, get = null,
            clientData = createData, pin = null, readerId = null, connect = { createChannel })
        assertTrue(createdResult is OperationResult.Completed)
        val created = ((createdResult as OperationResult.Completed).value as ProviderOutput.Created).credential
        val registrationJson = createRoundTrip(ResponseEncoder.create(created, createData))
        val registration = JsonParser.parseString(registrationJson).asJsonObject
        assertEquals("public-key", registration["type"].asString)
        assertArrayEquals(credentialId, decode(registration["rawId"].asString))
        val registrationResponse = registration["response"].asJsonObject
        assertArrayEquals(createData.json, decode(registrationResponse["clientDataJSON"].asString))
        val attestation = CborCodec.decode(decode(registrationResponse["attestationObject"].asString)).map()
        assertEquals("none", attestation["fmt"].text())
        assertTrue(attestation["attStmt"].map().value.isEmpty())
        val registrationAuth = AuthenticatorData.parse(attestation["authData"].bytes())
        assertArrayEquals(credentialId, registrationAuth.credentialId)
        assertArrayEquals(ByteArray(16), registrationAuth.aaguid)
        assertEquals(0, registrationAuth.flags and 0x18)
        val registeredKey = relyingPartyPublicKey(registrationAuth.publicKey!!)
        assertFalse(createChannel.makeParameters!![7].map()["rk"].bool())
        assertTrue(createChannel.closed)

        val getData = clientData("webauthn.get", "BAUG")
        val getChannel = card.channel(getData.hash)
        val getRequest = ParsedGet(rpId, "BAUG", listOf(credentialId), UserVerificationRequirement.REQUIRED)
        val assertionResult = ProviderOperationRunner().execute(create = null, get = getRequest,
            clientData = getData, pin = null, readerId = null, connect = { getChannel })
        assertTrue(assertionResult is OperationResult.Completed)
        val assertions = ((assertionResult as OperationResult.Completed).value as ProviderOutput.Assertions).values
        assertEquals(1, assertions.size)
        val authenticationJson = getRoundTrip(ResponseEncoder.get(assertions.single(), getData))
        val authentication = JsonParser.parseString(authenticationJson).asJsonObject
        assertArrayEquals(credentialId, decode(authentication["rawId"].asString))
        val assertionResponse = authentication["response"].asJsonObject
        val returnedAuth = decode(assertionResponse["authenticatorData"].asString)
        val returnedSignature = decode(assertionResponse["signature"].asString)
        val returnedClientData = decode(assertionResponse["clientDataJSON"].asString)
        assertArrayEquals(getData.json, returnedClientData)
        assertArrayEquals(card.lastAssertionData, returnedAuth)
        assertArrayEquals(card.lastAssertionSignature, returnedSignature)
        assertTrue(assertionResponse["userHandle"].isJsonNull)
        assertArrayEquals(sha256(rpId.toByteArray()), returnedAuth.copyOfRange(0, 32))
        assertEquals(5, returnedAuth[32].toInt() and 0x1d)
        assertTrue(verifyAtRelyingParty(registeredKey, returnedAuth, sha256(returnedClientData), returnedSignature))
        assertFalse(verifyAtRelyingParty(registeredKey, returnedAuth, createData.hash, returnedSignature))
        assertEquals(listOf(4, 4, 2), getChannel.commands)
        assertFalse(createChannel.commands.contains(0x0a))
        assertFalse(getChannel.commands.contains(0x0a))
        assertTrue(getChannel.closed)
    }

    @Test fun delegatedBrowserHashSurvivesAndroidIntentWithoutInventedClientData() {
        val card = ScriptedAuthenticator()
        val browserData = clientData("webauthn.get", "BwgJ")
        val delegated = ClientData(byteArrayOf(), browserData.hash, true)
        val channel = card.channel(browserData.hash)
        val result = ProviderOperationRunner().execute(create = null,
            get = ParsedGet(rpId, "BwgJ", listOf(credentialId), UserVerificationRequirement.REQUIRED),
            clientData = delegated, pin = null, readerId = null, connect = { channel })
        assertTrue(result is OperationResult.Completed)
        val assertion = ((result as OperationResult.Completed).value as ProviderOutput.Assertions).values.single()
        val responseJson = getRoundTrip(ResponseEncoder.get(assertion, delegated))
        val response = JsonParser.parseString(responseJson).asJsonObject["response"].asJsonObject
        assertFalse(response.has("clientDataJSON"))
        assertTrue(verifyAtRelyingParty(card.publicKey, decode(response["authenticatorData"].asString),
            browserData.hash, decode(response["signature"].asString)))
    }

    @Test fun lostCreationResponseRemainsUnknownAndRunnerDoesNotReconnectOrReplay() {
        val card = ScriptedAuthenticator()
        val data = clientData("webauthn.create", "AQID")
        val channel = card.channel(data.hash, loseCreationResponse = true)
        val runner = ProviderOperationRunner()
        var connects = 0
        val result = runner.execute(create = createRequest, get = null, clientData = data, pin = null,
            readerId = null, connect = { connects++; channel })
        assertTrue(result is OperationResult.Failed)
        result as OperationResult.Failed
        assertEquals(FailureCode.TRANSPORT_FAILURE, result.code)
        assertEquals(Effect.UNKNOWN, result.effect)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, result.dispatch)
        assertTrue(channel.closed)
        val repeated = runner.execute(create = createRequest, get = null, clientData = data, pin = null,
            readerId = null, connect = { connects++; channel }) as OperationResult.Failed
        assertEquals(FailureCode.CANCELLED, repeated.code)
        assertEquals(Dispatch.NOT_DISPATCHED, repeated.dispatch)
        assertEquals(1, connects)
        assertEquals(1, channel.commands.count { it == 1 })
    }

    private fun clientData(type: String, challenge: String): ClientData {
        val json = "{\"type\":\"$type\",\"challenge\":\"$challenge\",\"origin\":\"https://$rpId\",\"crossOrigin\":false}".toByteArray()
        return ClientData(json, sha256(json), false)
    }

    @SuppressLint("RestrictedApi")
    private fun createRoundTrip(json: String): String {
        val intent = Intent()
        PendingIntentHandler.setCreateCredentialResponse(intent, CreatePublicKeyCredentialResponse(json))
        val restored = PendingIntentHandler.retrieveCreateCredentialResponse(
            PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, parcelRoundTrip(intent)) as CreatePublicKeyCredentialResponse
        assertEquals(json, restored.registrationResponseJson)
        return restored.registrationResponseJson
    }

    @SuppressLint("RestrictedApi")
    private fun getRoundTrip(json: String): String {
        val intent = Intent()
        PendingIntentHandler.setGetCredentialResponse(intent, GetCredentialResponse(PublicKeyCredential(json)))
        val restored = PendingIntentHandler.retrieveGetCredentialResponse(parcelRoundTrip(intent))!!
            .credential as PublicKeyCredential
        assertEquals(json, restored.authenticationResponseJson)
        return restored.authenticationResponseJson
    }

    private fun parcelRoundTrip(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)

    private fun relyingPartyPublicKey(cose: Cbor.Map): PublicKey {
        assertEquals(-7L, cose[3].number())
        assertEquals(2L, cose[1].number())
        assertEquals(1L, cose[-1].number())
        val curve = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, cose[-2].bytes()), BigInteger(1, cose[-3].bytes())), curve))
    }

    private fun verifyAtRelyingParty(key: PublicKey, auth: ByteArray, hash: ByteArray, signature: ByteArray) =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(auth); update(hash); verify(signature)
        }

    private inner class ScriptedAuthenticator {
        private val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val publicKey: PublicKey get() = pair.public
        private val ec = pair.public as ECPublicKey
        private val cose = cborMap(1 to 2.cbor(), 3 to (-7).cbor(), -1 to 1.cbor(),
            -2 to fixed32(ec.w.affineX).cbor(), -3 to fixed32(ec.w.affineY).cbor())
        var lastAssertionData = byteArrayOf()
        var lastAssertionSignature = byteArrayOf()
        private fun sign(data: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(data); sign()
        }

        fun channel(hash: ByteArray, loseCreationResponse: Boolean = false) = ScriptedChannel { command, params ->
            when (command) {
                4 -> cborMap(1 to Cbor.Array(listOf("FIDO_2_1".cbor())), 3 to ByteArray(16).cbor(),
                    4 to textMap("rk" to true.cbor(), "uv" to true.cbor()))
                1 -> {
                    assertArrayEquals(hash, params!![1].bytes())
                    assertEquals(rpId, params[2].map()["id"].text())
                    assertArrayEquals(user.id, params[3].map()["id"].bytes())
                    assertEquals(-7L, params[4].array().single().map()["alg"].number())
                    assertTrue(params[7].map()["uv"].bool())
                    val auth = sha256(rpId.toByteArray()) + byteArrayOf(0x45, 0, 0, 0, 0) +
                        ByteArray(16) { 0x55 } + byteArrayOf(0, credentialId.size.toByte()) + credentialId + CborCodec.encode(cose)
                    val signed = sign(auth + hash)
                    if (loseCreationResponse) throw IOException("Synthetic response loss after credential creation")
                    cborMap(1 to "packed".cbor(), 2 to auth.cbor(),
                        3 to textMap("alg" to (-7).cbor(), "sig" to signed.cbor()))
                }
                2 -> {
                    assertEquals(rpId, params!![1].text())
                    assertArrayEquals(hash, params[2].bytes())
                    assertArrayEquals(credentialId, parseDescriptor(params[3].array().single()))
                    assertTrue(params[5].map()["up"].bool())
                    assertTrue(params[5].map()["uv"].bool())
                    lastAssertionData = sha256(rpId.toByteArray()) + byteArrayOf(5, 0, 0, 0, 1)
                    lastAssertionSignature = sign(lastAssertionData + hash)
                    cborMap(1 to descriptor(credentialId), 2 to lastAssertionData.cbor(), 3 to lastAssertionSignature.cbor())
                }
                else -> error("Unexpected CTAP command $command")
            }
        }
    }

    private class ScriptedChannel(private val respond: (Int, Cbor.Map?) -> Cbor.Map) : CardChannel {
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 261
        val commands = mutableListOf<Int>()
        var makeParameters: Cbor.Map? = null
        var closed = false
        private val chained = ByteArrayOutputStream()

        override fun transmit(command: ByteArray): ByteArray {
            check(!closed)
            if (command[1].toInt() and 255 == 0xa4)
                return "FIDO_2_0".toByteArray() + byteArrayOf(0x90.toByte(), 0)
            assertEquals(0x10, command[1].toInt() and 255)
            val length = command[4].toInt() and 255
            chained.write(command, 5, length)
            if (command[0].toInt() and 0x10 != 0) return byteArrayOf(0x90.toByte(), 0)
            val payload = chained.toByteArray()
            chained.reset()
            val operation = payload[0].toInt() and 255
            val params = if (payload.size == 1) null else CborCodec.decode(payload.copyOfRange(1, payload.size)).map()
            commands += operation
            if (operation == 1) makeParameters = params
            return byteArrayOf(0) + CborCodec.encode(respond(operation, params)) + byteArrayOf(0x90.toByte(), 0)
        }

        override fun close() { closed = true }
    }
}
