package com.maynkudu.yoinkd.data.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.maynkudu.yoinkd.BuildConfig
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.extraction.runtimeAbiFromHeader
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import com.maynkudu.yoinkd.data.storage.atomicText
import com.maynkudu.yoinkd.data.storage.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.DataInputStream
import java.util.zip.ZipFile

enum class AppUpdatePhase { IDLE, AVAILABLE, DOWNLOADING, VERIFYING, READY }
data class AppUpdateState(val phase: AppUpdatePhase = AppUpdatePhase.IDLE, val candidate: AppUpdateCandidate? = null,
    val received: Long = 0, val message: String? = null, val error: String? = null, val checking: Boolean = false) {
    val downloading: Boolean get() = phase in setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING)
    val ready: Boolean get() = phase == AppUpdatePhase.READY
    val progress: Float? get() = candidate?.takeIf { phase == AppUpdatePhase.DOWNLOADING }
        ?.let { (received.toFloat() / it.bytes).coerceIn(0f, 1f) }
}

class AppUpdates(private val context: Context, private val http: HttpMediaClient, private val architecture: () -> String) {
    private val repository = BuildConfig.UPDATE_REPOSITORY
    private val releases = AppReleases(http, repository, context.packageName)
    private val root = File(context.cacheDir, "app-updates")
    private val pointer = File(root, "ready.json")
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var restored = false

    suspend fun check() = lock.withLock { withContext(Dispatchers.IO) {
        restore()
        val before = state.value
        mutableState.value = before.copy(checking = true, error = null, message = null)
        try {
            val candidate = releases.latest(architecture(), installed().longVersionCode)
            if (candidate != null) {
                val file = File(root, candidate.filename)
                val ready = file.isFile && runCatching { validateUpdateApk(context, file, candidate, architecture()) }.isSuccess
                mutableState.value = AppUpdateState(if (ready) AppUpdatePhase.READY else AppUpdatePhase.AVAILABLE, candidate,
                    message = if (ready) "Update ready to install." else "An app update is available.")
            } else if (before.ready && before.candidate != null && before.candidate.versionCode > installed().longVersionCode) {
                mutableState.value = before
            } else mutableState.value = AppUpdateState(message = "App is up to date.")
        } catch (cancelled: CancellationException) {
            mutableState.value = before
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = before.copy(error = error.message ?: "Could not check GitHub for app updates.")
        }
    } }

    suspend fun download() = lock.withLock { withContext(Dispatchers.IO) {
        val candidate = state.value.candidate ?: return@withContext
        root.mkdirs()
        val target = File(root, candidate.filename)
        val incoming = File(root, "${candidate.filename}.part")
        try {
            validateCandidate(candidate)
            mutableState.value = state.value.copy(phase = AppUpdatePhase.DOWNLOADING, received = 0, error = null, message = null)
            if (target.isFile && runCatching { validateUpdateApk(context, target, candidate, architecture()) }.isSuccess) {
                atomicText(pointer, mediaJson.encodeToString(candidate))
                mutableState.value = state.value.copy(phase = AppUpdatePhase.READY, message = "Update ready to install.")
                return@withContext
            }
            http.download(candidate.url, incoming) { bytes, _ ->
                require(bytes <= candidate.bytes) { "The app update exceeds its expected size." }
                mutableState.value = state.value.copy(received = bytes)
            }
            mutableState.value = state.value.copy(phase = AppUpdatePhase.VERIFYING)
            try { validateUpdateApk(context, incoming, candidate, architecture()) }
            catch (error: Exception) {
                incoming.delete()
                File(root, "${incoming.name}.resume.json").delete()
                throw error
            }
            check(incoming.renameTo(target)) { "Could not prepare the app update." }
            File(root, "${incoming.name}.resume.json").delete()
            atomicText(pointer, mediaJson.encodeToString(candidate))
            mutableState.value = state.value.copy(phase = AppUpdatePhase.READY, message = "Update ready to install.")
        } catch (cancelled: CancellationException) {
            mutableState.value = state.value.copy(phase = AppUpdatePhase.AVAILABLE, message = "Update download paused.")
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = state.value.copy(phase = AppUpdatePhase.AVAILABLE,
                error = error.message ?: "Could not download this app update.")
        }
    } }

    suspend fun installationIntent(candidate: AppUpdateCandidate): Intent = lock.withLock { withContext(Dispatchers.IO) {
        validateCandidate(candidate)
        val file = File(root, candidate.filename)
        validateUpdateApk(context, file, candidate, architecture())
        Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", file),
            "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } }

    fun installationFailed(message: String) { mutableState.value = state.value.copy(error = message) }

    private fun restore() {
        if (restored) return
        restored = true
        if (!pointer.isFile) return
        try {
            val candidate = mediaJson.decodeFromString<AppUpdateCandidate>(pointer.readText())
            validateCandidate(candidate)
            validateUpdateApk(context, File(root, candidate.filename), candidate, architecture())
            mutableState.value = AppUpdateState(AppUpdatePhase.READY, candidate, message = "Update ready to install.")
        } catch (_: Exception) { pointer.delete() }
    }

    private fun validateCandidate(candidate: AppUpdateCandidate) {
        require(candidate.versionCode in (installed().longVersionCode + 1)..Int.MAX_VALUE && candidate.abi == architecture() &&
            candidate.versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) && candidate.tag == "v${candidate.versionName}" &&
            candidate.filename == "yoinkd-${candidate.versionName}-${candidate.abi}.apk" && candidate.bytes in 1..100_000_000 &&
            candidate.sha256.matches(Regex("[a-f0-9]{64}")) && candidate.url ==
            "https://github.com/$repository/releases/download/${candidate.tag}/${candidate.filename}") { "The app update metadata is invalid." }
    }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
}

/** Checks Android's parsed signing identity as well as the release checksum and ABI. */
@Suppress("DEPRECATION")
internal fun validateUpdateApk(context: Context, file: File, candidate: AppUpdateCandidate, architecture: String) {
    require(file.isFile && file.length() == candidate.bytes && file.inputStream().use(::sha256) == candidate.sha256) {
        "App update checksum verification failed. Retry to download it again."
    }
    val manager = context.packageManager
    val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: error("Android could not verify this APK.")
    require(archive.packageName == context.packageName) { "This update belongs to another app." }
    require(archive.longVersionCode == candidate.versionCode && archive.longVersionCode > installed.longVersionCode &&
        archive.versionName == candidate.versionName) { "This APK does not match the newer release version." }
    require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
        "This app update requires a newer Android version."
    }
    val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray().inputStream().use(::sha256) }.toSet()
    val archiveSigners = archive.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray().inputStream().use(::sha256) }.toSet()
    require(installedSigners.isNotEmpty() && archiveSigners == installedSigners) {
        "This update uses a different signing key. It cannot update the installed app."
    }
    ZipFile(file).use { zip ->
        val nativeFiles = zip.entries().toList().filter { !it.isDirectory && it.name.startsWith("lib/") }.map { it.name }
        val abis = nativeFiles.map { it.split('/').getOrNull(1) }.toSet()
        require(candidate.abi == architecture && abis == setOf(architecture) &&
            listOf("libpython.so", "libqjs.so", "libffmpeg.so", "libffprobe.so").all { "lib/$architecture/$it" in nativeFiles }) {
            "This APK does not match the installed architecture."
        }
        for (name in listOf("libpython.so", "libqjs.so", "libffmpeg.so", "libffprobe.so")) {
            val header = ByteArray(24)
            DataInputStream(zip.getInputStream(zip.getEntry("lib/$architecture/$name"))).use { it.readFully(header) }
            require(runtimeAbiFromHeader(header) == architecture) { "This APK contains a launcher for another architecture." }
        }
    }
}
