package com.maynkudu.yoinkd.data.storage

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HttpMediaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HttpMediaClient
    private lateinit var directory: File
    private lateinit var target: File
    private val original = ByteArray(150_000) { (it % 251).toByte() }
    private class InterruptedTransfer : Exception()

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        client = HttpMediaClient(OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .readTimeout(5, TimeUnit.SECONDS).build())
        directory = Files.createTempDirectory("yoinkd-transfer-").toFile()
        target = File(directory, "media.mp4")
    }

    @After fun tearDown() {
        server.shutdown()
        client.client.connectionPool.evictAll()
        client.client.dispatcher.executorService.shutdown()
        directory.deleteRecursively()
    }

    private fun response(bytes: ByteArray) = MockResponse().setHeader("ETag", "\"version-1\"")
        .setBody(Buffer().write(bytes))

    private suspend fun interrupt(): Long {
        server.enqueue(response(original))
        val failure = runCatching { client.download(server.url("/media.mp4").toString(), target) { _, _ ->
            throw InterruptedTransfer()
        } }.exceptionOrNull()
        assertTrue(failure is InterruptedTransfer)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
        return target.length().also { assertTrue(it in 1 until original.size.toLong()) }
    }

    @Test fun resumedHttpsTransferUsesTheValidatorAndAppendsTheCorrectBytes() = runBlocking {
        val offset = interrupt()
        server.enqueue(response(original.copyOfRange(offset.toInt(), original.size)).setResponseCode(206)
            .setHeader("Content-Range", "bytes $offset-${original.lastIndex}/${original.size}"))
        var received = 0L
        client.download(server.url("/media.mp4").toString(), target) { bytes, total ->
            received = bytes
            assertEquals(original.size.toLong(), total)
        }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals("\"version-1\"", request.getHeader("If-Range"))
        assertArrayEquals(original, target.readBytes())
        assertEquals(original.size.toLong(), received)
    }

    @Test fun changedContentReplacesThePartialFileWhenTheServerIgnoresRange() = runBlocking {
        interrupt()
        val replacement = ByteArray(90_000) { 7 }
        server.enqueue(response(replacement).setHeader("ETag", "\"version-2\""))
        client.download(server.url("/media.mp4").toString(), target) { _, _ -> }
        assertArrayEquals(replacement, target.readBytes())
    }

    @Test fun unsatisfiedResumeRangeRestartsOnceWithoutRange() = runBlocking {
        interrupt()
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(response(original))
        client.download(server.url("/media.mp4").toString(), target) { _, _ -> }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertArrayEquals(original, target.readBytes())
    }

    @Test fun unsatisfiedInitialRangeFailsWithoutRetryingForever() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(416))
        val failure = runCatching { client.download(server.url("/media.mp4").toString(), target) { _, _ -> } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("416"))
        assertEquals(1, server.requestCount)
    }
}
