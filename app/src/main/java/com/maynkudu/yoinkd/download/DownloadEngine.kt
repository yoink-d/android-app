package com.maynkudu.yoinkd.download

import android.content.Context
import com.maynkudu.yoinkd.core.model.DownloadJob
import com.maynkudu.yoinkd.core.model.DownloadPhase
import com.maynkudu.yoinkd.core.model.DownloadRules
import com.maynkudu.yoinkd.core.model.MediaLink
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.core.model.SourceSelection
import com.maynkudu.yoinkd.data.JobRepository
import com.maynkudu.yoinkd.data.extraction.MediaInspector
import com.maynkudu.yoinkd.data.extraction.MediaProcessor
import com.maynkudu.yoinkd.data.extraction.SourceExtractor
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import com.maynkudu.yoinkd.data.storage.MediaStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private data class ProgressEvent(val phase: DownloadPhase, val bytes: Long = 0,
    val total: Long? = null, val seconds: Double? = null, val duration: Double? = null)

class DownloadEngine(private val context: Context, private val scope: CoroutineScope,
    private val ready: CompletableDeferred<Unit>, private val repository: JobRepository,
    private val inspector: MediaInspector, private val extractor: SourceExtractor,
    private val processor: MediaProcessor, private val http: HttpMediaClient,
    private val storage: MediaStorage, private val network: NetworkMonitor) {
    private val lock = Mutex()
    private val sourceAcquisition = Semaphore(1)
    private val running = mutableMapOf<String, Job>()
    private val acquiring = mutableSetOf<String>()
    private var enabled = false
    private var serviceOwner: String? = null
    private val mutableIdle = MutableStateFlow(true)
    val idle = mutableIdle.asStateFlow()

    suspend fun enqueue(source: SourceMedia, selection: SourceSelection) {
        ready.await()
        val job = DownloadJob(UUID.randomUUID().toString(), source.sourceUrl, source.title,
            System.currentTimeMillis(), selection, source.isDirect, thumbnail = source.thumbnail,
            platform = source.platform, artist = source.artist ?: source.uploader,
            expectedBytes = source.size(selection.kind, selection.formatId)?.bytes, expectedSeconds = source.duration)
        repository.put(job)
        DownloadService.requestStart(context)
    }

    suspend fun start(owner: String) {
        ready.await()
        lock.withLock {
            serviceOwner = owner
            enabled = true
        }
        schedule()
    }

    fun serviceStopped(owner: String): Job = scope.launch { pauseAllOwned(owner, forceSaving = true) }

    private suspend fun schedule() = lock.withLock {
        if (!enabled) {
            updateIdle()
            return@withLock
        }
        while (true) {
            val jobs = repository.all()
            val next = DownloadRules.next(jobs, acquiring.isNotEmpty()) ?: break
            val attempt = UUID.randomUUID().toString()
            repository.change(next.id) { it.copy(phase = DownloadPhase.EXTRACTING, attemptId = attempt,
                message = null, processedSeconds = null) }
            if (!next.direct) acquiring += next.id
            val task = scope.launch(start = CoroutineStart.LAZY) { execute(next.id, attempt) }
            running[next.id] = task
            mutableIdle.value = false
            task.start()
        }
        updateIdle()
    }

    private suspend fun updateIdle() {
        mutableIdle.value = repository.all().none { it.phase == DownloadPhase.QUEUED || it.phase.occupiesSlot }
    }

    private suspend fun execute(id: String, attempt: String) {
        try {
            coroutineScope {
                val progress = Channel<ProgressEvent>(Channel.CONFLATED)
                val progressWriter = launch {
                    for (event in progress) repository.forAttempt(id, attempt) { current ->
                        if (current.phase != event.phase) current else current.copy(
                            receivedBytes = if (event.phase == DownloadPhase.DOWNLOADING) event.bytes else current.receivedBytes,
                            expectedBytes = if (event.phase == DownloadPhase.DOWNLOADING) event.total else current.expectedBytes,
                            processedSeconds = event.seconds ?: current.processedSeconds,
                            expectedSeconds = event.duration ?: current.expectedSeconds)
                    }
                }
                try {
                    while (true) {
                        try {
                            if (!network.online.value) {
                                repository.forAttempt(id, attempt) { it.copy(phase = DownloadPhase.WAITING) }
                                network.awaitOnline()
                            }
                            val job = repository.get(id) ?: return@coroutineScope
                            val directory = storage.workDirectory(id).apply { mkdirs() }
                            val prepared = processor.prepared(directory, attempt) ?: run {
                                suspend fun acquire(): Pair<SourceMedia, List<File>> {
                                    repository.forAttempt(id, attempt) { it.copy(phase = DownloadPhase.EXTRACTING) }
                                    val source = inspector.inspect(MediaLink.parse(job.sourceUrl))
                                    repository.forAttempt(id, attempt) { it.copy(title = source.title,
                                        thumbnail = source.thumbnail, artist = source.artist ?: source.uploader,
                                        expectedSeconds = source.duration, phase = DownloadPhase.DOWNLOADING) }
                                    val files = if (job.direct) {
                                        val extension = source.formats.first().container
                                        val file = File(directory, "direct.$extension")
                                        http.download(job.sourceUrl, file) { bytes, total ->
                                            progress.trySend(ProgressEvent(DownloadPhase.DOWNLOADING, bytes, total))
                                        }
                                        listOf(file)
                                    } else extractor.acquire(attempt, source, job.selection, directory) { bytes, total ->
                                        progress.trySend(ProgressEvent(DownloadPhase.DOWNLOADING, bytes, total))
                                    }
                                    return source to files
                                }
                                val (source, files) = if (job.direct) acquire() else sourceAcquisition.withPermit { acquire() }
                                lock.withLock { acquiring.remove(id) }
                                val transferredBytes = files.sumOf { it.length() }
                                repository.forAttempt(id, attempt) { it.copy(phase = DownloadPhase.PROCESSING,
                                    receivedBytes = transferredBytes, expectedBytes = transferredBytes) }
                                schedule()
                                processor.prepare(attempt, source, job.selection, files, directory) { seconds, duration ->
                                    progress.trySend(ProgressEvent(DownloadPhase.PROCESSING, seconds = seconds, duration = duration))
                                }
                            }
                            val saving = repository.forAttempt(id, attempt) { it.copy(phase = DownloadPhase.SAVING) }
                                ?: throw CancellationException("This attempt was stopped.")
                            storage.commit(prepared, saving)
                            directory.deleteRecursively()
                            break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (network.online.value) throw error
                            repository.forAttempt(id, attempt) { it.copy(phase = DownloadPhase.WAITING,
                                message = "Waiting for a connection. Partial files are retained.") }
                            network.awaitOnline()
                        }
                    }
                } finally {
                    progress.close()
                    withContext(NonCancellable) { progressWriter.cancelAndJoin() }
                }
            }
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                val job = repository.change(id) { current ->
                    if (current.attemptId != attempt || current.phase.terminal) current else current.copy(
                        phase = if (current.phase == DownloadPhase.CANCELLING) DownloadPhase.CANCELLED else DownloadPhase.PAUSED,
                        attemptId = null, message = null)
                }
                if (job?.phase == DownloadPhase.CANCELLED) storage.workDirectory(id).deleteRecursively()
            }
        } catch (error: Exception) {
            repository.forAttempt(id, attempt) { it.copy(phase = DownloadPhase.FAILED,
                message = error.message?.take(800) ?: "Download failed. Retry to continue.") }
        } finally {
            withContext(NonCancellable) {
                lock.withLock { running.remove(id); acquiring.remove(id) }
                schedule()
            }
        }
    }

    suspend fun pause(id: String, cancel: Boolean = false, forceSaving: Boolean = false) {
        val task = lock.withLock {
            val job = repository.get(id) ?: return@withLock null
            val allowed = if (cancel) job.phase.canCancel else job.phase.canPause || (forceSaving && job.phase == DownloadPhase.SAVING)
            if (!allowed) return@withLock null
            val active = running[id]
            repository.change(id) { it.copy(phase = if (active != null) {
                if (cancel) DownloadPhase.CANCELLING else DownloadPhase.PAUSING
            } else if (cancel) DownloadPhase.CANCELLED else DownloadPhase.PAUSED, message = null) }
            active
        }
        task?.cancelAndJoin()
        if (cancel && repository.get(id)?.phase == DownloadPhase.CANCELLED) storage.workDirectory(id).deleteRecursively()
        schedule()
    }

    suspend fun pauseAll(forceSaving: Boolean = false) {
        pauseAllOwned(null, forceSaving)
    }

    private suspend fun pauseAllOwned(owner: String?, forceSaving: Boolean) {
        ready.await()
        val tasks = lock.withLock {
            if (owner != null && serviceOwner != owner) return@withLock emptyList<Job>()
            enabled = false
            if (owner != null) serviceOwner = null
            val jobs = repository.all().filter { it.phase.canPause || (forceSaving && it.phase == DownloadPhase.SAVING) }
            val active = jobs.mapNotNull { running[it.id] }
            jobs.forEach { job ->
                repository.change(job.id) { it.copy(phase = if (running[job.id] != null)
                    DownloadPhase.PAUSING else DownloadPhase.PAUSED, message = null) }
            }
            updateIdle()
            active
        }
        tasks.forEach { it.cancel() }
        tasks.forEach { it.join() }
    }

    suspend fun resume(id: String) {
        repository.change(id) { current ->
            if (current.phase == DownloadPhase.PAUSED || current.phase == DownloadPhase.FAILED || current.phase == DownloadPhase.CANCELLED)
                current.copy(phase = DownloadPhase.QUEUED, attemptId = null, message = null, processedSeconds = null)
            else current
        }
        DownloadService.requestStart(context)
    }

    suspend fun removeHistory(id: String) {
        if (repository.get(id)?.phase?.terminal != true) return
        repository.remove(id)
        storage.workDirectory(id).deleteRecursively()
    }

    suspend fun clearHistory() {
        val terminal = repository.all().filter { it.phase.terminal }
        repository.clearHistory()
        terminal.forEach { storage.workDirectory(it.id).deleteRecursively() }
    }
}
