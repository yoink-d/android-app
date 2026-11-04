package com.maynkudu.yoinkd.data.extraction

import android.content.Context
import com.maynkudu.yoinkd.core.model.OutputSupport
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ExtractorRuntime(private val context: Context, val runner: NativeProcessRunner) {
    private val initialization = Mutex()
    private var initialized = false
    private var support: OutputSupport? = null
    val nativeDirectory: File get() = File(context.applicationInfo.nativeLibraryDir)
    val root: File get() = File(context.noBackupFilesDir, "youtubedl-android")
    val python: File get() = File(nativeDirectory, "libpython.so")
    val ffmpeg: File get() = File(nativeDirectory, "libffmpeg.so")
    val ffprobe: File get() = File(nativeDirectory, "libffprobe.so")
    val bundledExtractor: File get() = File(root, "yt-dlp/yt-dlp")
    @Volatile var activeExtractor: File? = null
        internal set

    val environment: Map<String, String> get() {
        val pythonHome = File(root, "packages/python/usr").absolutePath
        return mapOf(
            "LD_LIBRARY_PATH" to "$pythonHome/lib:${File(root, "packages/ffmpeg/usr/lib").absolutePath}",
            "SSL_CERT_FILE" to "$pythonHome/etc/tls/cert.pem",
            "PYTHONHOME" to pythonHome,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "/system/bin:${nativeDirectory.absolutePath}",
            "PYTHONUNBUFFERED" to "1",
        )
    }

    suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.init(context)
            FFmpeg.init(context)
            check(python.canExecute() && ffmpeg.canExecute() && ffprobe.canExecute()) {
                "The media runtime is unavailable on this device."
            }
        }
        initialized = true
    }

    suspend fun extract(id: String, options: List<String>, directory: File? = null,
        onLine: (String) -> Unit = {}): String {
        initialize()
        val runtimeOptions = listOf("--ignore-config", "--no-cache-dir", "--no-playlist",
            "--js-runtimes", "quickjs:${File(nativeDirectory, "libqjs.so").absolutePath}",
            "--remote-components", "ejs:github", "--ffmpeg-location", ffmpeg.absolutePath,
            "--socket-timeout", "20", "--retries", "2", "--extractor-retries", "1")
        return runner.execute(id, python, listOf(python.absolutePath,
            (activeExtractor ?: bundledExtractor).absolutePath) + runtimeOptions + options,
            environment, directory, onLine).output
    }

    suspend fun process(id: String, arguments: List<String>, onLine: (String) -> Unit = {}): String {
        initialize()
        return runner.execute(id, python, listOf(ffmpeg.absolutePath, "-hide_banner", "-nostdin") + arguments,
            environment, onLine = onLine).output
    }

    suspend fun probe(id: String, arguments: List<String>): String {
        initialize()
        return runner.execute(id, python, listOf(ffprobe.absolutePath, "-v", "error") + arguments,
            environment).output
    }

    suspend fun outputSupport(): OutputSupport {
        support?.let { return it }
        val encoders = process(UUID.randomUUID().toString(), listOf("-encoders"))
            .lineSequence().map { it.trim().split(Regex("\\s+")) }.filter {
                it.size >= 2 && it[0].length == 6 && it[0][0] in "AVS" && it[1] != "="
            }.map { it[1] }.toSet()
        val muxers = process(UUID.randomUUID().toString(), listOf("-muxers"))
            .lineSequence().map { it.trim().split(Regex("\\s+")) }.filter {
                it.size >= 2 && it[0].length <= 3 && 'E' in it[0] && it[1] != "="
            }.flatMap { it[1].split(',') }.toSet()
        return OutputSupport(encoders, muxers).also { support = it }
    }
}
