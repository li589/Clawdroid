package com.clawdroid.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal class CapturePreviewStore {
    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview.asStateFlow()
    private val generation = AtomicLong(0)

    fun clear() {
        generation.incrementAndGet()
        _preview.value = null
    }

    fun publishDecoded(bytes: ByteArray): DecodedPreview? {
        val token = generation.incrementAndGet()
        val decoded = runCatching { decode(bytes) }.getOrNull()
        if (generation.get() == token) {
            _preview.value = decoded?.image
        }
        return decoded
    }

    suspend fun publishDecodedAsync(bytes: ByteArray): DecodedPreview? {
        val token = generation.incrementAndGet()
        val decoded = withContext(Dispatchers.Default) {
            runCatching { decode(bytes) }.getOrNull()
        }
        if (generation.get() == token) {
            _preview.value = decoded?.image
        }
        return decoded
    }

    fun decode(bytes: ByteArray, maxPreviewDimension: Int = 1280): DecodedPreview? {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null
        }

        var sampleSize = 1
        while (max(sourceWidth / sampleSize, sourceHeight / sampleSize) > maxPreviewDimension) {
            sampleSize *= 2
            if (sampleSize > 64) {
                break
            }
        }

        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            inJustDecodeBounds = false
        }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: return null
        return DecodedPreview(
            image = bitmap.asImageBitmap(),
            width = bitmap.width,
            height = bitmap.height,
            sampleSize = sampleSize
        )
    }

    data class DecodedPreview(
        val image: ImageBitmap,
        val width: Int,
        val height: Int,
        val sampleSize: Int
    )
}
