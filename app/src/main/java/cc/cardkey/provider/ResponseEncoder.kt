package cc.cardkey.provider

import cc.cardkey.protocol.fido.Assertion
import cc.cardkey.protocol.fido.CborCodec
import cc.cardkey.protocol.fido.cbor
import cc.cardkey.protocol.fido.textMap
import com.google.gson.JsonObject
import java.util.Base64

object ResponseEncoder {
    fun create(credential: CreatedCredential, clientData: ClientData): String {
        validateClientData(clientData)
        val result = credential.result
        val attestation = CborCodec.encode(textMap("fmt" to result.format.cbor(),
            "authData" to result.authData.cbor(), "attStmt" to result.statement))
        val response = response(clientData)
        response.addProperty("attestationObject", encode(attestation))
        return envelope(credential.credentialId, response).toString()
    }

    fun get(assertion: Assertion, clientData: ClientData): String {
        validateClientData(clientData)
        val response = response(clientData)
        response.addProperty("authenticatorData", encode(assertion.authData))
        response.addProperty("signature", encode(assertion.signature))
        response.addProperty("userHandle", assertion.user?.id?.let(::encode))
        return envelope(assertion.credentialId, response).toString()
    }

    private fun response(clientData: ClientData) = JsonObject().apply {
        if (!clientData.browserDelegated) addProperty("clientDataJSON", encode(clientData.json))
    }

    private fun envelope(id: ByteArray, response: JsonObject) = JsonObject().apply {
        addProperty("id", encode(id))
        addProperty("rawId", encode(id))
        addProperty("type", "public-key")
        add("response", response)
        add("clientExtensionResults", JsonObject())
    }

    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
