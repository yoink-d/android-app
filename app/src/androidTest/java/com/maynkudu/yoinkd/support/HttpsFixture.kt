package com.maynkudu.yoinkd.support

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Routes release requests through local TLS while retaining production URL validation. */
class HttpsFixture : AutoCloseable {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val inputs = File(base.filesDir, "device-fixtures")
    val root = File(base.cacheDir, "https-verification-${UUID.randomUUID()}").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    @Volatile var online = true
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    val server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
    val http = HttpMediaClient(OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (!online) throw IOException("Offline verification fixture")
            val original = chain.request().url
            val destination = server.url(original.encodedPath).newBuilder().encodedQuery(original.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(destination).build())
        }.build())

    override fun close() {
        server.shutdown()
        http.client.connectionPool.evictAll()
        http.client.dispatcher.executorService.shutdown()
        root.deleteRecursively()
    }
}
