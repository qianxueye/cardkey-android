package cc.cardkey.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CardArtworkReference(val recordId: String, val revision: String? = null)
data class CardArtworkPayload(val encodedImage: ByteArray, val accessibilityLabel: String)

fun interface CardArtworkSource {
    suspend fun load(reference: CardArtworkReference): CardArtworkPayload?
}

object UnconfiguredCardArtworkSource : CardArtworkSource {
    override suspend fun load(reference: CardArtworkReference): CardArtworkPayload? = null
}

sealed interface CardArtworkState {
    data object Default : CardArtworkState
    data object Loading : CardArtworkState
    data class Loaded(val bitmap: Bitmap, val label: String) : CardArtworkState
    data object Failed : CardArtworkState
}

internal fun decodeCardArtwork(payload: CardArtworkPayload): CardArtworkState.Loaded {
    require(payload.encodedImage.size in 1..4_194_304)
    require(payload.accessibilityLabel.isNotBlank() && payload.accessibilityLabel.length <= 200)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(payload.encodedImage, 0, payload.encodedImage.size, options)
    require(options.outMimeType in setOf("image/png", "image/jpeg"))
    require(options.outWidth in 1..4096 && options.outHeight in 1..4096)
    require(options.outWidth.toLong() * options.outHeight <= 16_000_000)
    val decodeOptions = BitmapFactory.Options()
    while (maxOf(options.outWidth, options.outHeight) / decodeOptions.inSampleSize.coerceAtLeast(1) > 1200) {
        decodeOptions.inSampleSize = decodeOptions.inSampleSize.coerceAtLeast(1) * 2
    }
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(payload.encodedImage, 0, payload.encodedImage.size, decodeOptions))
    return CardArtworkState.Loaded(bitmap, payload.accessibilityLabel)
}

internal class CardArtworkLoader(private val source: CardArtworkSource, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<CardArtworkState>(CardArtworkState.Default)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun load(reference: CardArtworkReference?) {
        val current = ++generation
        job?.cancel()
        mutableState.value = if (reference == null) CardArtworkState.Default else CardArtworkState.Loading
        if (reference == null) return
        job = scope.launch {
            val result = try {
                source.load(reference)?.let { payload -> withContext(Dispatchers.Default) { decodeCardArtwork(payload) } }
                    ?: CardArtworkState.Default
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                CardArtworkState.Failed
            }
            if (current == generation) mutableState.value = result
        }
    }

    fun clear() { load(null) }
}
