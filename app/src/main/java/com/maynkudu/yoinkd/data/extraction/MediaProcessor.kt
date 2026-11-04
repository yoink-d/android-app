package com.maynkudu.yoinkd.data.extraction

import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.OutputFormat
import com.maynkudu.yoinkd.core.model.PreparedMedia
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.core.model.SourceSelection
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.storage.ArtworkStore
import com.maynkudu.yoinkd.data.storage.atomicText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

class MediaProcessor(val probe: MediaProbe, private val runtime: ExtractorRuntime,
    private val artwork: ArtworkStore) {
    suspend fun prepared(directory: File, id: String): PreparedMedia? {
        val result = withContext(Dispatchers.IO) {
            runCatching { mediaJson.decodeFromString<PreparedMedia>(File(directory, "result.json").readText()) }.getOrNull()
        } ?: return null
        return try { probe.inspect(id, File(result.filePath)); result }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun prepare(id: String, source: SourceMedia, selection: SourceSelection,
        files: List<File>, directory: File, onProgress: (Double, Double) -> Unit): PreparedMedia {
        require(files.isNotEmpty()) { "No source file is available." }
        val first = probe.inspect(id, files.first())
        if (selection.kind == MediaKind.VIDEO) require(first.videoCodec != null) { "This source contains no video." }
        if (selection.kind == MediaKind.AUDIO) require(first.audioCodec != null) { "This source contains no audio." }
        val format = when {
            selection.output != OutputFormat.AUTOMATIC -> selection.output
            selection.kind == MediaKind.VIDEO -> OutputFormat.MP4
            else -> when (first.audioCodec) {
                "aac" -> OutputFormat.M4A
                "mp3" -> OutputFormat.MP3
                "flac" -> OutputFormat.FLAC
                "alac" -> OutputFormat.ALAC
                "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le" -> OutputFormat.WAV
                else -> OutputFormat.FLAC
            }
        }
        val support = runtime.outputSupport()
        require(format in support.available(selection.kind)) { "That output format is unavailable on this device." }
        val temporary = File(directory, "preparing.${format.extension}")
        val final = File(directory, "prepared.${format.extension}")
        val cover = artwork.remote(source.thumbnail, File(directory, "artwork.jpg"))
            ?: artwork.fromMedia(files.first(), File(directory, "artwork.jpg"))
        val arguments = mutableListOf("-y", "-i", files.first().absolutePath)
        if (selection.kind == MediaKind.VIDEO && files.size > 1) arguments += listOf("-i", files[1].absolutePath)
        val embedsCover = selection.kind == MediaKind.AUDIO && cover != null &&
            format in setOf(OutputFormat.M4A, OutputFormat.ALAC, OutputFormat.MP3, OutputFormat.FLAC)
        if (embedsCover) arguments += listOf("-i", cover!!.absolutePath)
        if (selection.kind == MediaKind.VIDEO) {
            arguments += listOf("-map", "0:v:0", "-map", if (files.size > 1) "1:a:0?" else "0:a:0?")
            if (first.videoCodec in setOf("h264", "hevc")) arguments += listOf("-c:v", "copy")
            else {
                require("libx264" in support.encoders) { "This source needs a video encoder unavailable on this device." }
                arguments += listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p")
            }
            val audioCodec = if (files.size > 1) probe.inspect(id, files[1]).audioCodec else first.audioCodec
            arguments += listOf("-c:a", if (audioCodec == "aac") "copy" else "aac")
            if (audioCodec != null && audioCodec != "aac") arguments += listOf("-b:a", "192k")
            if (first.videoCodec == "hevc") arguments += listOf("-tag:v", "hvc1")
            arguments += listOf("-movflags", "+faststart")
        } else {
            arguments += listOf("-map", "0:a:0")
            val sameCodec = when (format) {
                OutputFormat.M4A, OutputFormat.AAC -> first.audioCodec == "aac"
                OutputFormat.MP3 -> first.audioCodec == "mp3"
                OutputFormat.FLAC -> first.audioCodec == "flac"
                OutputFormat.ALAC -> first.audioCodec == "alac"
                OutputFormat.WAV, OutputFormat.CAF -> first.audioCodec == "pcm_s16le"
                OutputFormat.AIFF -> first.audioCodec == "pcm_s16be"
                else -> false
            }
            arguments += listOf("-c:a", if (sameCodec) "copy" else format.encoder!!)
            if (!sameCodec && format in setOf(OutputFormat.M4A, OutputFormat.AAC)) arguments += listOf("-b:a", "192k")
            if (!sameCodec && format == OutputFormat.MP3) arguments += listOf("-q:a", "2")
            if (embedsCover) {
                arguments += listOf("-map", "1:v:0", "-c:v", "mjpeg", "-disposition:v:0", "attached_pic",
                    "-metadata:s:v:0", "title=Cover", "-metadata:s:v:0", "comment=Cover (front)")
                if (format == OutputFormat.MP3) arguments += listOf("-id3v2_version", "3")
            } else arguments += "-vn"
        }
        arguments += listOf("-metadata", "title=${source.title}")
        (source.artist ?: source.uploader)?.let { arguments += listOf("-metadata", "artist=$it") }
        arguments += listOf("-f", format.muxer!!, "-progress", "pipe:1", "-nostats", temporary.absolutePath)
        runtime.process(id, arguments) { line ->
            if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms="))
                line.substringAfter('=').toDoubleOrNull()?.let { onProgress(it / 1_000_000, first.duration) }
        }
        val info = probe.inspect(id, temporary)
        require(info.kind == selection.kind) { "The prepared file does not contain the selected media." }
        withContext(Dispatchers.IO) { check(temporary.renameTo(final)) { "Could not stage the prepared media." } }
        return PreparedMedia(final.absolutePath, source.title, info.kind, format.mime, info.duration,
            source.artist ?: source.uploader ?: info.artist, cover?.absolutePath, source.sourceUrl,
            info.audioCodec, info.videoCodec).also { result ->
            withContext(Dispatchers.IO) { atomicText(File(directory, "result.json"), mediaJson.encodeToString(result)) }
        }
    }

    suspend fun import(file: File, id: String, title: String, directory: File): PreparedMedia {
        val info = probe.inspect(id, file)
        val thumbnail = artwork.fromMedia(file, File(directory, "artwork.jpg"))
        return PreparedMedia(file.absolutePath, info.title ?: title, info.kind, mimeFor(file.extension, info.kind),
            info.duration, info.artist, thumbnail?.absolutePath, audioCodec = info.audioCodec, videoCodec = info.videoCodec)
    }

    suspend fun playableCopy(id: String, input: File, output: File, kind: MediaKind) {
        val arguments = mutableListOf("-y", "-i", input.absolutePath)
        if (kind == MediaKind.AUDIO) arguments += listOf("-map", "0:a:0", "-vn", "-c:a", "flac")
        else arguments += listOf("-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264",
            "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart")
        arguments += output.absolutePath
        runtime.process(id, arguments)
        probe.inspect(id, output)
    }
}

fun mimeFor(extension: String, kind: MediaKind): String = when (extension.lowercase()) {
    "mp4", "m4v" -> if (kind == MediaKind.VIDEO) "video/mp4" else "audio/mp4"
    "mov" -> "video/quicktime"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "aif", "aiff" -> "audio/aiff"
    "caf" -> "audio/x-caf"
    "ogg", "opus" -> "audio/ogg"
    "webm" -> if (kind == MediaKind.VIDEO) "video/webm" else "audio/webm"
    "mkv" -> "video/x-matroska"
    "avi" -> "video/x-msvideo"
    else -> if (kind == MediaKind.VIDEO) "video/*" else "audio/*"
}
