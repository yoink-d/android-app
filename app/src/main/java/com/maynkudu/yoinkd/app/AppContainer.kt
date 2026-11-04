package com.maynkudu.yoinkd.app

import android.content.Context
import androidx.room.Room
import com.maynkudu.yoinkd.data.JobRepository
import com.maynkudu.yoinkd.data.LibraryRepository
import com.maynkudu.yoinkd.data.PreferencesRepository
import com.maynkudu.yoinkd.data.database.AppDatabase
import com.maynkudu.yoinkd.data.extraction.ExtractorRuntime
import com.maynkudu.yoinkd.data.extraction.ExtractorUpdates
import com.maynkudu.yoinkd.data.extraction.MediaInspector
import com.maynkudu.yoinkd.data.extraction.MediaProbe
import com.maynkudu.yoinkd.data.extraction.MediaProcessor
import com.maynkudu.yoinkd.data.extraction.NativeProcessRunner
import com.maynkudu.yoinkd.data.extraction.SourceExtractor
import com.maynkudu.yoinkd.data.storage.ArtworkStore
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import com.maynkudu.yoinkd.data.storage.MediaStorage
import com.maynkudu.yoinkd.data.storage.UriFiles
import com.maynkudu.yoinkd.download.DownloadEngine
import com.maynkudu.yoinkd.download.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val error = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, cause -> report(cause) })
    val ready = CompletableDeferred<Unit>()
    val initialized = MutableStateFlow(false)
    val database = Room.databaseBuilder(context, AppDatabase::class.java, "yoinkd.db").build()
    val preferences = PreferencesRepository(database, scope)
    val jobs = JobRepository(database, scope)
    val http = HttpMediaClient(OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build())
    val runtime = ExtractorRuntime(context, NativeProcessRunner())
    private val sourceExtractor = SourceExtractor(runtime)
    val inspector = MediaInspector(sourceExtractor, http)
    private val processor = MediaProcessor(MediaProbe(runtime), runtime, ArtworkStore(http))
    val storage = MediaStorage(context, database, preferences, UriFiles(context), processor)
    val library = LibraryRepository(context, database, scope, storage, processor)
    val downloads = DownloadEngine(context, scope, ready, jobs, inspector, sourceExtractor, processor,
        http, storage, NetworkMonitor(context))
    val updates = ExtractorUpdates(context, runtime, http)
    val artwork = ArtworkThemeController(scope, http)

    init {
        scope.launch {
            try { storage.recover(); jobs.restore() }
            catch (cause: Exception) { report(cause) }
            finally { initialized.value = true; ready.complete(Unit) }
            try { updates.restore() } catch (cause: Exception) { report(cause) }
        }
    }

    fun report(cause: Throwable) {
        if (cause is CancellationException) return
        error.value = cause.message?.take(800) ?: "This operation could not finish. Try again."
        android.util.Log.e("Yoinkd", "Operation failed", cause)
    }
}
