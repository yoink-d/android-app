package com.maynkudu.yoinkd.data.extraction

import android.content.Context
import com.maynkudu.yoinkd.core.model.mediaJson
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

data class ExtractorUpdateState(val installed: String = "Loading", val latest: String? = null,
    val checking: Boolean = false, val updating: Boolean = false, val progress: Float? = null,
    val message: String? = null, val error: String? = null, val canRollback: Boolean = false)

@Serializable
private data class ExtractorPointer(val current: String, val previous: String? = null)
@Serializable
private data class ExtractorManifest(val version: String, val sha256: String)
private data class ExtractorRelease(val version: String, val payload: String, val checksums: String)
private const val BUNDLED_GENERATION = "bundled"

class ExtractorUpdates(context: Context, private val runtime: ExtractorRuntime, private val http: HttpMediaClient) {
    private val root = File(context.noBackupFilesDir, "extractor-releases")
    private val pointerFile = File(root, "active.json")
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(ExtractorUpdateState())
    val state = mutableState.asStateFlow()
    private var release: ExtractorRelease? = null

    suspend fun restore() = lock.withLock { withContext(Dispatchers.IO) {
        runtime.initialize()
        var pointer = runCatching { mediaJson.decodeFromString<ExtractorPointer>(pointerFile.readText()) }.getOrNull()
        if (pointer != null) {
            val current = verified(pointer.current)
            if (current != null) activate(current)
            else {
                val fallback = pointer.previous?.let { verified(it) }
                activate(fallback ?: runtime.bundledExtractor)
                pointer = if (fallback != null) ExtractorPointer(generation()) else null
                if (pointer != null) atomicText(pointerFile, mediaJson.encodeToString(pointer))
                else pointerFile.delete()
                mutableState.value = mutableState.value.copy(error = "The installed update failed verification. The previous extractor was restored.")
            }
        }
        mutableState.value = mutableState.value.copy(installed = version(runtime.activeExtractor ?: runtime.bundledExtractor),
            canRollback = pointer?.previous?.let { verified(it) } != null)
    } }

    suspend fun check() = lock.withLock {
        mutableState.value = mutableState.value.copy(checking = true, error = null, message = null)
        try {
            release = fetchRelease()
            mutableState.value = mutableState.value.copy(latest = release!!.version,
                message = if (release!!.version == mutableState.value.installed) "Extractor is up to date." else "An extractor update is available.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message ?: "Could not check for updates.") }
        finally { mutableState.value = mutableState.value.copy(checking = false) }
    }

    suspend fun install() = lock.withLock { withContext(Dispatchers.IO) {
        mutableState.value = mutableState.value.copy(updating = true, progress = null, error = null, message = "Downloading extractor")
        try {
            runtime.initialize()
            val candidate = release ?: fetchRelease().also { release = it }
            if (candidate.version == mutableState.value.installed) {
                mutableState.value = mutableState.value.copy(message = "Extractor is up to date.", latest = candidate.version)
                return@withContext
            }
            require(candidate.version.matches(Regex("[A-Za-z0-9._-]+"))) { "The release has an invalid version." }
            val checksums = http.bytes(candidate.checksums, 1_000_000).decodeToString()
            val expected = checksums.lineSequence().mapNotNull { line ->
                Regex("([0-9a-fA-F]{64})\\s+\\*?yt-dlp").matchEntire(line.trim())?.groupValues?.get(1)?.lowercase()
            }.singleOrNull() ?: error("The release does not provide a unique SHA-256 checksum.")
            val directory = File(root, candidate.version).apply { mkdirs() }
            val download = File(directory, "payload.new")
            http.download(candidate.payload, download) { bytes, total ->
                require(bytes <= 25_000_000) { "The extractor exceeds the update size limit." }
                mutableState.value = mutableState.value.copy(progress = total?.takeIf { it > 0 }
                    ?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) })
            }
            mutableState.value = mutableState.value.copy(message = "Verifying extractor", progress = null)
            require(download.inputStream().use(::sha256) == expected) { "Extractor checksum verification failed. The installed version is retained." }
            require(version(download) == candidate.version) { "The extractor version does not match its release." }
            runtime.runner.execute(UUID.randomUUID().toString(), runtime.python,
                listOf(runtime.python.absolutePath, download.absolutePath, "--ignore-config", "--list-extractors"), runtime.environment)
            val final = File(directory, "yt-dlp")
            check(download.renameTo(final)) { "Could not stage the verified extractor." }
            atomicText(File(directory, "manifest.json"), mediaJson.encodeToString(ExtractorManifest(candidate.version, expected)))
            val previous = generation()
            atomicText(pointerFile, mediaJson.encodeToString(ExtractorPointer(candidate.version, previous)))
            activate(final)
            mutableState.value = mutableState.value.copy(installed = candidate.version, latest = candidate.version,
                message = "Extractor updated.", canRollback = true)
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(message = "Update cancelled. The installed version is retained.")
            throw cancelled
        } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message ?: "Could not update the extractor.") }
        finally { mutableState.value = mutableState.value.copy(updating = false, progress = null) }
    } }

    suspend fun rollback() = lock.withLock { withContext(Dispatchers.IO) {
        try {
            val pointer = mediaJson.decodeFromString<ExtractorPointer>(pointerFile.readText())
            val previous = pointer.previous ?: error("No previous extractor is available.")
            val payload = verified(previous) ?: error("The previous extractor did not pass verification.")
            atomicText(pointerFile, mediaJson.encodeToString(ExtractorPointer(previous, pointer.current)))
            activate(payload)
            mutableState.value = mutableState.value.copy(installed = version(payload), message = "Previous extractor restored.",
                error = null, canRollback = verified(pointer.current) != null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message) }
    } }

    private suspend fun verified(version: String): File? {
        if (version == BUNDLED_GENERATION) return runtime.bundledExtractor.takeIf { it.isFile }
        if (!version.matches(Regex("[A-Za-z0-9._-]+"))) return null
        return try {
            val directory = File(root, version)
            val manifest = mediaJson.decodeFromString<ExtractorManifest>(File(directory, "manifest.json").readText())
            val payload = File(directory, "yt-dlp")
            if (manifest.version != version || payload.inputStream().use(::sha256) != manifest.sha256 || version(payload) != version) null
            else payload
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun generation(): String = runtime.activeExtractor?.parentFile?.name ?: BUNDLED_GENERATION

    private fun activate(file: File) {
        runtime.activeExtractor = file.takeUnless { it == runtime.bundledExtractor }
    }

    private suspend fun version(file: File): String = runtime.runner.execute(UUID.randomUUID().toString(), runtime.python,
        listOf(runtime.python.absolutePath, file.absolutePath, "--ignore-config", "--version"), runtime.environment)
        .output.trim().lineSequence().last()

    private suspend fun fetchRelease(): ExtractorRelease {
        val json = mediaJson.parseToJsonElement(http.bytes("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest", 1_000_000).decodeToString()).jsonObject
        val assets = (json["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun asset(name: String): String = assets.singleOrNull { it.text("name") == name }?.text("browser_download_url")
            ?.takeIf { it.startsWith("https://github.com/yt-dlp/yt-dlp/releases/download/") }
            ?: error("The official release is missing $name.")
        return ExtractorRelease(json.text("tag_name") ?: error("The release has no version."), asset("yt-dlp"), asset("SHA2-256SUMS"))
    }
}
