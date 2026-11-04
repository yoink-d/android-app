package com.maynkudu.yoinkd.support

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.SourceFormat
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.data.JobRepository
import com.maynkudu.yoinkd.data.PreferencesRepository
import com.maynkudu.yoinkd.data.database.AppDatabase
import com.maynkudu.yoinkd.data.extraction.ExtractorRuntime
import com.maynkudu.yoinkd.data.extraction.MediaProbe
import com.maynkudu.yoinkd.data.extraction.MediaProcessor
import com.maynkudu.yoinkd.data.extraction.NativeProcessRunner
import com.maynkudu.yoinkd.data.storage.ArtworkStore
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import com.maynkudu.yoinkd.data.storage.MediaStorage
import com.maynkudu.yoinkd.data.storage.UriFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/** Uses an isolated Room database and private directories. Public files have unique test IDs. */
class NativeFixture : AutoCloseable {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    val id = UUID.randomUUID().toString()
    val root = File(base.cacheDir, "verification-$id").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
    val preferences = PreferencesRepository(database, scope)
    val jobs = JobRepository(database, scope)
    val runtime = ExtractorRuntime(base, NativeProcessRunner())
    val http = HttpMediaClient(OkHttpClient())
    val processor = MediaProcessor(MediaProbe(runtime), runtime, ArtworkStore(http))
    val files = UriFiles(context)
    val storage = MediaStorage(context, database, preferences, files, processor)
    val publicFiles = mutableSetOf<Uri>()

    suspend fun sourceFile(codec: String = "libx264"): File {
        val file = File(root, "source-$codec.mkv")
        if (file.isFile) return file
        runtime.process("fixture-$id", listOf("-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=15",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
            "-c:v", codec, "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-metadata", "title=Native test source", "-metadata", "artist=yoinkd verification", file.absolutePath))
        return file
    }

    fun source() = SourceMedia("https://example.com/native-verification.mkv", "Native test source", "Fixture",
        null, "yoinkd verification", 2.0, null, listOf(SourceFormat("fixture", "mkv", "h264", "aac")))

    suspend fun prepared() = processor.import(sourceFile(), "inspect-$id", "Native test source", root)

    override fun close() {
        publicFiles.forEach { runCatching { files.delete(it) } }
        scope.cancel()
        database.close()
        root.deleteRecursively()
    }
}
