package cc.cardkey.issuer

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class IssuerSecrets(private val preferences: SharedPreferences, private val key: () -> SecretKey = { issuerKey() }) {
    fun origin(): String = preferences.getString("origin", "").orEmpty()
    fun token(origin: String): String? {
        val value = preferences.getString("credential:$origin", null) ?: return null
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    fun save(origin: String, token: String) = synchronized(preferences) {
        require(token.isNotBlank() && token.length <= 4096 && token.all { it.code in 33..126 })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        val encrypted = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
        check(preferences.edit().putString("origin", origin).putString("credential:$origin", encrypted).commit())
    }
    fun forget(origin: String, rejectedToken: String) = synchronized(preferences) {
        if (token(origin) == rejectedToken) check(preferences.edit().remove("credential:$origin").commit())
    }
}
internal fun issuerKey(alias: String = "cardkey-issuer-operator-v1"): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}
