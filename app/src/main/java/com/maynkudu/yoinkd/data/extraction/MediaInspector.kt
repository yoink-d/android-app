package com.maynkudu.yoinkd.data.extraction

import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.MediaLink
import com.maynkudu.yoinkd.core.model.SourceFormat
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import okhttp3.Request
import java.net.URI

class MediaInspector(private val extractor: SourceExtractor, private val http: HttpMediaClient) {
    suspend fun inspect(link: MediaLink): SourceMedia {
        val path = URI(link.value).path.orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase()
        val audio = extension in setOf("mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "caf", "ogg", "opus")
        val video = extension in setOf("mp4", "mov", "mkv", "webm", "m4v", "avi", "mpeg", "mpg", "ts")
        if (!audio && !video) return extractor.inspect(link)
        return http.read(Request.Builder().url(link.value).head().build()) { response ->
            check(response.isSuccessful && response.request.url.isHttps) { "Could not check this media. HTTP ${response.code}." }
            val type = response.header("Content-Type").orEmpty().substringBefore(';')
            require(!type.startsWith("text/") && !type.contains("json")) { "This link does not return a media file." }
            val kind = if (audio) MediaKind.AUDIO else MediaKind.VIDEO
            val format = SourceFormat("direct", extension, if (video) "unknown" else null, "unknown",
                bytes = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }, protocol = "https")
            SourceMedia(link.value, path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Media from ${link.host}" },
                link.host, null, null, null, null, listOf(format), isDirect = true)
        }
    }
}
