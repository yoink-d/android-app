package com.maynkudu.yoinkd.core

import com.maynkudu.yoinkd.core.model.*
import com.maynkudu.yoinkd.data.extraction.parseSourceMedia
import org.junit.Assert.*
import org.junit.Test

class MediaRulesTest {
    private val link = MediaLink.parse("https://example.com/watch?v=test")

    @Test fun rejectsInvalidLinksBeforeNetworkWork() {
        for (input in listOf("", "http://example.com/file.mp4", "file:///media.mp4", "https://user:password@example.com/", "https://example.com/a b", "https:///missing-host")) {
            assertThrows(IllegalArgumentException::class.java) { MediaLink.parse(input) }
        }
        assertEquals("https://example.com/a?signature=a%2Fb%3D", MediaLink.parse(" HTTPS://example.com/a?signature=a%2Fb%3D ").value)
    }

    @Test fun portraitQualityUsesTheShorterDimension() {
        val format = SourceFormat("portrait", "mp4", "h264", "aac", width = 1080.0, height = 1920.0, fps = 60.0)
        assertEquals(1080, format.resolution)
        assertEquals("1080p, H.264, 60 fps", format.label(MediaKind.VIDEO))
    }

    @Test fun retainsDistinctSameResolutionSourcesAndPrefersAudioOnly() {
        val source = parseSourceMedia("""{"title":"Example","duration":20,"formats":[
          {"format_id":"audio","url":"https://example.com/a","ext":"m4a","vcodec":"none","acodec":"aac","filesize":1000},
          {"format_id":"vp9","url":"https://example.com/v","ext":"webm","vcodec":"vp9","acodec":"none","width":640,"height":360,"filesize":2000},
          {"format_id":"h264","url":"https://example.com/c","ext":"mp4","vcodec":"h264","acodec":"aac","width":640,"height":360,"filesize":4000}
        ]}""", link)
        assertEquals(listOf("h264", "vp9"), source.choices(MediaKind.VIDEO).map { it.id })
        assertEquals("audio", source.selected(MediaKind.AUDIO, null)?.id)
        assertEquals(3000L, source.size(MediaKind.VIDEO, "vp9")?.bytes)
        assertFalse(source.size(MediaKind.VIDEO, "vp9")!!.estimated)
        assertEquals(4000L, source.size(MediaKind.VIDEO, "h264")?.bytes)
        assertNull(source.selected(MediaKind.VIDEO, "no-longer-available"))
    }

    @Test fun doesNotPromiseUnknownOrOverflowingSizes() {
        val format = SourceFormat("a", "mp4", "h264", "aac", bitrate = 200.0)
        assertTrue(format.size(20.0)!!.estimated)
        assertEquals(500_000L, format.size(20.0)!!.bytes)
        assertNull(format.size(null))
        assertNull(format.size(Double.POSITIVE_INFINITY))
        val source = SourceMedia(link.value, "Media", "Example", null, null, 10.0, null,
            listOf(SourceFormat("v", "mp4", "h264", "none", bytes = Long.MAX_VALUE),
                SourceFormat("a", "m4a", "none", "aac", bytes = 100)))
        assertNull(source.size(MediaKind.VIDEO, "v"))
    }

    @Test fun rejectsPlaylistsLiveStreamsAndDrm() {
        for (json in listOf("{\"_type\":\"playlist\"}", "{\"_type\":\"multi_video\"}",
            "{\"is_live\":true}", "{\"has_drm\":true}")) {
            assertThrows(IllegalArgumentException::class.java) { parseSourceMedia(json, link) }
        }
    }

    @Test fun outputChoicesReflectRealEncoderAndMuxerSupport() {
        val support = OutputSupport(setOf("aac", "flac"), setOf("mp4", "ipod", "flac"))
        assertEquals(listOf(OutputFormat.AUTOMATIC, OutputFormat.MP4), support.available(MediaKind.VIDEO))
        assertEquals(listOf(OutputFormat.AUTOMATIC, OutputFormat.M4A, OutputFormat.FLAC), support.available(MediaKind.AUDIO))
        assertThrows(IllegalArgumentException::class.java) { SourceSelection(MediaKind.VIDEO, output = OutputFormat.MP3) }
    }

    @Test fun limitsSlotsAndSourceAcquisitionIndependently() {
        fun job(id: String, phase: DownloadPhase, direct: Boolean) = DownloadJob(id, link.value, id, 1,
            SourceSelection(MediaKind.VIDEO), direct, phase)
        val processing = job("processing", DownloadPhase.PROCESSING, false)
        val source = job("source", DownloadPhase.QUEUED, false)
        val direct = job("direct", DownloadPhase.QUEUED, true)
        assertEquals(source, DownloadRules.next(listOf(processing, source, direct), false))
        assertEquals(direct, DownloadRules.next(listOf(processing, source, direct), true))
        assertNull(DownloadRules.next(listOf(processing, job("saving", DownloadPhase.SAVING, true), source), false))
        assertNull(DownloadRules.next(listOf(job("pausing", DownloadPhase.PAUSING, true), job("cancelling", DownloadPhase.CANCELLING, true), direct), false))
    }

    @Test fun interruptedJobsPauseAndCommittedJobsComplete() {
        for (phase in listOf(DownloadPhase.EXTRACTING, DownloadPhase.DOWNLOADING, DownloadPhase.PROCESSING, DownloadPhase.SAVING))
            assertEquals(DownloadPhase.PAUSED, DownloadRules.restoredPhase(phase, false))
        assertEquals(DownloadPhase.CANCELLED, DownloadRules.restoredPhase(DownloadPhase.CANCELLING, false))
        assertEquals(DownloadPhase.COMPLETED, DownloadRules.restoredPhase(DownloadPhase.SAVING, true))
        assertEquals(DownloadPhase.QUEUED, DownloadRules.restoredPhase(DownloadPhase.QUEUED, false))
    }

    @Test fun managedFileNamesCannotEscapeTheirFolderOrExceedItsByteLimit() {
        val name = managedFileName("8d8543b9-eeb6-4ab4-b15c-b3fd810b16c7", "../" + "曲".repeat(100), "mp4")
        assertFalse(name.contains('/'))
        assertTrue(name.toByteArray().size <= 240)
        assertTrue(name.endsWith(".mp4"))
        assertThrows(IllegalArgumentException::class.java) { managedFileName("../id", "Title", "mp4") }
    }
}
