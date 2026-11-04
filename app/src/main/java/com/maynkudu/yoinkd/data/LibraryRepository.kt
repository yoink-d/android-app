package com.maynkudu.yoinkd.data

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.data.database.AppDatabase
import com.maynkudu.yoinkd.data.extraction.MediaProcessor
import com.maynkudu.yoinkd.data.storage.MediaStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryRepository(private val context: Context, database: AppDatabase, scope: CoroutineScope,
    private val storage: MediaStorage, private val processor: MediaProcessor) {
    val items = database.library().observe().map { rows -> rows.map { it.model() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun rename(item: MediaItem, title: String) = storage.rename(item.id, title)
    suspend fun delete(item: MediaItem) = storage.delete(item.id)
    suspend fun import(uris: List<Uri>) = storage.import(uris)
    suspend fun relocate(uri: String?) = storage.relocate(uri)

    suspend fun playbackUri(item: MediaItem): Uri = withContext(Dispatchers.IO) {
        val original = Uri.parse(item.uri)
        storage.files.open(original).close()
        val videoMime = when (item.videoCodec) {
            "h264" -> "video/avc"
            "hevc" -> "video/hevc"
            "vp8" -> "video/x-vnd.on2.vp8"
            "vp9" -> "video/x-vnd.on2.vp9"
            "av1" -> "video/av01"
            "mpeg4" -> "video/mp4v-es"
            else -> null
        }
        val videoSupported = item.kind != MediaKind.VIDEO || (videoMime != null &&
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && videoMime in it.supportedTypes })
        val audioSupported = item.audioCodec != "alac" && item.fileName.substringAfterLast('.').lowercase() !in setOf("aiff", "aif", "caf")
        if (videoSupported && audioSupported) return@withContext original
        val directory = File(context.cacheDir, "playback/${item.id}").apply { mkdirs() }
        val converted = File(directory, if (item.kind == MediaKind.AUDIO) "playback.flac" else "playback.mp4")
        val request = UUID.randomUUID().toString()
        if (converted.isFile && runCatching { processor.probe.inspect(request, converted) }.isSuccess)
            return@withContext Uri.fromFile(converted)
        val input = File(directory, "original.${item.fileName.substringAfterLast('.')}")
        storage.files.copyToPrivate(original, input)
        try { processor.playableCopy(request, input, converted, item.kind) }
        finally { input.delete() }
        Uri.fromFile(converted)
    }
}
