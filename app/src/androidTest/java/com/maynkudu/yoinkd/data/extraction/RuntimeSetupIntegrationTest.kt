package com.maynkudu.yoinkd.data.extraction

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.support.HttpsFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RuntimeSetupIntegrationTest {
    private lateinit var fixture: HttpsFixture
    @Volatile private var throttle = false
    @Volatile private var corruptPython = false

    @Before fun setUp() {
        fixture = HttpsFixture()
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.requestUrl?.encodedPath?.removePrefix("/") ?: return MockResponse().setResponseCode(404)
                val file = File(fixture.inputs, if (name == "yt-dlp") name else "$name.zip")
                if (!file.isFile) return MockResponse().setResponseCode(404)
                val offset = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
                val body = if (name == "python" && corruptPython) {
                    val bytes = file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
                    Buffer().write(bytes)
                } else file.inputStream().use { input ->
                    var skipped = 0L
                    while (skipped < offset) skipped += input.skip(offset - skipped)
                    Buffer().readFrom(input)
                }
                return MockResponse().setHeader("ETag", "\"runtime-fixture\"").setBody(body).apply {
                    if (offset > 0) setResponseCode(206).setHeader("Content-Range", "bytes $offset-${file.length() - 1}/${file.length()}")
                    if (name == "python" && throttle) throttleBody(65_536, 100, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    @After fun tearDown() { fixture.close() }

    private fun packages(): RuntimePackages {
        val original = fixture.base.assets.open("runtime-manifest.json").bufferedReader().use {
            mediaJson.decodeFromString<RuntimeManifest>(it.readText())
        }
        val manifest = original.copy(
            architectures = original.architectures.mapValues { (_, value) -> value.copy(
                python = value.python.copy(url = fixture.server.url("/python").toString()),
                ffmpeg = value.ffmpeg.copy(url = fixture.server.url("/ffmpeg").toString())) },
            extractor = original.extractor.copy(url = fixture.server.url("/yt-dlp").toString()))
        return RuntimePackages(fixture.context, fixture.http, NativeProcessRunner(), manifest)
    }

    @Test fun offlineSetupRetriesAndTheInstalledToolsReopenWithoutAnotherTransfer() = runBlocking {
        withTimeout(120_000) {
            val packages = packages()
            fixture.online = false
            packages.install()
            assertEquals(RuntimeSetupPhase.FAILED, packages.state.value.phase)
            assertFalse(packages.directory.exists())
            fixture.online = true
            packages.install()
            assertEquals(packages.state.value.error, RuntimeSetupPhase.READY, packages.state.value.phase)
            assertEquals(3, fixture.server.requestCount)
            fixture.online = false
            val reopened = packages()
            reopened.install()
            assertEquals(reopened.state.value.error, RuntimeSetupPhase.READY, reopened.state.value.phase)
            assertEquals(3, fixture.server.requestCount)
            val runtime = ExtractorRuntime(fixture.context, NativeProcessRunner(), reopened)
            assertTrue(runtime.process("setup-ffmpeg-probe", listOf("-version")).contains("ffmpeg version"))
            assertTrue(runtime.outputSupport().encoders.contains("libx264"))
        }
    }

    @Test fun interruptedSetupResumesThePartialPackageOverHttps() = runBlocking {
        withTimeout(120_000) {
            val packages = packages()
            throttle = true
            val task = launch(Dispatchers.IO) { packages.install() }
            withTimeout(15_000) { packages.state.first { it.received >= 65_536 } }
            task.cancelAndJoin()
            assertEquals(RuntimeSetupPhase.PAUSED, packages.state.value.phase)
            val incoming = File(fixture.context.noBackupFilesDir, "media-tools/downloads/${packages.directory.name}/python")
            val offset = incoming.length()
            assertTrue(offset in 1 until File(fixture.inputs, "python.zip").length())
            assertNull(fixture.server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
            throttle = false
            packages.install()
            assertEquals(packages.state.value.error, RuntimeSetupPhase.READY, packages.state.value.phase)
            val resumed = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("bytes=$offset-", resumed.getHeader("Range"))
            assertEquals("\"runtime-fixture\"", resumed.getHeader("If-Range"))
            assertFalse(incoming.exists())
        }
    }

    @Test fun anInvalidChecksumCannotActivateToolsAndRetryDiscardsTheBadPackage() = runBlocking {
        withTimeout(120_000) {
            val packages = packages()
            val incoming = File(fixture.context.noBackupFilesDir, "media-tools/downloads/${packages.directory.name}").apply { mkdirs() }
            File(fixture.inputs, "ffmpeg.zip").copyTo(File(incoming, "ffmpeg"))
            File(fixture.inputs, "yt-dlp").copyTo(File(incoming, "yt-dlp"))
            corruptPython = true
            packages.install()
            assertEquals(RuntimeSetupPhase.FAILED, packages.state.value.phase)
            assertTrue(packages.state.value.error.orEmpty().contains("verification failed"))
            assertFalse(packages.directory.exists())
            assertFalse(File(incoming, "python").exists())
            assertFalse(File(incoming, "python.resume.json").exists())
            corruptPython = false
            packages.install()
            assertEquals(packages.state.value.error, RuntimeSetupPhase.READY, packages.state.value.phase)
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test fun pausingBeforeSetupStartsReleasesRequestsWaitingForTools() = runBlocking {
        val packages = packages()
        packages.pauseSetup()
        val failure = withTimeout(1_000) { runCatching { packages.awaitReady() }.exceptionOrNull() }
        assertTrue(failure is IllegalStateException)
        assertEquals(RuntimeSetupPhase.PAUSED, packages.state.value.phase)
        assertEquals(0, fixture.server.requestCount)
    }
}
