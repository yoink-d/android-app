package com.maynkudu.yoinkd.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.maynkudu.yoinkd.core.design.ArtworkSeed
import com.maynkudu.yoinkd.core.design.extractArtworkSeed
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private data class ArtworkTarget(val key: String, val path: String? = null, val url: String? = null)

class ArtworkThemeController(private val scope: CoroutineScope, private val http: HttpMediaClient) {
    private var selected: ArtworkTarget? = null
    private var preview: ArtworkTarget? = null
    private var key: String? = null
    private var task: Job? = null
    private val request = AtomicLong()
    private val cache = object : LinkedHashMap<String, ArtworkSeed>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkSeed>?): Boolean = size > 64
    }
    private val mutableSeed = MutableStateFlow(ArtworkSeed.Neutral)
    val seed = mutableSeed.asStateFlow()

    fun select(item: MediaItem) { selected = ArtworkTarget("library:${item.id}", path = item.thumbnailPath); resolve() }
    fun preview(id: String, url: String?) { preview = ArtworkTarget("preview:$id:$url", url = url); resolve() }
    fun endPreview() { preview = null; resolve() }
    fun remove(id: String) { if (selected?.key == "library:$id") { selected = null; resolve() } }

    private fun resolve() {
        val target = preview ?: selected
        if (target?.key == key && task?.isActive == true) return
        if (target?.key == key && synchronized(cache) { cache.containsKey(key) }) return
        key = target?.key
        val identity = request.incrementAndGet()
        task?.cancel()
        mutableSeed.value = ArtworkSeed.Neutral
        if (target == null) return
        task = scope.launch(Dispatchers.IO) {
            val cached = synchronized(cache) { cache[target.key] }
            val resolved = cached ?: try {
                val bytes = when {
                    target.path != null -> File(target.path).takeIf { it.isFile }?.readBytes()
                    target.url != null -> http.bytes(target.url)
                    else -> null
                }
                if (bytes == null) ArtworkSeed.Neutral else sample(bytes).also { synchronized(cache) { cache[target.key] = it } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ArtworkSeed.Neutral }
            if (request.get() == identity) mutableSeed.value = resolved
        }
    }

    private fun sample(bytes: ByteArray): ArtworkSeed {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 96) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ArtworkSeed.Neutral
        val scale = minOf(1f, 48f / maxOf(decoded.width, decoded.height))
        val bitmap = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1), true)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return extractArtworkSeed(pixels)
    }
}
