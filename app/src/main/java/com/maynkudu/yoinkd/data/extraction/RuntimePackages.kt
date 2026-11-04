package com.maynkudu.yoinkd.data.extraction

import android.content.Context
import android.os.Build
import com.maynkudu.yoinkd.BuildConfig
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import com.maynkudu.yoinkd.data.storage.atomicText
import com.maynkudu.yoinkd.data.storage.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.File
import java.util.UUID

@Serializable
data class RuntimeArtifact(val url: String, val bytes: Long, val sha256: String, val links: Map<String, String> = emptyMap())
@Serializable
data class RuntimeExtractor(val version: String, val url: String, val bytes: Long, val sha256: String)
@Serializable
data class RuntimeArchitecture(val python: RuntimeArtifact, val ffmpeg: RuntimeArtifact)
@Serializable
data class RuntimeManifest(val schema: Int, val version: String, val extractor: RuntimeExtractor,
    val architectures: Map<String, RuntimeArchitecture>)

enum class RuntimeSetupPhase { CHECKING, DOWNLOADING, VERIFYING, PREPARING, READY, FAILED, PAUSED }
data class RuntimeSetupState(val phase: RuntimeSetupPhase = RuntimeSetupPhase.CHECKING,
    val received: Long = 0, val total: Long? = null, val error: String? = null) {
    val ready: Boolean get() = phase == RuntimeSetupPhase.READY
    val running: Boolean get() = phase !in setOf(RuntimeSetupPhase.READY, RuntimeSetupPhase.FAILED, RuntimeSetupPhase.PAUSED)
    val progress: Float? get() = total?.takeIf { it > 0 && phase == RuntimeSetupPhase.DOWNLOADING }
        ?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
    val message: String get() = when (phase) {
        RuntimeSetupPhase.CHECKING -> "Checking media tools"
        RuntimeSetupPhase.DOWNLOADING -> "Downloading media tools"
        RuntimeSetupPhase.VERIFYING -> "Verifying media tools"
        RuntimeSetupPhase.PREPARING -> "Preparing media tools"
        RuntimeSetupPhase.READY -> "Media tools ready"
        RuntimeSetupPhase.FAILED -> "Media setup could not finish"
        RuntimeSetupPhase.PAUSED -> "Media setup paused"
    }
}

private val supportedRuntimeAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
private val runtimeChecksum = Regex("[a-f0-9]{64}")

fun installedRuntimeAbi(nativeDirectory: File): String {
    val header = ByteArray(24)
    DataInputStream(File(nativeDirectory, "libpython.so").inputStream()).use { it.readFully(header) }
    return runtimeAbiFromHeader(header)
}

internal fun runtimeAbiFromHeader(header: ByteArray): String {
    require(header.take(4) == listOf<Byte>(0x7f, 0x45, 0x4c, 0x46) && header[5] == 1.toByte()) {
        "The installed media launcher is invalid."
    }
    val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
    return when (header[4].toInt() to machine) {
        2 to 183 -> "arm64-v8a"
        1 to 40 -> "armeabi-v7a"
        2 to 62 -> "x86_64"
        1 to 3 -> "x86"
        else -> error("Media tools do not support this architecture.")
    }
}

private fun readRuntimeManifest(context: Context): RuntimeManifest {
    val manifest = context.assets.open("runtime-manifest.json").bufferedReader().use {
        mediaJson.decodeFromString<RuntimeManifest>(it.readText())
    }
    require(manifest.schema == 1 && manifest.version.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) &&
        manifest.architectures.keys == supportedRuntimeAbis) { "The media package manifest is invalid." }
    for (architecture in manifest.architectures.values) for (artifact in listOf(architecture.python, architecture.ffmpeg)) {
        require(artifact.url.startsWith("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/") &&
            artifact.bytes in 1..128_000_000 && artifact.sha256.matches(runtimeChecksum)) { "The media package manifest is invalid." }
    }
    val extractor = manifest.extractor
    require(extractor.version.matches(Regex("[A-Za-z0-9._-]+")) && extractor.bytes in 1..25_000_000 &&
        extractor.url == "https://github.com/yt-dlp/yt-dlp/releases/download/${extractor.version}/yt-dlp" &&
        extractor.sha256.matches(runtimeChecksum)) { "The extractor manifest is invalid." }
    return manifest
}

internal fun runtimeEnvironment(directory: File, nativeDirectory: File, cache: File): Map<String, String> {
    val pythonHome = File(directory, "python/usr").absolutePath
    return mapOf("LD_LIBRARY_PATH" to "$pythonHome/lib:${File(directory, "ffmpeg/usr/lib").absolutePath}",
        "SSL_CERT_FILE" to "$pythonHome/etc/tls/cert.pem", "PYTHONHOME" to pythonHome,
        "TMPDIR" to cache.absolutePath, "PATH" to "/system/bin:${nativeDirectory.absolutePath}", "PYTHONUNBUFFERED" to "1")
}

class RuntimePackages(private val context: Context, private val http: HttpMediaClient,
    private val runner: NativeProcessRunner, val manifest: RuntimeManifest = readRuntimeManifest(context),
    private val root: File = File(context.noBackupFilesDir, "media-tools")) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(RuntimeSetupState())
    val state = mutableState.asStateFlow()
    val nativeDirectory: File get() = File(context.applicationInfo.nativeLibraryDir)
    val abi: String by lazy { installedRuntimeAbi(nativeDirectory).also {
        require(it in Build.SUPPORTED_ABIS) { "The installed APK does not match this device." }
    } }
    private val specification: RuntimeArchitecture get() = manifest.architectures.getValue(abi)
    private val identity: String get() = listOf(manifest.version, abi, specification.python.sha256,
        specification.ffmpeg.sha256, manifest.extractor.sha256).joinToString("\n")
    val directory: File get() = File(root, "${manifest.version}-$abi-${identity.byteInputStream().use(::sha256).take(12)}")
    val bootstrapExtractor: File get() = File(directory, "yt-dlp")

    suspend fun awaitReady() {
        val current = state.first { it.ready || !it.running }
        check(current.ready) { current.error ?: "Finish media setup before checking or converting media." }
    }

    fun pauseSetup() {
        if (!state.value.ready) mutableState.value = state.value.copy(phase = RuntimeSetupPhase.PAUSED, error = null)
    }

    suspend fun install() = lock.withLock { withContext(Dispatchers.IO) {
        if (state.value.ready) return@withContext
        mutableState.value = RuntimeSetupState()
        var staging: File? = null
        try {
            require(listOf("libpython.so", "libqjs.so", "libffmpeg.so", "libffprobe.so").all {
                File(nativeDirectory, it).canExecute()
            }) { "The installed APK is missing its media launchers." }
            root.listFiles()?.filter { it.name.startsWith(".staging-") }?.forEach { it.deleteRecursively() }
            val marker = File(directory, "complete.txt")
            if (marker.isFile && marker.readText() == identity && layoutValid(directory)) {
                probe(directory)
                mutableState.value = RuntimeSetupState(RuntimeSetupPhase.READY)
                return@withContext
            }
            val incoming = File(root, "downloads/${directory.name}").apply { mkdirs() }
            val extractor = manifest.extractor
            val artifacts = listOf("python" to specification.python, "ffmpeg" to specification.ffmpeg,
                "yt-dlp" to RuntimeArtifact(extractor.url, extractor.bytes, extractor.sha256))
            val total = artifacts.sumOf { it.second.bytes }
            var completed = 0L
            for ((name, artifact) in artifacts) {
                val file = File(incoming, name)
                if (!verified(file, artifact)) {
                    mutableState.value = RuntimeSetupState(RuntimeSetupPhase.DOWNLOADING, completed, total)
                    http.download(artifact.url, file) { bytes, _ ->
                        require(bytes <= artifact.bytes) { "The media package exceeds its expected size." }
                        mutableState.value = RuntimeSetupState(RuntimeSetupPhase.DOWNLOADING, completed + bytes, total)
                    }
                    mutableState.value = RuntimeSetupState(RuntimeSetupPhase.VERIFYING, completed, total)
                    if (!verified(file, artifact)) {
                        file.delete()
                        File(incoming, "$name.resume.json").delete()
                        error("Media package verification failed. Retry to download it again.")
                    }
                }
                completed += artifact.bytes
            }
            mutableState.value = RuntimeSetupState(RuntimeSetupPhase.PREPARING, total, total)
            staging = File(root, ".staging-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val coroutine = currentCoroutineContext()
            installRuntimeArchive(File(incoming, "python"), File(staging, "python"), specification.python.links) { coroutine.ensureActive() }
            installRuntimeArchive(File(incoming, "ffmpeg"), File(staging, "ffmpeg"), specification.ffmpeg.links) { coroutine.ensureActive() }
            File(incoming, "yt-dlp").copyTo(File(staging, "yt-dlp"))
            require(layoutValid(staging)) { "The media package is incomplete." }
            probe(staging)
            atomicText(File(staging, "complete.txt"), identity)
            if (directory.exists()) check(directory.deleteRecursively())
            check(staging.renameTo(directory)) { "Could not activate the media tools." }
            staging = null
            mutableState.value = RuntimeSetupState(RuntimeSetupPhase.READY)
            incoming.deleteRecursively()
            // The old alpha's unpacked libraries are superseded only after setup succeeds.
            File(context.noBackupFilesDir, "youtubedl-android/packages").deleteRecursively()
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(phase = RuntimeSetupPhase.PAUSED)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(phase = RuntimeSetupPhase.FAILED,
                error = error.message ?: "Connect to the internet and retry media setup.")
        } finally {
            staging?.deleteRecursively()
        }
    } }

    private fun verified(file: File, artifact: RuntimeArtifact): Boolean = file.isFile &&
        file.length() == artifact.bytes && file.inputStream().use(::sha256) == artifact.sha256

    private fun layoutValid(folder: File): Boolean = listOf("python/usr/lib/libpython3.12.so.1.0",
        "python/usr/etc/tls/cert.pem", "ffmpeg/usr/lib/libavcodec.so", "yt-dlp").all { File(folder, it).isFile } &&
        File(folder, "yt-dlp").inputStream().use(::sha256) == manifest.extractor.sha256

    private suspend fun probe(folder: File) {
        val python = File(nativeDirectory, "libpython.so")
        val environment = runtimeEnvironment(folder, nativeDirectory, context.cacheDir)
        val version = runner.execute(UUID.randomUUID().toString(), python,
            listOf(python.absolutePath, File(folder, "yt-dlp").absolutePath, "--ignore-config", "--version"), environment).output.trim()
        require(version == manifest.extractor.version) { "The media extractor version is incorrect." }
        runner.execute(UUID.randomUUID().toString(), python,
            listOf(File(nativeDirectory, "libffmpeg.so").absolutePath, "-hide_banner", "-version"), environment)
    }
}
