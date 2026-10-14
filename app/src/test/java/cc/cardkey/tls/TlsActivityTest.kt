package cc.cardkey.tls

import android.app.Activity
import android.content.Intent
import android.hardware.usb.*
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cc.cardkey.MainActivity
import cc.cardkey.enrollment.EnrollmentStage
import cc.cardkey.protocol.*
import cc.cardkey.transport.UsbAuthenticatorReader
import cc.cardkey.ui.ManagerViewModel
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TlsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun seed(activity: MainActivity, f: TlsFixture): ManagerViewModel {
        fun endpoint(address: Int) = Shadow.newInstanceOf(UsbEndpoint::class.java).also {
            ReflectionHelpers.setField(it, "mAddress", address)
            ReflectionHelpers.setField(it, "mAttributes", 2)
            ReflectionHelpers.setField(it, "mMaxPacketSize", 64)
        }
        val intf = Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", 1)
            ReflectionHelpers.setField(it, "mClass", 0x0b)
            ReflectionHelpers.setField(it, "mEndpoints", arrayOf(endpoint(0x81), endpoint(1)))
        }
        val device = Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "tls-activity-device")
            ReflectionHelpers.setField(it, "mVendorId", 0x04e6)
            ReflectionHelpers.setField(it, "mProductId", 0x5810)
            ReflectionHelpers.setField(it, "mInterfaces", arrayOf(intf))
        }
        val manager = activity.getSystemService(UsbManager::class.java)
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        return ViewModelProvider(activity)[ManagerViewModel::class.java].apply {
            selectReader(UsbAuthenticatorReader.discover(manager).single())
            enrollmentSettings("https://localhost", f.csr.cn)
            enrollmentRoot(enrollment.rootRequest(), f.issued.root)
            enrollment.settleCsr(enrollment.startCsr(), f.csr)
            val (owner, _) = enrollment.startIssuance(f.issued.token(f.csr))
            enrollment.settleIssuance(owner, f.bundle, EnrollmentStage.VERIFIED)
            enrollment.imported(owner)
            tlsSettings("https://localhost/qualify")
        }
    }
    private fun waitFor(predicate: () -> Boolean) = compose.waitUntil(8000) {
        shadowOf(Looper.getMainLooper()).idle()
        predicate()
    }

    @Test fun actualTlsPickerSurvivesPauseRecreationAndRetainsImportedIdentity() {
        val f = TlsFixture()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val original = controller.get()
            val model = seed(original, f)
            compose.waitForIdle()
            compose.onNodeWithText("PIV").performClick()
            compose.onNodeWithTag("tls-pin").performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("tls-root").performScrollTo().performClick()
            val launch = shadowOf(original).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            controller.recreate()
            val recreated = controller.get()
            assertSame(model, ViewModelProvider(recreated)[ManagerViewModel::class.java])
            val uri = Uri.parse("content://tls-activity/root")
            shadowOf(recreated.contentResolver).registerInputStream(uri, ByteArrayInputStream(f.issued.root))
            assertTrue(recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            waitFor { model.tls.value.serverRootSubject != null }
            assertEquals(EnrollmentStage.IMPORTED, model.enrollment.state.value.stage)
            assertSame(f.bundle, model.enrollment.state.value.bundle)
            compose.onNodeWithText("PIV").performClick()
            compose.onNodeWithTag("tls-pin").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lateDocumentReadCannotReplaceChangedEndpointRootSelection() {
        val f = TlsFixture()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val release = CountDownLatch(1)
        try {
            val activity = controller.get()
            val model = seed(activity, f)
            compose.waitForIdle(); compose.onNodeWithText("PIV").performClick()
            compose.onNodeWithTag("tls-root").performScrollTo().performClick()
            val launch = shadowOf(activity).nextStartedActivityForResult
            val entered = CountDownLatch(1)
            val uri = Uri.parse("content://tls-activity/slow-root")
            shadowOf(activity.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(f.issued.root) {
                override fun readNBytes(len: Int): ByteArray {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return super.readNBytes(len)
                }
            })
            activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            waitFor { entered.count == 0L }
            model.tlsSettings("https://new.invalid"); model.tlsSettings("https://localhost/qualify")
            release.countDown()
            waitFor { model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
            assertNull(model.tls.value.serverRootSubject)
        } finally { release.countDown(); controller.pause().stop().destroy() }
    }

    @Test fun activityPauseClosesActiveCardRunAndRetainsLateUnknownResponse() {
        val f = TlsFixture()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val activity = controller.get()
            val model = seed(activity, f)
            TlsServer(f).use { server ->
                model.tlsSettings(server.address)
                model.requestTlsRoot()
                val uri = Uri.parse("content://tls-activity/run-root")
                shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(f.issued.root))
                model.receiveTlsRoot(uri)
                waitFor { model.tls.value.serverRootSubject != null }
                var cancels = 0
                val channel = object : CardChannel by f.card {
                    override fun transmit(command: ByteArray): ByteArray {
                        if (command[1].toInt() and 255 == 0x20) {
                            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                            throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                        }
                        return f.card.transmit(command)
                    }
                    override fun cancel() { cancels++ }
                }
                val method = ManagerViewModel::class.java.getDeclaredMethod("startTls", CharArray::class.java, Function0::class.java).apply { isAccessible = true }
                method.invoke(model, "123456".toCharArray(), { channel })
                waitFor { entered.count == 0L }
                controller.pause()
                assertTrue(model.state.value.busy)
                assertTrue(cancels > 0)
                release.countDown()
                waitFor { !model.state.value.busy }
                assertEquals(PivTlsOutcome.CANCELLED, model.tls.value.result?.outcome)
                assertEquals(Effect.UNKNOWN, (model.state.value.result as OperationResult.Failed).effect)
                assertTrue(model.state.value.requiresInspection)
                assertEquals(0, f.card.commands.count { it.ins == 0x87 })
            }
        } finally { release.countDown(); controller.stop().destroy() }
    }
}
