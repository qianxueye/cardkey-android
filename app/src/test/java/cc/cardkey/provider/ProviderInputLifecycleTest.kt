package cc.cardkey.provider

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProviderInputLifecycleTest {
    @Test fun destructionWipesPendingCreateAndGetInputs() {
        val controller = Robolectric.buildActivity(ProviderActivity::class.java)
        val activity = controller.get()
        val creation = RequestParser.parseCreate("""{"rp":{"id":"example.com","name":"Example"},"user":{"id":"AQ","name":"a","displayName":"A"},"challenge":"AQ","pubKeyCredParams":[{"type":"public-key","alg":-7}],"extensions":{"credBlob":"AQID"}}""")
        val request = RequestParser.parseGet("""{"rpId":"example.com","challenge":"AQ","allowCredentials":[{"type":"public-key","id":"AQ"}],"extensions":{"prf":{"eval":{"first":"AQ","second":"Ag"},"evalByCredential":{"AQ":{"first":"Aw"}}},"largeBlob":{"write":"BA"}}}""")
        ProviderActivity::class.java.getDeclaredField("create").apply { isAccessible = true }.set(activity, creation)
        ProviderActivity::class.java.getDeclaredField("get").apply { isAccessible = true }.set(activity, request)
        controller.destroy()
        assertArrayEquals(ByteArray(3), creation.extensions.credBlob)
        assertArrayEquals(byteArrayOf(0), request.extensions.prfInputs!!.first)
        assertArrayEquals(byteArrayOf(0), request.extensions.prfInputs.second)
        assertArrayEquals(byteArrayOf(0), request.extensions.prfByCredential["AQ"]!!.first)
        assertArrayEquals(byteArrayOf(0), request.extensions.largeBlobWrite)
    }

    @Test fun cancellationWipesPendingInputsBeforeActivityDestruction() {
        val activity = Robolectric.buildActivity(ProviderActivity::class.java,
            Intent().setAction(ProviderRequests.ACTION_GET)).get()
        val request = ParsedGet("example.com", "AQ", listOf(byteArrayOf(1)), UserVerificationRequirement.DISCOURAGED,
            GetExtensionInputs(prfInputs = PrfInputs(byteArrayOf(3)), largeBlobWrite = byteArrayOf(4)))
        ProviderActivity::class.java.getDeclaredField("get").apply { isAccessible = true }.set(activity, request)
        ProviderActivity::class.java.getDeclaredMethod("cancelRequest").apply { isAccessible = true }.invoke(activity)
        assertArrayEquals(byteArrayOf(0), request.extensions.prfInputs!!.first)
        assertArrayEquals(byteArrayOf(0), request.extensions.largeBlobWrite)
    }
}
