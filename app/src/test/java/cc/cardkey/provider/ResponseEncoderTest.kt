package cc.cardkey.provider

import cc.cardkey.protocol.fido.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ResponseEncoderTest {
    private val json = "{ \"type\" : \"webauthn.get\", \"challenge\":\"AQ\", \"origin\":\"https://cardkey.example\" }".toByteArray()
    private val client = ClientData(json, sha256(json), false)
    private val id = byteArrayOf(-1, -2, 0, 1)
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
    private val assertion = Assertion(id, ByteArray(37) { it.toByte() }, byteArrayOf(1, 2, 3),
        FidoUser(byteArrayOf(8, 9), "name", "display"), null, null)

    @Test fun assertionEncodesExactClientDataAndSignedBytesWithoutPadding() {
        val result = JsonParser.parseString(ResponseEncoder.get(assertion, client)).asJsonObject
        assertEquals("public-key", result["type"].asString)
        assertEquals(result["id"], result["rawId"])
        assertArrayEquals(id, decode(result["rawId"].asString))
        assertFalse(result["id"].asString.contains('='))
        assertFalse(result["id"].asString.contains('+'))
        assertFalse(result["id"].asString.contains('/'))
        assertEquals(0, result["clientExtensionResults"].asJsonObject.size())
        val response = result["response"].asJsonObject
        assertArrayEquals(json, decode(response["clientDataJSON"].asString))
        assertArrayEquals(assertion.authData, decode(response["authenticatorData"].asString))
        assertArrayEquals(assertion.signature, decode(response["signature"].asString))
        assertArrayEquals(assertion.user!!.id, decode(response["userHandle"].asString))
    }

    @Test fun absentUserHandleIsNullAndBrowserDelegatedClientDataIsOmitted() {
        val result = JsonParser.parseString(ResponseEncoder.get(assertion.copy(user = null),
            ClientData(byteArrayOf(), ByteArray(32) { 7 }, true))).asJsonObject
        val response = result["response"].asJsonObject
        assertFalse(response.has("clientDataJSON"))
        assertTrue(response["userHandle"].isJsonNull)
        assertArrayEquals(assertion.signature, decode(response["signature"].asString))
    }

    @Test fun creationEncodesPreparedAttestationAndExactNativeClientData() {
        val authData = ByteArray(80) { it.toByte() }
        val statement = textMap("alg" to (-7).cbor(), "sig" to byteArrayOf(3, 4).cbor())
        val credential = CreatedCredential(MakeCredentialResult("packed", authData, statement, null, null), id, true)
        val result = JsonParser.parseString(ResponseEncoder.create(credential, client)).asJsonObject
        val response = result["response"].asJsonObject
        assertArrayEquals(json, decode(response["clientDataJSON"].asString))
        val attestation = CborCodec.decode(decode(response["attestationObject"].asString)).map()
        assertEquals("packed", attestation["fmt"].text())
        assertEquals(statement, attestation["attStmt"])
        assertArrayEquals(authData, attestation["authData"].bytes())
        assertArrayEquals(id, decode(result["id"].asString))
    }

    @Test fun creationWithBrowserHashOmitsClientDataAndPreservesNoneObject() {
        val credential = CreatedCredential(MakeCredentialResult("none", ByteArray(60), cborMap(), null, null), id, false)
        val result = JsonParser.parseString(ResponseEncoder.create(credential,
            ClientData(byteArrayOf(), ByteArray(32) { 8 }, true))).asJsonObject
        val response = result["response"].asJsonObject
        assertFalse(response.has("clientDataJSON"))
        val attestation = CborCodec.decode(decode(response["attestationObject"].asString)).map()
        assertEquals("none", attestation["fmt"].text())
        assertTrue(attestation["attStmt"].map().value.isEmpty())
    }

    @Test fun nativeClientDataMismatchCannotBeEncoded() {
        assertThrows(IllegalArgumentException::class.java) {
            ResponseEncoder.get(assertion, client.copy(hash = ByteArray(32)))
        }
    }
}
