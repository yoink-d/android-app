package com.maynkudu.yoinkd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.OutputFormat
import com.maynkudu.yoinkd.core.model.SourceSelection
import com.maynkudu.yoinkd.support.NativeFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaProcessingIntegrationTest {
    private lateinit var fixture: NativeFixture

    @Before fun setUp() { fixture = NativeFixture() }
    @After fun tearDown() { fixture.close() }

    @Test fun everyOfferedAudioOutputProducesValidAudio() = runBlocking {
        val available = fixture.runtime.outputSupport().available(MediaKind.AUDIO)
        assertEquals(setOf(OutputFormat.AUTOMATIC, OutputFormat.M4A, OutputFormat.AAC, OutputFormat.MP3,
            OutputFormat.FLAC, OutputFormat.ALAC, OutputFormat.WAV, OutputFormat.AIFF, OutputFormat.CAF), available.toSet())
        for (format in available) {
            val directory = File(fixture.root, format.name).apply { mkdirs() }
            val prepared = fixture.processor.prepare("audio-${fixture.id}", fixture.source(),
                SourceSelection(MediaKind.AUDIO, output = format), listOf(fixture.sourceFile()), directory) { _, _ -> }
            val info = fixture.processor.probe.inspect("audio-probe-${fixture.id}", File(prepared.filePath))
            assertEquals(format.name, MediaKind.AUDIO, info.kind)
            assertNotNull(format.name, info.audioCodec)
            assertNull(format.name, info.videoCodec)
            assertEquals(format.name, 2.0, info.duration, 0.5)
            if (format in setOf(OutputFormat.M4A, OutputFormat.MP3, OutputFormat.FLAC, OutputFormat.ALAC)) {
                assertEquals("Native test source", info.title)
                assertEquals("yoinkd verification", info.artist)
            }
        }
    }

    @Test fun videoOutputsTranscodeUnsupportedVideoAndRetainAudio() = runBlocking {
        assertTrue("libx264" in fixture.runtime.outputSupport().encoders)
        for (format in listOf(OutputFormat.AUTOMATIC, OutputFormat.MP4, OutputFormat.MOV)) {
            val directory = File(fixture.root, format.name).apply { mkdirs() }
            val prepared = fixture.processor.prepare("video-${fixture.id}", fixture.source(),
                SourceSelection(MediaKind.VIDEO, output = format), listOf(fixture.sourceFile("mpeg4")), directory) { _, _ -> }
            val info = fixture.processor.probe.inspect("video-probe-${fixture.id}", File(prepared.filePath))
            assertEquals(MediaKind.VIDEO, info.kind)
            assertEquals("h264", info.videoCodec)
            assertEquals("aac", info.audioCodec)
            assertEquals(2.0, info.duration, 0.5)
        }
    }

    @Test fun cancellationWaitsForTheOwnedNativeProcessGroupToStop() = runBlocking {
        fixture.runtime.initialize()
        val child = CompletableDeferred<Int>()
        val python = fixture.runtime.python.absolutePath
        val script = "import subprocess,time; p=subprocess.Popen(['$python','-c','import time;time.sleep(30)']);print('CHILD:'+str(p.pid),flush=True);time.sleep(30)"
        val task = launch(Dispatchers.IO) {
            fixture.runtime.runner.execute("cancel-${fixture.id}", fixture.runtime.python,
                listOf(python, "-u", "-c", script), fixture.runtime.environment) { line ->
                if (line.startsWith("CHILD:")) child.complete(line.substringAfter(':').toInt())
            }
        }
        val pid = withTimeout(10_000) { child.await() }
        withTimeout(10_000) { task.cancelAndJoin() }
        val status = runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").first() }.getOrNull()
        assertTrue("The child must have exited or be awaiting reaping.", status == null || status == 'Z' || status == 'X')
    }
}
