package com.maynkudu.yoinkd.data.extraction

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ProcessResult(val output: String, val exitCode: Int)

class NativeProcessException(message: String) : Exception(message)

class NativeProcessRunner {
    private data class OwnedProcess(val process: Process, val group: AtomicInteger = AtomicInteger(0))
    private val processes = ConcurrentHashMap<String, OwnedProcess>()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "yoinkd-native").apply { isDaemon = true }
    }

    suspend fun execute(
        id: String, python: File, command: List<String>, environment: Map<String, String>,
        directory: File? = null, onLine: (String) -> Unit = {},
    ): ProcessResult {
        val finished = CompletableDeferred<Unit>()
        val requestProcess = AtomicReference<OwnedProcess?>()
        try {
            return suspendCancellableCoroutine { continuation ->
                executor.submit {
                    try {
                        if (!continuation.isActive) return@submit
                        // Every native request owns a process group, including its JS/FFmpeg children.
                        val bootstrap = "import os,sys;os.setsid();print('__YOINKD_PID:'+str(os.getpid()),flush=True);os.execv(sys.argv[1],sys.argv[1:])"
                        val builder = ProcessBuilder(listOf(python.absolutePath, "-u", "-c", bootstrap) + command)
                            .redirectErrorStream(true)
                        builder.environment().putAll(environment)
                        directory?.let { builder.directory(it) }
                        val running = OwnedProcess(builder.start())
                        requestProcess.set(running)
                        check(processes.putIfAbsent(id, running) == null) { "This native request is already running." }
                        if (!continuation.isActive) terminate(running)
                        val output = StringBuilder()
                        running.process.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                if (line.startsWith("__YOINKD_PID:")) {
                                    running.group.set(line.substringAfter(':').toIntOrNull() ?: 0)
                                    if (!continuation.isActive) terminate(running)
                                } else {
                                    if (output.length < 12_000_000) output.appendLine(line)
                                    if (continuation.isActive) onLine(line)
                                }
                            }
                        }
                        val exit = running.process.waitFor()
                        if (continuation.isActive) {
                            if (exit == 0) continuation.resume(ProcessResult(output.toString(), exit))
                            else continuation.resumeWithException(NativeProcessException(
                                output.lines().filter { it.startsWith("ERROR:") }.lastOrNull()
                                    ?.removePrefix("ERROR:")?.trim()
                                    ?: output.lines().filter { it.isNotBlank() }.takeLast(3).joinToString("\n").take(600)
                                        .ifBlank { "The media engine stopped. Retry to continue." }))
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        requestProcess.get()?.let { process ->
                            processes.remove(id, process)
                            terminate(process)
                        }
                        finished.complete(Unit)
                    }
                }
                continuation.invokeOnCancellation {
                    requestProcess.get()?.let(::terminate)
                }
            }
        } finally {
            withContext(NonCancellable) { finished.await() }
        }
    }

    fun cancel(id: String) { processes[id]?.let(::terminate) }

    private fun terminate(owned: OwnedProcess) {
        val group = owned.group.get()
        if (group > 0) runCatching { Os.kill(-group, OsConstants.SIGKILL) }
        runCatching { owned.process.destroyForcibly() }
    }
}
