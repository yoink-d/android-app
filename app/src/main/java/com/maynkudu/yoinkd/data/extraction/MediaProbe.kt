package com.maynkudu.yoinkd.data.extraction

import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.mediaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

data class MediaInfo(val kind: MediaKind, val duration: Double, val videoCodec: String?,
    val audioCodec: String?, val title: String?, val artist: String?, val format: String?)

class MediaProbe(private val runtime: ExtractorRuntime) {
    suspend fun inspect(id: String, file: File): MediaInfo {
        require(withContext(Dispatchers.IO) { file.isFile && file.length() > 0 }) { "This media file is empty or unavailable." }
        val output = runtime.probe(id, listOf("-show_streams", "-show_format", "-of", "json", file.absolutePath))
        val json = mediaJson.parseToJsonElement(output.substringAfter('{', "").let { "{$it" }).jsonObject
        val streams = (json["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val video = streams.firstOrNull { it.text("codec_type") == "video" &&
            (it["disposition"] as? JsonObject)?.number("attached_pic") != 1.0 }
        val audio = streams.firstOrNull { it.text("codec_type") == "audio" }
        require(video != null || audio != null) { "This file contains no playable video or audio." }
        val format = json["format"] as? JsonObject
        val duration = format?.number("duration") ?: streams.mapNotNull { it.number("duration") }.maxOrNull()
        require(duration != null && duration.isFinite() && duration > 0) { "This media has no valid duration." }
        val tags = format?.get("tags") as? JsonObject
        return MediaInfo(if (video != null) MediaKind.VIDEO else MediaKind.AUDIO, duration,
            video?.text("codec_name"), audio?.text("codec_name"), tags?.text("title"),
            tags?.text("artist") ?: tags?.text("ARTIST"), format?.text("format_name"))
    }
}
