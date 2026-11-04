package com.maynkudu.yoinkd.data.extraction

import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.MediaLink
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.core.model.SourceSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

class SourceExtractor(private val runtime: ExtractorRuntime, private val updates: ExtractorUpdates) {
    suspend fun inspect(link: MediaLink): SourceMedia {
        updates.restore()
        return recoverYoutubeForbidden(link.value, { updates.refreshAutomatically(force = true) }, { inspectOnce(link) }, { inspectOnce(link) })
    }

    private suspend fun inspectOnce(link: MediaLink): SourceMedia = parseSourceMedia(runtime.extract(
        UUID.randomUUID().toString(), listOf("--dump-single-json", "--skip-download", link.value)), link)

    suspend fun acquire(id: String, media: SourceMedia, selection: SourceSelection, directory: File,
        onProgress: (Long, Long?) -> Unit): List<File> {
        updates.restore()
        return recoverYoutubeForbidden(media.sourceUrl,
            { updates.refreshAutomatically(force = true) },
            { acquireOnce(id, media, selection, directory, onProgress) },
            { acquireOnce(id, inspectOnce(MediaLink.parse(media.sourceUrl)), selection, directory, onProgress) })
    }

    private suspend fun acquireOnce(id: String, media: SourceMedia, selection: SourceSelection, directory: File,
        onProgress: (Long, Long?) -> Unit): List<File> {
        val selected = media.selected(selection.kind, selection.formatId)
            ?: error("That quality is no longer available. Check the link again.")
        val formats = buildList {
            add(selected)
            if (selection.kind == MediaKind.VIDEO && !selected.hasAudio)
                media.choices(MediaKind.AUDIO).firstOrNull { !it.hasVideo }?.let(::add)
        }
        withContext(Dispatchers.IO) { directory.mkdirs() }
        val files = mutableListOf<File>()
        var completedBytes = 0L
        val predicted = media.size(selection.kind, selection.formatId)?.bytes
        for (format in formats) {
            val digest = MessageDigest.getInstance("SHA-256").digest(format.id.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
            val prefix = "stream-$digest."
            val existing = withContext(Dispatchers.IO) {
                directory.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.length() > 0 &&
                    it.extension !in setOf("part", "ytdl", "temp") && !it.name.contains(".part-") }
            }
            val file = existing ?: run {
                runtime.extract(id, listOf("--continue", "--newline", "--no-mtime", "--no-simulate",
                    "--progress-template", "download:__YOINKD_PROGRESS:%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s",
                    "--format", format.id, "--output", "${prefix}%(ext)s", media.sourceUrl), directory) { line ->
                    if (line.startsWith("__YOINKD_PROGRESS:")) {
                        val parts = line.substringAfter(':').split('|')
                        val current = parts.getOrNull(0)?.toDoubleOrNull()?.toLong() ?: 0
                        val total = parts.getOrNull(1)?.toDoubleOrNull()?.toLong()
                            ?: parts.getOrNull(2)?.toDoubleOrNull()?.toLong()
                        onProgress(completedBytes + current, predicted ?: total?.let { completedBytes + it })
                    }
                }
                withContext(Dispatchers.IO) {
                    directory.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.length() > 0 &&
                        it.extension !in setOf("part", "ytdl", "temp") && !it.name.contains(".part-") }
                        ?: error("The source transfer did not produce a media file.")
                }
            }
            files += file
            completedBytes += withContext(Dispatchers.IO) { file.length() }
            onProgress(completedBytes, predicted ?: completedBytes)
        }
        return files
    }
}

internal suspend fun <T> recoverYoutubeForbidden(url: String, refresh: suspend () -> Unit,
    first: suspend () -> T, retry: suspend () -> T): T {
    try { return first() }
    catch (error: NativeProcessException) {
        if (!isYoutubeForbidden(url, error)) throw error
        refresh()
        try { return retry() }
        catch (second: NativeProcessException) {
            if (!isYoutubeForbidden(url, second)) throw second
            throw NativeProcessException("YouTube still returned HTTP 403 after refreshing the link. Check that it plays without sign-in and try again later.")
        }
    }
}

internal fun isYoutubeForbidden(url: String, error: NativeProcessException): Boolean {
    val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
    return (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")) &&
        Regex("(?i)\\b(?:http(?: error)?[^\\n]*403|403[^\\n]*forbidden)\\b").containsMatchIn(error.message.orEmpty())
}
