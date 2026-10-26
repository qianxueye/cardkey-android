package cc.cardkey.issuer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IssuerKeystoreTest {
    @Test fun platformKeystoreProtectsEndpointScopedCredentialsAcrossStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alias = "cardkey-issuer-test-${java.util.UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            IssuerSecrets(preferences) { issuerKey(alias) }.save("https://synthetic-a.invalid", "synthetic-instrumentation-token")
            assertFalse(preferences.all.values.any { it.toString().contains("synthetic-instrumentation-token") })
            val reopened = IssuerSecrets(preferences) { issuerKey(alias) }
            assertEquals("synthetic-instrumentation-token", reopened.token("https://synthetic-a.invalid"))
            assertNull(reopened.token("https://synthetic-b.invalid"))
            reopened.forget("https://synthetic-a.invalid")
            assertNull(reopened.token("https://synthetic-a.invalid"))
        } finally {
            preferences.edit().clear().commit()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            context.deleteSharedPreferences(alias)
        }
    }
}
