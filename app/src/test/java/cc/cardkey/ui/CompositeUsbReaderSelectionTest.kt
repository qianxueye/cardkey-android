package cc.cardkey.ui

import android.hardware.usb.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import cc.cardkey.MainActivity
import cc.cardkey.provider.ProviderActivity
import cc.cardkey.protocol.CardWire
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.transport.UsbReaderDescriptor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@OptIn(InternalComposeApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompositeUsbReaderSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun readers(): List<UsbReaderDescriptor> {
        fun endpoint(address: Int, type: Int) = Shadow.newInstanceOf(UsbEndpoint::class.java).also {
            ReflectionHelpers.setField(it, "mAddress", address)
            ReflectionHelpers.setField(it, "mAttributes", type)
            ReflectionHelpers.setField(it, "mMaxPacketSize", 64)
            ReflectionHelpers.setField(it, "mInterval", 5)
        }
        fun intf(id: Int, klass: Int, type: Int) = Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", id)
            ReflectionHelpers.setField(it, "mClass", klass)
            ReflectionHelpers.setField(it, "mEndpoints", arrayOf(endpoint(0x81, type), endpoint(1, type)))
        }
        val device = Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "composite-reader")
            ReflectionHelpers.setField(it, "mProductName", "Composite security key")
            ReflectionHelpers.setField(it, "mVendorId", 0x072f)
            ReflectionHelpers.setField(it, "mProductId", 0x2200)
            ReflectionHelpers.setField(it, "mInterfaces", arrayOf(intf(1, 0x0b, 2), intf(2, 3, 3)))
        }
        val usb = RuntimeEnvironment.getApplication().getSystemService(UsbManager::class.java)
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        return UsbAuthenticatorReader.discover(usb).also {
            assertEquals(listOf(CardWire.APDU, CardWire.CTAP), it.map { reader -> reader.wire })
            assertEquals(listOf(1, 2), it.map { reader -> reader.interfaceId })
            assertEquals(2, it.map { reader -> reader.routeKey }.toSet().size)
            assertSame(it[0].device, it[1].device)
        }
    }

    @Test fun managerLabelsSelectBothInterfacesOfOneCompositeDevice() {
        val routes = readers()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val model = ViewModelProvider(controller.get())[ManagerViewModel::class.java]
            compose.onNodeWithText("选择读卡器", substring = false).performClick()
            for ((index, protocol) in listOf("CCID", "FIDO HID").withIndex()) {
                val label = "Composite security key · $protocol · 接口 ${index + 1}"
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
                compose.waitForIdle()
                assertEquals(routes[index].routeKey, model.state.value.selectedReader?.routeKey)
                compose.onAllNodesWithText(label).assertCountEquals(2)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun providerLabelsSelectBothInterfacesInChinese() = providerLabels("接口")

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h800dp-mdpi")
    fun providerLabelsSelectBothInterfacesInEnglish() = providerLabels("interface")

    private fun providerLabels(interfaceWord: String) {
        val routes = readers()
        val provider = Robolectric.buildActivity(ProviderActivity::class.java).get()
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "refreshReaders")
        ReflectionHelpers.callInstanceMethod<Unit>(provider, "setLoading",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val content = ProviderActivity::class.java.getDeclaredMethod("ProviderContent", Composer::class.java,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        host.get().setContent { CardKeyTheme { content.invoke(provider, currentComposer, 0) } }
        try {
            for ((index, protocol) in listOf("CCID", "FIDO HID").withIndex()) {
                val label = "Composite security key · $protocol · $interfaceWord ${index + 1}"
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
                compose.waitForIdle()
                assertEquals(routes[index].routeKey,
                    ReflectionHelpers.callInstanceMethod<UsbReaderDescriptor>(provider, "getSelected").routeKey)
                compose.onNodeWithText(label).assertIsSelected()
            }
            compose.runOnIdle {
                ReflectionHelpers.setField(provider, "pendingReader", routes[1])
                ReflectionHelpers.callInstanceMethod<Unit>(provider, "setPermissionPending",
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
            }
            compose.waitForIdle()
            compose.onAllNodesWithText("Composite security key · FIDO HID · $interfaceWord 2")
                .assertCountEquals(2)
        } finally { host.pause().stop().destroy() }
    }
}
