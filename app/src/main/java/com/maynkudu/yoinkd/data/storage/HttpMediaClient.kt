package com.maynkudu.yoinkd.data.storage

import com.maynkudu.yoinkd.core.model.mediaJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class ResumeToken(val url: String, val validator: String, val total: Long?)

class HttpMediaClient(val client: OkHttpClient) {
    suspend fun <T> read(request: Request, consume: (Response) -> T): T {
        val finished = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                        finished.complete(Unit)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use(consume)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                })
            }
        } finally {
            withContext(NonCancellable) { finished.await() }
        }
    }

    suspend fun bytes(url: String, limit: Int = 5_000_000, headers: Map<String, String> = emptyMap()): ByteArray {
        require(url.startsWith("https://")) { "Use an HTTPS address." }
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return read(request) { response ->
            check(response.isSuccessful && response.request.url.isHttps) { "Could not retrieve this file. HTTP ${response.code}." }
            val body = response.body ?: error("The server returned an empty response.")
            require(body.contentLength() <= limit) { "This file exceeds the download limit." }
            body.byteStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "This file exceeds the download limit." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    suspend fun download(url: String, target: File, onProgress: (Long, Long?) -> Unit) {
        require(url.startsWith("https://"))
        val tokenFile = File(target.parentFile, "${target.name}.resume.json")
        val token = runCatching { mediaJson.decodeFromString<ResumeToken>(tokenFile.readText()) }.getOrNull()
        val offset = target.length().takeIf { token?.url == url && it > 0 } ?: 0
        val builder = Request.Builder().url(url)
        if (offset > 0) builder.header("Range", "bytes=$offset-").header("If-Range", token!!.validator)
        val mustRestart = read(builder.build()) { response ->
            if (response.code == 416 && offset > 0) return@read true
            check(response.isSuccessful && response.request.url.isHttps) { "Download failed. HTTP ${response.code}." }
            val body = response.body ?: error("The server returned no media.")
            val append = offset > 0 && response.code == 206
            val range = response.header("Content-Range")?.let {
                Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it)
            }
            if (append) require(range?.groupValues?.get(1)?.toLongOrNull() == offset) {
                "The server returned an invalid resume range. Retry to restart."
            }
            val start = if (append) offset else 0L
            val total = if (append) range?.groupValues?.get(3)?.toLongOrNull()
                else body.contentLength().takeIf { it > 0 }
            val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                ?: response.header("Last-Modified")
            if (validator != null) atomicText(tokenFile, mediaJson.encodeToString(ResumeToken(url, validator, total)))
            else tokenFile.delete()
            target.parentFile?.mkdirs()
            var received = start
            FileOutputStream(target, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(received, total)
                    }
                }
                output.fd.sync()
            }
            require(received > 0 && (total == null || received == total)) { "This transfer ended before all bytes arrived." }
            false
        }
        if (mustRestart) {
            target.delete(); tokenFile.delete()
            download(url, target, onProgress)
        }
    }
}

fun atomicText(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.new")
    FileOutputStream(temporary).use { output -> output.write(text.toByteArray()); output.fd.sync() }
    check(temporary.renameTo(file)) { "Could not save the media checkpoint." }
}
