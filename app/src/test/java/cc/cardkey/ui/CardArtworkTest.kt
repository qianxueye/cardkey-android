package cc.cardkey.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardArtworkTest {
    private fun payload(): CardArtworkPayload {
        val image = Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        return CardArtworkPayload(bytes, "Synthetic card artwork")
    }

    @Test fun accessibilityLabelLimitCountsUnicodeScalars() {
        val image = payload()
        assertEquals("😀".repeat(200), decodeCardArtwork(image.copy(accessibilityLabel = "😀".repeat(200))).label)
        assertTrue(runCatching { decodeCardArtwork(image.copy(accessibilityLabel = "😀".repeat(201))) }.isFailure)
    }

    @Before fun initializeNativeCodec() {
        decodeCardArtwork(payload())
    }

    @Test fun validPngLoadsWithLabelAndOpaqueReference() = runBlocking {
        val reference = CardArtworkReference("public-record", "revision-2")
        var received: CardArtworkReference? = null
        val loader = CardArtworkLoader({ received = it; payload() }, this)
        loader.load(reference)
        assertEquals(CardArtworkState.Loading, loader.state.value)
        val completed = withTimeout(5000) { loader.state.first { it != CardArtworkState.Loading } }
        assertTrue("Expected decoded image, got $completed", completed is CardArtworkState.Loaded)
        val result = completed as CardArtworkState.Loaded
        assertEquals(reference, received)
        assertEquals("Synthetic card artwork", result.label)
        assertEquals(16, result.bitmap.width)
        loader.clear()
        assertEquals(CardArtworkState.Default, loader.state.value)
    }

    @Test fun unconfiguredSourceReturnsGenericCard() = runBlocking {
        val loader = CardArtworkLoader(UnconfiguredCardArtworkSource, this)
        loader.load(CardArtworkReference("public-record"))
        yield()
        assertEquals(CardArtworkState.Default, loader.state.value)
    }

    @Test fun invalidAndOversizeImagesFailWithoutLoadingBitmap() {
        for (payload in listOf(CardArtworkPayload(byteArrayOf(1, 2), "Invalid"),
            CardArtworkPayload(ByteArray(4_194_305), "Too large"),
            payload().copy(accessibilityLabel = ""))) {
            assertThrows(IllegalArgumentException::class.java) { decodeCardArtwork(payload) }
        }
        val large = Bitmap.createBitmap(4097, 1, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        large.compress(Bitmap.CompressFormat.PNG, 100, output)
        assertThrows(IllegalArgumentException::class.java) {
            decodeCardArtwork(CardArtworkPayload(output.toByteArray(), "Too wide"))
        }
    }

    @Test fun jpegIsAcceptedAndGifIsRejected() {
        val image = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 90, output)
        assertEquals(20, decodeCardArtwork(CardArtworkPayload(output.toByteArray(), "Synthetic JPEG")).bitmap.width)
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        assertThrows(IllegalArgumentException::class.java) { decodeCardArtwork(CardArtworkPayload(gif, "GIF")) }
    }

    @Test fun acceptedLargeImageDecodesToBoundedPreview() {
        val image = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, output)
        val decoded = decodeCardArtwork(CardArtworkPayload(output.toByteArray(), "Synthetic large card"))
        assertEquals(1200, decoded.bitmap.width)
        assertEquals(800, decoded.bitmap.height)
    }

    @Test fun malformedPayloadProducesVisibleFailureState() = runBlocking {
        val loader = CardArtworkLoader({ CardArtworkPayload(byteArrayOf(1, 2), "Invalid image") }, this)
        loader.load(CardArtworkReference("record"))
        val result = withTimeout(5000) { loader.state.first { it != CardArtworkState.Loading } }
        assertEquals(CardArtworkState.Failed, result)
    }

    @Test fun failureCanRetryWithoutAnyCardOperation() = runBlocking {
        var attempts = 0
        val loader = CardArtworkLoader({ attempts++; if (attempts == 1) error("Unavailable") else null }, this)
        val reference = CardArtworkReference("public-record")
        loader.load(reference)
        yield()
        assertEquals(CardArtworkState.Failed, loader.state.value)
        loader.load(reference)
        yield()
        assertEquals(CardArtworkState.Default, loader.state.value)
        assertEquals(2, attempts)
    }

    @Test fun currentSourceTimeoutExposesRetryAndPreservesParentScope() = runBlocking {
        var attempts = 0
        val loader = CardArtworkLoader({
            attempts++
            if (attempts == 1) withTimeout(50) { awaitCancellation() } else null
        }, this)
        val reference = CardArtworkReference("record")
        loader.load(reference)
        assertEquals(CardArtworkState.Failed, withTimeout(5000) { loader.state.first { it != CardArtworkState.Loading } })
        assertTrue(coroutineContext.isActive)
        loader.load(reference)
        assertEquals(CardArtworkState.Default, withTimeout(5000) { loader.state.first { it != CardArtworkState.Loading } })
        assertEquals(2, attempts)
    }

    @Test fun changedRevisionCancelsAndDiscardsUncooperativeOldLoad() = runBlocking {
        val pending = CompletableDeferred<CardArtworkPayload?>()
        val received = mutableListOf<CardArtworkReference>()
        val loader = CardArtworkLoader({ reference ->
            received += reference
            if (reference.revision == "old") withContext(NonCancellable) { pending.await() } else null
        }, this)
        loader.load(CardArtworkReference("record", "old"))
        yield()
        loader.load(CardArtworkReference("record", "new"))
        yield()
        pending.complete(payload())
        yield()
        assertEquals(listOf("old", "new"), received.map { it.revision })
        assertEquals(CardArtworkState.Default, loader.state.value)
    }

    @Test fun clearingReferenceRetiresPendingLoad() = runBlocking {
        val pending = CompletableDeferred<CardArtworkPayload?>()
        val loader = CardArtworkLoader({ withContext(NonCancellable) { pending.await() } }, this)
        loader.load(CardArtworkReference("record"))
        yield()
        loader.clear()
        pending.complete(payload())
        yield()
        assertEquals(CardArtworkState.Default, loader.state.value)
    }
}
