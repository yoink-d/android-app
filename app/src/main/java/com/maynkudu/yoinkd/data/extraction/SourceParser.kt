package com.maynkudu.yoinkd.data.extraction

import com.maynkudu.yoinkd.core.model.MediaLink
import com.maynkudu.yoinkd.core.model.SourceFormat
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.core.model.mediaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)
    ?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
internal fun JsonObject.number(name: String): Double? = (get(name) as? JsonPrimitive)
    ?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
internal fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true

fun parseSourceMedia(output: String, link: MediaLink): SourceMedia {
    val line = output.lineSequence().firstOrNull { it.trimStart().startsWith('{') }
        ?: error("The source did not return media details. Check the link again.")
    val start = output.indexOf(line)
    val end = output.lastIndexOf('}')
    require(end >= start) { "The source returned incomplete media details." }
    val json = mediaJson.parseToJsonElement(output.substring(start, end + 1)).jsonObject
    require(json.text("_type") !in setOf("playlist", "multi_video")) { "Choose a link to one video or audio file." }
    require(!json.flag("is_live") && json.text("live_status") != "is_live") { "Active livestreams cannot be downloaded." }
    require(!json.flag("has_drm")) { "This media is protected and cannot be downloaded." }
    val rawFormats = (json["formats"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(json)
    val formats = rawFormats.mapIndexedNotNull { rank, raw ->
        if (raw.flag("has_drm") || raw.text("url") == null) return@mapIndexedNotNull null
        val format = SourceFormat(
            id = raw.text("format_id") ?: "default", container = raw.text("ext") ?: "mp4",
            videoCodec = raw.text("vcodec"), audioCodec = raw.text("acodec"),
            width = raw.number("width"), height = raw.number("height"), fps = raw.number("fps"),
            audioBitrate = raw.number("abr"), bitrate = raw.number("tbr"),
            bytes = (raw.number("filesize") ?: raw.number("filesize_approx"))
                ?.takeIf { it < Long.MAX_VALUE.toDouble() }?.toLong(),
            estimated = raw.number("filesize") == null, rank = rank,
            sampleRate = raw.number("asr"), channels = raw.number("audio_channels")?.toInt(),
            language = raw.text("language"), dynamicRange = raw.text("dynamic_range"),
            note = raw.text("format_note"), protocol = raw.text("protocol"),
        )
        format.takeIf { it.hasAudio || it.hasVideo }
    }.distinctBy { it.id }
    require(formats.isNotEmpty()) { "This source has no downloadable video or audio." }
    val extractor = json.text("extractor_key") ?: json.text("extractor") ?: link.host
    val platform = when (extractor.lowercase()) {
        "youtube", "youtube:tab" -> "YouTube"
        "twitter", "twittercard", "x" -> "X"
        "instagram", "instagram:story" -> "Instagram"
        "tiktok" -> "TikTok"
        else -> extractor
    }
    return SourceMedia(link.value, json.text("title") ?: "Media from ${link.host}", platform,
        json.text("uploader"), json.text("artist"), json.number("duration"),
        json.text("thumbnail")?.takeIf { it.startsWith("https://") }, formats)
}
