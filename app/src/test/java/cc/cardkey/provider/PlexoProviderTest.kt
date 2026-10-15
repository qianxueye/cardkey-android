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

    private fun render(verified: Boolean, pendingPermission: Boolean = false,
                       check: (ProviderActivity, ComponentActivity, UsbReaderDescriptor) -> Unit) {
        val provider = Robolectric.buildActivity(ProviderActivity::class.java).get()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val device = Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "plexo-provider-reader")
            ReflectionHelpers.setField(it, "mProductName", "Synthetic reader with a long native permission description")
        }
        val reader = UsbReaderDescriptor(device, 1, CardWire.APDU)
        val request = RequestParser.parseGet("""{"rpId":"example.com","challenge":"AQ"}""")
        ReflectionHelpers.setField(provider, "get", request)
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
            val owned = registry.claim(registry.offer(OfferedRequest(ProviderRequestKind.GET, "{}", null, CallerSnapshot.from(caller), "fixture")))
            claim = owned
            ReflectionHelpers.setField(provider, "extracted", ExtractedRequest(ProviderRequestKind.GET, "{}", null, caller, owned))
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
        }
    }

    @Test fun pendingNativePermissionKeepsRouteAndConfirmationDisabledWhileCancelIsReachable() {
        render(verified = true, pendingPermission = true) { provider, host, reader ->
            compose.onNodeWithText("NFC", substring = false).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_recognize)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("PIN").assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_confirm)).assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_cancel)).performScrollTo().assertIsDisplayed().assertHasClickAction()
            assertSame(reader, ReflectionHelpers.callInstanceMethod<UsbReaderDescriptor>(provider, "getSelected"))
            compose.onNodeWithText("example.com").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("synthetic.application.with.a.long.caller.package").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun unverifiedRequestCannotConfirmOrDisplayVerifiedAttribution() {
        render(verified = false) { _, host, _ ->
            compose.onNodeWithText(host.getString(R.string.plexo_provider_verified)).assertDoesNotExist()
            compose.onNodeWithText("example.com").assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_recognize)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("PIN").assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_confirm)).assertDoesNotExist()
            compose.onNodeWithText(host.getString(R.string.plexo_provider_cancel)).performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
    }
}
