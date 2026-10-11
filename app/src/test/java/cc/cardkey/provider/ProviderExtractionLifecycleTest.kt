package cc.cardkey.provider

import android.content.Intent
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.CallingAppInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProviderExtractionLifecycleTest {
    private val signing = ReflectionHelpers.callConstructor(SigningInfo::class.java).also {
        shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3))))
    }
    private val caller = CallingAppInfo("test.package", signing)
    private val platformCaller = android.service.credentials.CallingAppInfo("test.package", signing)
    private val getJson = """{"rpId":"example.com","challenge":"AQID"}"""
    private val createJson = """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"AQ","name":"User","displayName":"User"},"challenge":"AQID","pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""

    private fun input(kind: ProviderRequestKind): Intent {
        val json = if (kind == ProviderRequestKind.GET) getJson else createJson
        val id = ProviderRequests.registry.offer(OfferedRequest(kind, json, null, CallerSnapshot.from(caller), "chosen"))
        return Intent(if (kind == ProviderRequestKind.GET) ProviderRequests.ACTION_GET else ProviderRequests.ACTION_CREATE)
            .setData(Uri.parse("cardkey-provider://request/$id"))
    }

    private fun populate(input: Intent): Intent = input.apply {
        if (action == ProviderRequests.ACTION_GET) {
            val option = GetPublicKeyCredentialOption(getJson)
            putExtra("android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST",
                android.service.credentials.BeginGetCredentialRequest.Builder().setCallingAppInfo(platformCaller)
                    .addBeginGetCredentialOption(android.service.credentials.BeginGetCredentialOption("chosen",
                        PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, option.candidateQueryData)).build())
        } else {
            val request = CreatePublicKeyCredentialRequest(createJson)
            putExtra(android.service.credentials.CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST,
                android.service.credentials.CreateCredentialRequest(platformCaller, request.type, request.credentialData))
        }
    }

    @Test fun missingSystemExtrasCannotConsumeTheValidCreateOrGetEntry() {
        for (kind in ProviderRequestKind.entries) {
            val input = input(kind)
            assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(input) }
            val request = ProviderRequests.extract(populate(input))
            assertEquals(kind, request.kind)
            request.claim.finish(cancelled = false)
        }
    }

    @Test fun cancellationAllowsSystemReselectionButDuplicateAndDispatchedRequestsCannotReplay() {
        for (kind in ProviderRequestKind.entries) {
            val input = populate(input(kind))
            val first = ProviderRequests.extract(input)
            assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(input) }
            first.claim.finish(cancelled = true)
            val second = ProviderRequests.extract(input)
            assertEquals(first.json, second.json)
            assertEquals(first.caller.packageName, second.caller.packageName)
            first.claim.finish(cancelled = true)
            assertTrue(second.claim.reserveTransport())
            second.claim.finish(cancelled = true)
            assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(input) }
        }
    }
    @Test fun activityCancellationReturnsToSelectorOnlyBeforeTransportReservation() {
        for (kind in ProviderRequestKind.entries) for (reserved in listOf(false, true)) {
            val input = populate(input(kind))
            val extracted = ProviderRequests.extract(input)
            if (reserved) assertTrue(extracted.claim.reserveTransport())
            val activity = Robolectric.buildActivity(ProviderActivity::class.java, input).get()
            ReflectionHelpers.setField(activity, "extracted", extracted)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelRequest")
            assertTrue(activity.isFinishing)
            assertEquals(if (reserved) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
                shadowOf(activity).resultCode)
            if (reserved) {
                assertThrows(IllegalArgumentException::class.java) { ProviderRequests.extract(input) }
            } else {
                val selectedAgain = ProviderRequests.extract(input)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelRequest")
                assertTrue(selectedAgain.claim.reserveTransport())
                selectedAgain.claim.finish(cancelled = true)
            }
        }
    }

}
