package com.maynkudu.yoinkd.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class ArtworkStore(private val http: HttpMediaClient) {
    suspend fun remote(url: String?, target: File): File? {
        if (url == null) return null
        return try { normalize(http.bytes(url), target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun fromMedia(file: File, target: File): File? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val embedded = retriever.embeddedPicture
            if (embedded != null) return@withContext normalize(embedded, target)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
            try { save(frame, target) } finally { frame.recycle() }
        } catch (_: Exception) { null }
        finally { retriever.release() }
    }

    suspend fun normalize(bytes: ByteArray, target: File): File? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
        try { save(bitmap, target) } finally { bitmap.recycle() }
    }

    private fun save(bitmap: Bitmap, target: File): File {
        target.parentFile?.mkdirs()
        val scale = minOf(1.0, 600.0 / max(bitmap.width, bitmap.height))
        val scaled = if (scale < 1) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        try {
            FileOutputStream(target).use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, output))
                output.fd.sync()
            }
        } finally { if (scaled !== bitmap) scaled.recycle() }
        return target
    }
}
