package com.clawdroid.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.clawdroid.app.model.ModelUserImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Reads a content [Uri] and compresses to JPEG ≤ ~1.5MB for multimodal model requests.
 */
internal object ChatImageEncoder {
    private const val MAX_BYTES = (1.5 * 1024 * 1024).toInt()
    private const val MAX_EDGE_PX = 2048

    suspend fun encode(context: Context, uri: Uri): Result<ModelUserImage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val original = resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: error("无法读取图片")
                try {
                    val scaled = scaleDown(original, MAX_EDGE_PX)
                    val jpegBytes = compressJpeg(scaled, MAX_BYTES)
                    if (scaled !== original) {
                        scaled.recycle()
                    }
                    ModelUserImage(
                        mimeType = "image/jpeg",
                        base64Data = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    )
                } finally {
                    original.recycle()
                }
            }
        }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }

    private fun compressJpeg(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var quality = 88
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 8
        } while (bytes.size > maxBytes && quality >= 40)
        if (bytes.size > maxBytes) {
            // Final pass: shrink dimensions further.
            var current = bitmap
            var owned = false
            while (bytes.size > maxBytes) {
                val nw = (current.width * 0.75f).toInt().coerceAtLeast(64)
                val nh = (current.height * 0.75f).toInt().coerceAtLeast(64)
                val next = Bitmap.createScaledBitmap(current, nw, nh, true)
                if (owned) current.recycle()
                current = next
                owned = true
                val out = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, 70, out)
                bytes = out.toByteArray()
                if (nw <= 64 || nh <= 64) break
            }
            if (owned) current.recycle()
        }
        require(bytes.isNotEmpty()) { "图片压缩失败" }
        return bytes
    }
}
