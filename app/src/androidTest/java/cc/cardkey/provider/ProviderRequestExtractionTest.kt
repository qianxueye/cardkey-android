package cc.cardkey.provider

import android.Manifest
import android.app.Activity
import android.app.Application
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        val exceptionKey = android.service.credentials.CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION
        val responseKey = android.service.credentials.CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE
        ActivityScenario.launchActivityForResult<ProviderActivity>(Intent(context, ProviderActivity::class.java)
            .setAction(ProviderRequests.ACTION_GET).putExtra("requestJson", getJson)).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(android.credentials.GetCredentialException.TYPE_UNKNOWN,
                result.resultData.getSerializableExtra(exceptionKey, android.credentials.GetCredentialException::class.java)?.type)
            assertFalse(result.resultData.hasExtra(responseKey))
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val chromeSigning = try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.QUERY_ALL_PACKAGES)
            requireNotNull(context.packageManager.getPackageInfo(CallerVerifier.CHROME_PACKAGE,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())).signingInfo)
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        val chrome = CallingAppInfo(CallerVerifier.CHROME_PACKAGE, chromeSigning, "https://example.com")
        assertEquals("https://example.com", CallerVerifier.verifiedOrigin(chrome))
        val hash = ByteArray(32) { it.toByte() }
        assertTrue(CallerVerifier().verify(chrome, "example.com", "AQID", "webauthn.get", hash).browserDelegated)
        val token = ProviderRequests.registry.offer(OfferedRequest(ProviderRequestKind.GET, getJson, hash,
            CallerSnapshot.from(chrome), "chosen"))
        val query = GetPublicKeyCredentialOption(getJson, clientDataHash = hash)
        val platformRequest = android.service.credentials.BeginGetCredentialRequest.Builder()
            .setCallingAppInfo(android.service.credentials.CallingAppInfo(CallerVerifier.CHROME_PACKAGE,
                chromeSigning, "https://example.com"))
            .addBeginGetCredentialOption(android.service.credentials.BeginGetCredentialOption("chosen",
                PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, query.candidateQueryData)).build()
        val input = Intent(context, ProviderActivity::class.java).setAction(ProviderRequests.ACTION_GET)
            .setData(Uri.parse("cardkey-provider://request/$token"))
            .putExtra("android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST", platformRequest)
        val recreated = AtomicReference<Activity?>()
        val restored = AtomicBoolean(false)
        val finishedDuringCreate = AtomicBoolean(false)
        val resumedAfterRecreation = AtomicBoolean(false)
        val destroyed = CountDownLatch(1)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is ProviderActivity) {
                    recreated.set(activity)
                    restored.set(state != null)
                }
            }
            override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
                if (activity === recreated.get()) finishedDuringCreate.set(activity.isFinishing)
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity === recreated.get()) resumedAfterRecreation.set(true)
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === recreated.get()) destroyed.countDown()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }
        ActivityScenario.launchActivityForResult<ProviderActivity>(input).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            val application = context.applicationContext as Application
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
            try {
                var original: Activity? = null
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    original = activity
                    // ActivityScenario.recreate() requires RESUMED, but this activity must finish.
                    activity.recreate()
                }
                assertTrue("Recreated provider must finish", destroyed.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertNotNull(recreated.get())
                assertNotSame(original, recreated.get())
                assertTrue("Recreation must carry saved state", restored.get())
                assertTrue("Saved-state request must finish before card work", finishedDuringCreate.get())
                assertFalse(resumedAfterRecreation.get())
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertEquals(android.credentials.GetCredentialException.TYPE_USER_CANCELED,
                    result.resultData.getSerializableExtra(exceptionKey, android.credentials.GetCredentialException::class.java)?.type)
                assertFalse(result.resultData.hasExtra(responseKey))
                assertThrows(IllegalArgumentException::class.java) { ProviderRequests.registry.claim(token) }
            } finally {
                instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) }
            }
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
