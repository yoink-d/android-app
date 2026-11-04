package com.maynkudu.yoinkd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maynkudu.yoinkd.core.model.DownloadJob
import com.maynkudu.yoinkd.core.model.DownloadPhase
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.SourceSelection
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.database.PendingOperation
import com.maynkudu.yoinkd.data.extraction.MediaInspector
import com.maynkudu.yoinkd.data.extraction.SourceExtractor
import com.maynkudu.yoinkd.data.storage.FileOperation
import com.maynkudu.yoinkd.data.storage.FileOperationKind
import com.maynkudu.yoinkd.data.storage.sha256
import com.maynkudu.yoinkd.download.DownloadEngine
import com.maynkudu.yoinkd.download.NetworkMonitor
import com.maynkudu.yoinkd.support.NativeFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PersistenceIntegrationTest {
    private lateinit var fixture: NativeFixture

    @Before fun setUp() { fixture = NativeFixture() }
    @After fun tearDown() { fixture.close() }

    private fun job(phase: DownloadPhase = DownloadPhase.SAVING, attempt: String = "current") =
        DownloadJob(fixture.id, "https://example.com/native-verification.mkv", "Native test source", 1,
            SourceSelection(MediaKind.VIDEO), true, phase, attempt)

    @Test fun completionCommitsTheFileAndHistoryClearPreservesIt() = runBlocking {
        val job = job()
        fixture.jobs.put(job)
        val prepared = fixture.prepared()
        val item = fixture.storage.commit(prepared, job)
        fixture.publicFiles += android.net.Uri.parse(item.uri)
        val completed = fixture.jobs.get(job.id)!!
        assertEquals(DownloadPhase.COMPLETED, completed.phase)
        assertEquals(item.id, completed.storedItemId)
        assertEquals(item, fixture.database.library().get(item.id)!!.model())
        assertEquals(File(prepared.filePath).inputStream().use(::sha256), fixture.files.sha256(android.net.Uri.parse(item.uri)))
        assertEquals(item, fixture.storage.commit(prepared, job))
        fixture.jobs.clearHistory()
        assertTrue(fixture.jobs.all().isEmpty())
        assertNotNull(fixture.database.library().get(item.id))
        assertEquals(item.bytes, fixture.files.open(android.net.Uri.parse(item.uri)).use { it.readBytes().size.toLong() })
    }

    @Test fun staleSaveCannotCreateACompletedJobOrLibraryEntry() = runBlocking {
        fixture.jobs.put(job(attempt = "new"))
        val failure = runCatching { fixture.storage.commit(fixture.prepared(), job(attempt = "old")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(fixture.database.library().all().isEmpty())
        assertTrue(fixture.database.operations().all().isEmpty())
        assertEquals("new", fixture.jobs.get(fixture.id)!!.attemptId)
        assertEquals(DownloadPhase.SAVING, fixture.jobs.get(fixture.id)!!.phase)
    }

    @Test fun pausedAndReplacedAttemptsRejectLateProgress() = runBlocking {
        fixture.jobs.put(job(DownloadPhase.PAUSING, "old"))
        assertNull(fixture.jobs.forAttempt(fixture.id, "old") { it.copy(receivedBytes = 999) })
        fixture.jobs.put(job(DownloadPhase.DOWNLOADING, "new"))
        assertNull(fixture.jobs.forAttempt(fixture.id, "old") { it.copy(phase = DownloadPhase.FAILED) })
        assertEquals(0L, fixture.jobs.get(fixture.id)!!.receivedBytes)
        assertEquals(200L, fixture.jobs.forAttempt(fixture.id, "new") { it.copy(receivedBytes = 200) }!!.receivedBytes)
    }

    @Test fun anOldServiceCannotPauseANewerServicesQueuedWork() = runBlocking {
        val extractor = SourceExtractor(fixture.runtime)
        val engine = DownloadEngine(fixture.context, fixture.scope, CompletableDeferred(Unit), fixture.jobs,
            MediaInspector(extractor, fixture.http), extractor, fixture.processor, fixture.http,
            fixture.storage, NetworkMonitor(fixture.context))
        engine.start("old-service")
        engine.start("new-service")
        fixture.jobs.put(job(DownloadPhase.QUEUED))
        engine.serviceStopped("old-service").join()
        assertEquals(DownloadPhase.QUEUED, fixture.jobs.get(fixture.id)!!.phase)
        engine.serviceStopped("new-service").join()
        assertEquals(DownloadPhase.PAUSED, fixture.jobs.get(fixture.id)!!.phase)
    }

    @Test fun recoveryRemovesOnlyTheRecordedOwnedPendingFile() = runBlocking {
        val pendingName = "${fixture.id}-pending.mp4"
        val pending = fixture.files.create(null, pendingName, "video/mp4").uri
        val retained = fixture.files.create(null, "${fixture.id}-published.mp4", "video/mp4").uri
        fixture.publicFiles += pending
        fixture.publicFiles += retained
        fixture.files.copy("retained".byteInputStream(), retained)
        fixture.files.publish(retained)
        val item = MediaItem(fixture.id, "Interrupted save", "", pendingName, MediaKind.VIDEO, "video/mp4", 0, 2.0, 1)
        val operation = FileOperation("save-${fixture.id}", FileOperationKind.SAVE, item,
            pendingFileNames = listOf(pendingName))
        fixture.database.operations().put(PendingOperation(operation.id, mediaJson.encodeToString(operation)))
        fixture.storage.recover()
        assertTrue(fixture.files.ownedPendingFiles(listOf(pendingName)).isEmpty())
        assertEquals("retained", fixture.files.open(retained).bufferedReader().use { it.readText() })
        assertTrue(fixture.database.operations().all().isEmpty())
    }

    @Test fun invalidMediaNeverEntersTheLibrary() = runBlocking {
        val invalid = File(fixture.root, "broken.mp4").apply { writeText("not media") }
        val failure = runCatching { fixture.processor.import(invalid, fixture.id, "Broken", fixture.root) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(fixture.database.library().all().isEmpty())
        assertTrue(fixture.database.operations().all().isEmpty())
    }
}
