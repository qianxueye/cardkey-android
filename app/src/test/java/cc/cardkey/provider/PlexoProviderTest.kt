package cc.cardkey.provider

import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.hardware.usb.UsbDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.credentials.provider.CallingAppInfo
import cc.cardkey.R
import cc.cardkey.protocol.CardWire
import cc.cardkey.protocol.OperationResult
import cc.cardkey.protocol.Effect
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.fido.*
import cc.cardkey.transport.UsbReaderDescriptor
import cc.cardkey.ui.CardKeyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@OptIn(InternalComposeApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlexoProviderTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(verified: Boolean, pendingPermission: Boolean = false, creating: Boolean = false,
                       observed: ProviderRecognition? = null, accounts: List<ProvidedAssertion> = emptyList(),
                       failed: OperationResult.Failed? = null,
                       check: (ProviderActivity, ComponentActivity, UsbReaderDescriptor) -> Unit) {
        val provider = Robolectric.buildActivity(ProviderActivity::class.java).get()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val device = Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "plexo-provider-reader")
            ReflectionHelpers.setField(it, "mProductName", "Synthetic reader with a long native permission description")
        }
        val reader = UsbReaderDescriptor(device, 1, CardWire.APDU)
        val request = RequestParser.parseGet("""{"rpId":"example.com","challenge":"AQ"}""")
        ReflectionHelpers.setField(provider, "get", request.takeUnless { creating })
        val creation = if (creating) ParsedCreate("example.com", "Example", FidoUser(byteArrayOf(1), "alice", "Alice"),
            "AQ", ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.REQUIRED, emptyList(), AttestationPreference.DIRECT) else null
        ReflectionHelpers.setField(provider, "create", creation)
        if (observed != null) {
            ReflectionHelpers.callInstanceMethod<Unit>(provider, "setRecognition", ReflectionHelpers.ClassParameter.from(ProviderRecognition::class.java, observed))
            ReflectionHelpers.callInstanceMethod<Unit>(provider, "setBusy", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        }
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setAssertions", ReflectionHelpers.ClassParameter.from(List::class.java, accounts))
        if (failed != null) ReflectionHelpers.callInstanceMethod<Unit>(provider, "setFailure", ReflectionHelpers.ClassParameter.from(OperationResult.Failed::class.java, failed))
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setLoading", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setSelected", ReflectionHelpers.ClassParameter.from(UsbReaderDescriptor::class.java, reader))
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setReaders", ReflectionHelpers.ClassParameter.from(List::class.java, listOf(reader)))
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setPermissionPending", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, pendingPermission))
        ReflectionHelpers.setField(provider, "pendingReader", reader.takeIf { pendingPermission })
        var claim: RequestRegistry.Claim? = null
        if (verified) {
            val signing = ReflectionHelpers.callConstructor(SigningInfo::class.java).also {
                shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3))))
            }
            val caller = CallingAppInfo("synthetic.application.with.a.long.caller.package", signing)
            val registry = RequestRegistry()
            val kind = if (creating) ProviderRequestKind.CREATE else ProviderRequestKind.GET
            val owned = registry.claim(registry.offer(OfferedRequest(kind, "{}", null, CallerSnapshot.from(caller), "fixture")))
            claim = owned
            ReflectionHelpers.setField(provider, "extracted", ExtractedRequest(kind, "{}", null, caller, owned))
            ReflectionHelpers.setField(provider, "clientData", ClientData(byteArrayOf(1), byteArrayOf(2), false))
        }
        val content = ProviderActivity::class.java.getDeclaredMethod("ProviderContent", Composer::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                CardKeyTheme {
                    Box(Modifier.requiredSize(360.dp, 480.dp)) {
                        content.invoke(provider, currentComposer, 0)
                    }
                }
            }
        }
        try {
            compose.waitForIdle()
            check(provider, host.get(), reader)
            assertFalse(ReflectionHelpers.getField<ProviderOperationRunner>(provider, "runner").isActive)
        } finally {
            host.pause().stop().destroy()
            claim?.finish(cancelled = true)
            request.extensions.close()
            creation?.extensions?.close()
            accounts.forEach { it.close() }
        }
    }

    @Test fun pendingNativePermissionKeepsRouteAndConfirmationDisabledWhileCancelIsReachable() {
        render(verified = true, pendingPermission = true) { provider, host, reader ->
            compose.onNodeWithText("NFC", substring = false).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(host.getString(R.string.provider_getAction)).assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("FIDO PIN").assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_cancel)).assertIsDisplayed().assertHasClickAction()
            assertSame(reader, ReflectionHelpers.callInstanceMethod<UsbReaderDescriptor>(provider, "getSelected"))
            compose.onNodeWithText("example.com").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("synthetic.application.with.a.long.caller.package").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun unverifiedRequestCannotConfirmOrDisplayVerifiedAttribution() {
        render(verified = false) { _, host, _ ->
            compose.onNodeWithText(host.getString(R.string.plexo_provider_verified)).assertDoesNotExist()
            compose.onNodeWithText("example.com").assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.provider_getAction)).assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("FIDO PIN").assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_cancel)).assertIsDisplayed().assertHasClickAction()
        }
    }
    @Test fun verifiedCreationShowsAttestationAndAccountBeforeTheDockedTaskAction() {
        render(verified = true, creating = true) { _, host, _ ->
            compose.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(host.getString(R.string.provider_attestation_direct)).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(host.getString(R.string.provider_createAction)) and hasClickAction()).assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("FIDO PIN").assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_cancel)).assertIsDisplayed()
        }
    }
    @Test fun freshFinalAttemptShowsConditionalPinAndExplicitSingleAttemptAction() {
        render(verified = true, observed = ProviderRecognition(true, 1)) { _, host, _ ->
            compose.onNodeWithText("FIDO PIN").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(host.getString(R.string.confirm_fidoLastRetry)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(host.getString(R.string.provider_finalAttemptGet)).assertIsDisplayed().assertIsNotEnabled()
            compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).performScrollTo().performTextInput("1234")
            compose.onNodeWithText(host.getString(R.string.provider_finalAttemptGet)).assertIsEnabled()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_cancel)).assertIsDisplayed()
        }
    }
    @Test fun unknownEffectsNeverOfferARepeatActionOrClaimSiteSuccess() {
        render(verified = true, failed = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
            dispatch = cc.cardkey.protocol.Dispatch.RESPONSE_UNAVAILABLE)) { _, host, _ ->
            compose.onNodeWithText(host.getString(R.string.operation_unknown)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(host.getString(R.string.provider_getAction)).assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_cancel)).assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_close)).assertIsDisplayed()
        }
    }

}
