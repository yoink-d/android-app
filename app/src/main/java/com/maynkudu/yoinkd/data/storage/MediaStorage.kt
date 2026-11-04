package com.maynkudu.yoinkd.data.storage

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.maynkudu.yoinkd.core.model.DownloadJob
import com.maynkudu.yoinkd.core.model.DownloadPhase
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.PreparedMedia
import com.maynkudu.yoinkd.core.model.managedFileName
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.PreferencesRepository
import com.maynkudu.yoinkd.data.database.AppDatabase
import com.maynkudu.yoinkd.data.database.PendingOperation
import com.maynkudu.yoinkd.data.database.StoredJob
import com.maynkudu.yoinkd.data.database.StoredMedia
import com.maynkudu.yoinkd.data.extraction.MediaProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

class MediaStorage(private val context: Context, private val database: AppDatabase,
    private val preferences: PreferencesRepository, val files: UriFiles, private val processor: MediaProcessor) {
    private val lock = Mutex()
    private val mutableStatus = MutableStateFlow(StorageStatus())
    val status = mutableStatus.asStateFlow()
    val workRoot = File(context.noBackupFilesDir, "media-work")
    private val artworkRoot = File(context.filesDir, "artwork")

    fun workDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        return File(workRoot, id)
    }

    suspend fun commit(prepared: PreparedMedia, job: DownloadJob? = null): MediaItem =
        lock.withLock { withContext(Dispatchers.IO) { commitUnlocked(prepared, job) } }

    private suspend fun commitUnlocked(prepared: PreparedMedia, job: DownloadJob?): MediaItem {
        val id = job?.id ?: UUID.randomUUID().toString()
        database.library().get(id)?.model()?.let { saved ->
            files.open(Uri.parse(saved.uri)).close()
            job?.let { markComplete(it, saved) }
            return saved
        }
        val source = File(prepared.filePath)
        val info = processor.probe.inspect("save-$id", source)
        require(info.kind == prepared.kind) { "This media did not pass validation." }
        val originalHash = source.inputStream().use(::sha256)
        val folder = preferences.current().folderUri
        files.folderName(folder)
        val fileName = managedFileName(id, prepared.title, source.extension.ifBlank { "media" })
        val thumbnail = prepared.thumbnailPath?.let { path ->
            val input = File(path)
            if (!input.isFile) null else {
                artworkRoot.mkdirs()
                val target = File(artworkRoot, "$id.jpg")
                input.copyTo(target, overwrite = true)
                target.absolutePath
            }
        }
        var item = MediaItem(id, prepared.title, "", fileName, info.kind, prepared.mime,
            source.length(), info.duration, System.currentTimeMillis(), prepared.artist, thumbnail,
            prepared.sourceUrl, job?.id, info.audioCodec, info.videoCodec)
        var operation = FileOperation("save-$id", FileOperationKind.SAVE, item,
            attemptId = job?.attemptId, folderUri = folder, pendingFileNames = listOf(fileName))
        persist(operation)
        try {
            val destination = files.create(folder, fileName, prepared.mime)
            item = item.copy(uri = destination.uri.toString())
            operation = operation.copy(item = item, targetCreated = destination.created, pendingFileNames = emptyList())
            persist(operation)
            if (destination.created) {
                val count = files.copy(source.inputStream(), destination.uri)
                require(count == source.length()) { "The saved copy is incomplete." }
            }
            require(files.sha256(destination.uri) == originalHash) {
                "The saved copy did not pass verification. The original checkpoint is retained."
            }
            files.publish(destination.uri)
            database.withTransaction {
                if (job != null) {
                    val current = database.downloads().get(job.id)?.model()
                    require(current != null && current.attemptId == job.attemptId && current.phase == DownloadPhase.SAVING) {
                        "This save belongs to an interrupted download. Resume to continue."
                    }
                }
                database.library().put(StoredMedia.from(item))
                if (job != null) markComplete(job, item)
                database.operations().delete(operation.id)
            }
            return item
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (operation.targetCreated && item.uri.isNotEmpty()) {
                    runCatching { files.delete(Uri.parse(item.uri)) }.getOrElse { throw error }
                }
                database.operations().delete(operation.id)
            }
            throw error
        }
    }

    private suspend fun markComplete(job: DownloadJob, item: MediaItem) {
        val current = database.downloads().get(job.id)?.model() ?: return
        require(current.attemptId == job.attemptId) { "This download attempt has changed." }
        database.downloads().put(StoredJob.from(current.copy(phase = DownloadPhase.COMPLETED,
            storedItemId = item.id, title = item.title, message = null)))
    }

    suspend fun rename(id: String, title: String) = lock.withLock {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Enter a title." }
        database.withTransaction {
            val item = database.library().get(id)?.model() ?: return@withTransaction
            database.library().put(StoredMedia.from(item.copy(title = trimmed)))
        }
    }

    suspend fun delete(id: String) = lock.withLock { withContext(Dispatchers.IO) {
        val item = database.library().get(id)?.model() ?: return@withContext
        val operation = FileOperation("delete-$id", FileOperationKind.DELETE, item)
        persist(operation)
        files.delete(Uri.parse(item.uri))
        database.withTransaction { database.library().delete(id); database.operations().delete(operation.id) }
        item.thumbnailPath?.let { File(it).delete() }
        File(context.cacheDir, "playback/$id").deleteRecursively()
    } }

    suspend fun import(uris: List<Uri>): ImportReport = lock.withLock { withContext(Dispatchers.IO) {
        var imported = 0
        val errors = mutableListOf<String>()
        try {
            uris.distinct().forEachIndexed { index, uri ->
                val id = UUID.randomUUID().toString()
                val directory = workDirectory("import-$id").apply { mkdirs() }
                val name = runCatching { files.displayName(uri) }.getOrDefault("Media")
                mutableStatus.value = StorageStatus(importing = true, name = name, index = index + 1, total = uris.size)
                try {
                    val extension = name.substringAfterLast('.', "media").takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: "media"
                    val local = File(directory, "import.$extension")
                    files.copyToPrivate(uri, local)
                    val prepared = processor.import(local, "import-$id", name.substringBeforeLast('.'), directory)
                    commitUnlocked(prepared, null)
                    imported++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { errors += "$name: ${error.message ?: "Could not import this file."}" }
                finally { directory.deleteRecursively() }
            }
        } finally { mutableStatus.value = StorageStatus() }
        ImportReport(imported, errors)
    } }

    suspend fun relocate(folderUri: String?) = lock.withLock { withContext(Dispatchers.IO) {
        require(database.downloads().all().none { !it.model().phase.terminal }) {
            "Finish or cancel unfinished downloads before changing folders."
        }
        val previous = preferences.current().folderUri
        if (previous == folderUri) return@withContext
        val name = files.folderName(folderUri)
        var operation = FileOperation(UUID.randomUUID().toString(), FileOperationKind.RELOCATE,
            folderUri = folderUri, folderName = name)
        persist(operation)
        var committed = false
        mutableStatus.value = StorageStatus(moving = true)
        try {
            val items = database.library().all().map { it.model() }
            for ((index, item) in items.withIndex()) {
                mutableStatus.value = StorageStatus(moving = true, name = item.title, index = index + 1, total = items.size)
                val original = Uri.parse(item.uri)
                val hash = files.sha256(original)
                operation = operation.copy(pendingFileNames = listOf(item.fileName))
                persist(operation)
                val destination = files.create(folderUri, item.fileName, item.mime)
                require(destination.created) {
                    "A file with the same name exists in the selected folder. Choose a different folder."
                }
                val entry = MoveEntry(item.id, item.uri, destination.uri.toString(), destination.created)
                operation = operation.copy(entries = operation.entries + entry, pendingFileNames = emptyList())
                persist(operation)
                if (destination.created) require(files.copy(files.open(original), destination.uri) == item.bytes) {
                    "The moved copy is incomplete."
                }
                require(files.sha256(destination.uri) == hash) {
                    "A different file with the same name exists in the selected folder."
                }
                files.publish(destination.uri)
            }
            database.withTransaction {
                operation.entries.forEach { entry ->
                    val item = database.library().get(entry.itemId)?.model() ?: error("The library changed during relocation.")
                    database.library().put(StoredMedia.from(item.copy(uri = entry.targetUri)))
                }
                preferences.update { it.copy(folderUri = folderUri, folderName = name) }
                persist(operation.copy(committed = true))
            }
            committed = true
            operation.entries.forEach { files.delete(Uri.parse(it.originalUri)) }
            database.operations().delete(operation.id)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (!committed) {
                    operation.entries.filter { it.created }.forEach { files.delete(Uri.parse(it.targetUri)) }
                    database.operations().delete(operation.id)
                }
            }
            throw error
        } finally { mutableStatus.value = StorageStatus() }
    } }

    // Recover only operations explicitly recorded by this app, preserving unrelated folder contents.
    suspend fun recover() = lock.withLock { withContext(Dispatchers.IO) {
        for (row in database.operations().all()) {
            val operation = mediaJson.decodeFromString<FileOperation>(row.payload)
            when (operation.kind) {
                FileOperationKind.SAVE -> operation.item?.let { item ->
                    if (database.library().get(item.id) != null && item.uri.isNotEmpty()) files.publish(Uri.parse(item.uri))
                    else if (operation.targetCreated && item.uri.isNotEmpty()) files.delete(Uri.parse(item.uri))
                }
                FileOperationKind.DELETE -> operation.item?.let { item ->
                    files.delete(Uri.parse(item.uri))
                    database.library().delete(item.id)
                    item.thumbnailPath?.let { File(it).delete() }
                }
                FileOperationKind.RELOCATE -> {
                    val cleanup = if (operation.committed) operation.entries else operation.entries.filter { it.created }
                    cleanup.forEach { files.delete(Uri.parse(if (operation.committed) it.originalUri else it.targetUri)) }
                }
            }
            if (operation.folderUri == null && operation.pendingFileNames.isNotEmpty())
                files.ownedPendingFiles(operation.pendingFileNames).forEach(files::delete)
            database.operations().delete(operation.id)
        }
        database.downloads().all().filter { it.model().phase == DownloadPhase.COMPLETED || it.model().phase == DownloadPhase.CANCELLED }
            .forEach { workDirectory(it.id).deleteRecursively() }
        workRoot.listFiles()?.filter { it.name.startsWith("import-") }?.forEach { it.deleteRecursively() }
    } }

    private suspend fun persist(operation: FileOperation) = database.operations().put(
        PendingOperation(operation.id, mediaJson.encodeToString(operation)))
}
