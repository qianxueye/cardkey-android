package cc.cardkey.provider

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.CallingAppInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderRequestExtractionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val signing get() = requireNotNull(context.packageManager.getPackageInfo(context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())).signingInfo)
    private val caller get() = CallingAppInfo(context.packageName, signing)
    private val platformCaller get() = android.service.credentials.CallingAppInfo(context.packageName, signing)
    private val getJson = """{"rpId":"example.com","challenge":"AQID","userVerification":"discouraged"}"""
    private val createJson = """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"AQ","name":"User","displayName":"User"},"challenge":"AQID","pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""

    private fun intent(kind: ProviderRequestKind, json: String, optionId: String? = null): Intent {
        val token = ProviderRequests.registry.offer(OfferedRequest(kind, json, null, CallerSnapshot.from(caller), optionId))
        return Intent(context, ProviderActivity::class.java)
            .setAction(if (kind == ProviderRequestKind.CREATE) ProviderRequests.ACTION_CREATE else ProviderRequests.ACTION_GET)
            .setData(Uri.parse("cardkey-provider://request/$token"))
    }

    private fun getRequest(ids: List<String> = listOf("chosen"), json: String = getJson): android.service.credentials.BeginGetCredentialRequest {
        val option = GetPublicKeyCredentialOption(json)
        return android.service.credentials.BeginGetCredentialRequest.Builder().setCallingAppInfo(platformCaller)
            .setBeginGetCredentialOptions(ids.map {
                android.service.credentials.BeginGetCredentialOption(it, PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, option.candidateQueryData)
            }).build()
    }

    @Test fun actionUsesBeginRequestAndExactChosenOption() {
        val intent = intent(ProviderRequestKind.GET, getJson, "chosen")
            .putExtra("android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST", getRequest(listOf("another", "chosen")))
            .putExtra("clientDataHash", ByteArray(32))
            .putExtra("requestJson", "attacker-controlled-extra")
        val request = ProviderRequests.extract(intent)
        assertEquals(getJson, request.json)
        assertNull(request.hash)
        assertEquals(context.packageName, request.caller.packageName)
        assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(intent) }
    }

    @Test fun actionRejectsMissingDuplicatedOrChangedSelectedOption() {
        for (ids in listOf(listOf("different"), listOf("chosen", "chosen"))) {
            val input = intent(ProviderRequestKind.GET, getJson, "chosen")
                .putExtra("android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST", getRequest(ids))
            assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(input) }
        }
        val input = intent(ProviderRequestKind.GET, getJson, "chosen")
            .putExtra("android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST", getRequest(json = getJson.replace("AQID", "AQIE")))
        assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(input) }
    }

    @Test fun createRetrievesFullSystemRequestAndRejectsQueryOnlyData() {
        val request = CreatePublicKeyCredentialRequest(createJson)
        val input = intent(ProviderRequestKind.CREATE, createJson)
            .putExtra(android.service.credentials.CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST,
                android.service.credentials.CreateCredentialRequest(platformCaller, request.type, request.credentialData))
        assertEquals(createJson, ProviderRequests.extract(input).json)
        val incomplete = intent(ProviderRequestKind.CREATE, createJson).putExtra("requestJson", createJson)
        assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(incomplete) }
    }

    @Test fun populatedOriginFromUntrustedSignerFailsInsteadOfBecomingNative() {
        val info = CallingAppInfo(context.packageName, signing, "https://example.com")
        assertThrows(CallerVerificationException::class.java) { CallerVerifier.verifiedOrigin(info) }
    }

    @Test fun directInvocationAndActivityRecreationNeverStartCardWork() {
        ActivityScenario.launch<ProviderActivity>(Intent(context, ProviderActivity::class.java)
            .setAction(ProviderRequests.ACTION_GET).putExtra("requestJson", getJson)).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
        val input = intent(ProviderRequestKind.GET, getJson, "chosen")
            .putExtra("android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST", getRequest())
        ActivityScenario.launch<ProviderActivity>(input).use { scenario ->
            scenario.recreate()
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun manifestKeepsProviderActivityPrivateAndServiceSystemBound() {
        val activity = context.packageManager.getActivityInfo(ComponentName(context, ProviderActivity::class.java), 0)
        assertFalse(activity.exported)
        val service = context.packageManager.getServiceInfo(ComponentName(context, CardKeyProviderService::class.java), 0)
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE", service.permission)
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())).requestedPermissions.orEmpty()
        assertFalse("android.permission.USE_BIOMETRIC" in permissions)
        assertFalse("android.permission.USE_FINGERPRINT" in permissions)
    }
}
