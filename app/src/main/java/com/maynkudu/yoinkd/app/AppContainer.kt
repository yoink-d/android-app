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
import com.maynkudu.yoinkd.data.extraction.RuntimePackages
import com.maynkudu.yoinkd.data.extraction.SourceExtractor
import com.maynkudu.yoinkd.data.updates.AppUpdates
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
import kotlinx.coroutines.Job
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
    private val runner = NativeProcessRunner()
    val runtimePackages = RuntimePackages(context, http, runner)
    val runtime = ExtractorRuntime(context, runner, runtimePackages)
    val updates = ExtractorUpdates(context, runtime, http)
    val appUpdates = AppUpdates(context, http) { runtimePackages.abi }
    private val sourceExtractor = SourceExtractor(runtime, updates)
    val inspector = MediaInspector(sourceExtractor, http)
    private val processor = MediaProcessor(MediaProbe(runtime), runtime, ArtworkStore(http))
    val storage = MediaStorage(context, database, preferences, UriFiles(context), processor)
    val library = LibraryRepository(context, database, scope, storage, processor)
    val downloads = DownloadEngine(context, scope, ready, jobs, inspector, sourceExtractor, processor,
        http, storage, NetworkMonitor(context))
    val artwork = ArtworkThemeController(scope, http)
    private var setupJob: Job? = null
    private var appCheckJob: Job? = null
    private var appDownloadJob: Job? = null
    private var extractorUpdateJob: Job? = null
    val canCancelExtractorUpdate: Boolean get() = extractorUpdateJob?.isActive == true

    init {
        scope.launch {
            try { storage.recover(); jobs.restore() }
            catch (cause: Exception) { report(cause) }
            finally { initialized.value = true; ready.complete(Unit) }
        }
        prepareMediaTools()
    }

    fun onForeground() {
        if (runtimePackages.state.value.phase != com.maynkudu.yoinkd.data.extraction.RuntimeSetupPhase.PAUSED) prepareMediaTools()
        checkAppUpdates()
        if (runtimePackages.state.value.ready) refreshExtractor()
    }

    fun prepareMediaTools() {
        if (setupJob?.isActive == true || runtimePackages.state.value.ready) return
        setupJob = scope.launch {
            runtimePackages.install()
            if (runtimePackages.state.value.ready) { updates.restore(); refreshExtractor() }
        }
    }
    fun pauseMediaSetup() { setupJob?.cancel(); runtimePackages.pauseSetup() }
    fun checkAppUpdates() {
        if (appCheckJob?.isActive == true || appDownloadJob?.isActive == true) return
        appCheckJob = scope.launch { appUpdates.check() }
    }
    fun downloadAppUpdate() {
        if (appDownloadJob?.isActive != true) appDownloadJob = scope.launch { appUpdates.download() }
    }
    fun pauseAppUpdate() { appDownloadJob?.cancel() }
    private fun refreshExtractor() {
        if (extractorUpdateJob?.isActive != true) extractorUpdateJob = scope.launch { updates.refreshAutomatically() }
    }
    fun updateExtractor() {
        if (extractorUpdateJob?.isActive != true) extractorUpdateJob = scope.launch { updates.install() }
    }
    fun cancelExtractorUpdate() { extractorUpdateJob?.cancel() }

    fun report(cause: Throwable) {
        if (cause is CancellationException) return
        error.value = cause.message?.take(800) ?: "This operation could not finish. Try again."
        android.util.Log.e("Yoinkd", "Operation failed", cause)
    }
}
