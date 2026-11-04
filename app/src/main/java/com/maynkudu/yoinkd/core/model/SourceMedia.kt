package com.maynkudu.yoinkd.core.model

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Serializable
enum class MediaKind(val label: String) { VIDEO("Video"), AUDIO("Audio") }

data class SourceFormat(
    val id: String,
    val container: String,
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Double? = null,
    val height: Double? = null,
    val fps: Double? = null,
    val audioBitrate: Double? = null,
    val bitrate: Double? = null,
    val bytes: Long? = null,
    val estimated: Boolean = false,
    val rank: Int = 0,
    val sampleRate: Double? = null,
    val channels: Int? = null,
    val language: String? = null,
    val dynamicRange: String? = null,
    val note: String? = null,
    val protocol: String? = null,
) {
    val hasVideo: Boolean get() = !videoCodec.isNullOrBlank() && videoCodec != "none"
    val hasAudio: Boolean get() = !audioCodec.isNullOrBlank() && audioCodec != "none"
    val resolution: Int? get() = height?.let { min(width ?: it, it) }
        ?.takeIf { it.isFinite() && it > 0 && it < Int.MAX_VALUE }?.roundToInt()

    fun label(kind: MediaKind): String {
        val quality = if (kind == MediaKind.VIDEO) resolution?.let { "${it}p" } ?: "Original"
        else (audioBitrate ?: bitrate.takeUnless { hasVideo })
            ?.takeIf { it.isFinite() && it > 0 && it < Int.MAX_VALUE }
            ?.let { "${it.roundToInt()} kbps" } ?: "Original"
        val codec = codecLabel(if (kind == MediaKind.VIDEO) videoCodec else audioCodec)
        val frameRate = if (kind == MediaKind.VIDEO && fps != null && fps > 30 && fps < 1_000)
            ", ${fps.roundToInt()} fps" else ""
        return "$quality, $codec$frameRate"
    }

    fun details(kind: MediaKind): String = buildList {
        add(container.uppercase())
        if (kind == MediaKind.VIDEO) {
            add(if (hasAudio) "Includes audio" else "Separate audio")
            dynamicRange?.takeUnless { it == "SDR" }?.let(::add)
        } else {
            sampleRate?.let { add("${(it / 1_000).roundToInt()} kHz") }
            channels?.let { add(if (it == 1) "Mono" else if (it == 2) "Stereo" else "$it channels") }
            if (hasVideo) add("Audio from video")
        }
        language?.let(::add)
        note?.takeIf { it != "unknown" && it != "Default" }?.let(::add)
        add("Source $id")
    }.joinToString(" · ")

    fun size(duration: Double?): DownloadSize? {
        bytes?.takeIf { it > 0 }?.let { return DownloadSize(it, estimated) }
        val size = (duration ?: return null) * (bitrate ?: return null) * 125
        return if (size.isFinite() && size >= 1 && size < Long.MAX_VALUE.toDouble())
            DownloadSize(size.roundToLong(), true) else null
    }
}

data class DownloadSize(val bytes: Long, val estimated: Boolean) {
    val label: String get() = (if (estimated) "About " else "") + formatBytes(bytes)
}

data class SourceMedia(
    val sourceUrl: String,
    val title: String,
    val platform: String,
    val uploader: String?,
    val artist: String?,
    val duration: Double?,
    val thumbnail: String?,
    val formats: List<SourceFormat>,
    val isDirect: Boolean = false,
) {
    fun choices(kind: MediaKind): List<SourceFormat> = formats.filter {
        if (kind == MediaKind.VIDEO) it.hasVideo else it.hasAudio
    }.sortedWith(compareBy<SourceFormat> { if (kind == MediaKind.AUDIO && it.hasVideo) 1 else 0 }
        .thenByDescending { it.rank })

    fun selected(kind: MediaKind, id: String?): SourceFormat? = if (id == null)
        choices(kind).firstOrNull() else choices(kind).firstOrNull { it.id == id }

    fun size(kind: MediaKind, id: String?): DownloadSize? {
        val chosen = selected(kind, id) ?: return null
        val size = chosen.size(duration) ?: return null
        if (kind == MediaKind.VIDEO && !chosen.hasAudio) {
            val audio = choices(MediaKind.AUDIO).firstOrNull { !it.hasVideo } ?: return size
            val other = audio.size(duration) ?: return null
            if (size.bytes > Long.MAX_VALUE - other.bytes) return null
            return DownloadSize(size.bytes + other.bytes, size.estimated || other.estimated)
        }
        return size
    }
}

@Serializable
data class SourceSelection(
    val kind: MediaKind,
    val formatId: String? = null,
    val output: OutputFormat = OutputFormat.AUTOMATIC,
    val quality: String = "Best available",
) {
    init { require(output.supports(kind)) { "Choose an output format for ${kind.label.lowercase()}." } }
}

@Serializable
enum class OutputFormat(
    val label: String,
    val extension: String,
    val mime: String,
    val encoder: String?,
    val muxer: String?,
) {
    AUTOMATIC("Automatic", "", "", null, null),
    MP4("MP4", "mp4", "video/mp4", null, "mp4"),
    MOV("MOV", "mov", "video/quicktime", null, "mov"),
    M4A("M4A · AAC", "m4a", "audio/mp4", "aac", "ipod"),
    AAC("AAC", "aac", "audio/aac", "aac", "adts"),
    MP3("MP3", "mp3", "audio/mpeg", "libmp3lame", "mp3"),
    FLAC("FLAC", "flac", "audio/flac", "flac", "flac"),
    ALAC("M4A · Apple Lossless", "m4a", "audio/mp4", "alac", "ipod"),
    WAV("WAV", "wav", "audio/wav", "pcm_s16le", "wav"),
    AIFF("AIFF", "aiff", "audio/aiff", "pcm_s16be", "aiff"),
    CAF("CAF", "caf", "audio/x-caf", "pcm_s16le", "caf");

    fun supports(kind: MediaKind): Boolean = this == AUTOMATIC ||
        if (kind == MediaKind.VIDEO) this == MP4 || this == MOV else this != MP4 && this != MOV
}

data class OutputSupport(val encoders: Set<String>, val muxers: Set<String>) {
    fun available(kind: MediaKind): List<OutputFormat> = OutputFormat.entries.filter {
        it.supports(kind) && (it.encoder == null || it.encoder in encoders) &&
            (it.muxer == null || it.muxer in muxers)
    }
}

fun codecLabel(codec: String?): String = when {
    codec.isNullOrBlank() || codec == "none" || codec == "unknown" -> "Original"
    codec.startsWith("avc1") || codec == "h264" -> "H.264"
    codec.startsWith("hev1") || codec.startsWith("hvc1") || codec == "hevc" -> "HEVC"
    codec.startsWith("av01") || codec == "av1" -> "AV1"
    codec.startsWith("vp09") || codec == "vp9" -> "VP9"
    codec.startsWith("mp4a") || codec == "aac" -> "AAC"
    else -> codec.uppercase()
}
