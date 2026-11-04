package com.maynkudu.yoinkd.data.updates

import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class AppReleasesTest {
    private val repository = "yoink-d/android-app"
    private val applicationId = "com.maynkudu.yoinkd"
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    private fun manifest(code: Long = 2, version: String = "0.2.0-alpha.1") = AppReleaseManifest(1, applicationId, code, version,
        abis.associateWith { ReleaseApk("yoinkd-$version-$it.apk", 6_000_000, "a".repeat(64)) })
    private fun release(manifest: AppReleaseManifest, prerelease: Boolean = true) = GitHubRelease("v${manifest.versionName}",
        manifest.apks.values.map { GitHubAsset(it.filename,
            "https://github.com/$repository/releases/download/v${manifest.versionName}/${it.filename}", it.bytes) }, prerelease = prerelease)

    @Test fun eachArchitectureSelectsItsOwnPrereleaseApk() {
        for (abi in abis) {
            val manifest = manifest()
            val candidate = selectReleaseApk(release(manifest), manifest, repository, applicationId, abi, 1)!!
            assertEquals(abi, candidate.abi)
            assertEquals("yoinkd-0.2.0-alpha.1-$abi.apk", candidate.filename)
            assertEquals(2L, candidate.versionCode)
        }
    }

    @Test fun olderEqualDraftAndMissingArchitectureReleasesAreNotOffered() {
        val manifest = manifest()
        for (installed in listOf(2L, 3L)) assertNull(selectReleaseApk(release(manifest), manifest, repository, applicationId, "x86_64", installed))
        assertNull(selectReleaseApk(release(manifest).copy(draft = true), manifest, repository, applicationId, "x86_64", 1))
        assertNull(selectReleaseApk(release(manifest), manifest.copy(apks = manifest.apks - "x86_64"), repository, applicationId, "x86_64", 1))
    }

    @Test fun anotherApplicationOrMalformedChecksumIsRejected() {
        val manifest = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            selectReleaseApk(release(manifest), manifest.copy(applicationId = "another.app"), repository, applicationId, "x86_64", 1)
        }
        val malformed = manifest.copy(apks = manifest.apks + ("x86_64" to manifest.apks.getValue("x86_64").copy(sha256 = "invalid")))
        assertThrows(IllegalArgumentException::class.java) {
            selectReleaseApk(release(manifest), malformed, repository, applicationId, "x86_64", 1)
        }
    }

    @Test fun missingDuplicateForeignAndWrongSizedAssetsAreRejected() {
        val manifest = manifest()
        val release = release(manifest)
        val selected = release.assets.single { "x86_64" in it.name }
        assertThrows(IllegalStateException::class.java) {
            selectReleaseApk(release.copy(assets = release.assets - selected), manifest, repository, applicationId, "x86_64", 1)
        }
        assertThrows(IllegalStateException::class.java) {
            selectReleaseApk(release.copy(assets = release.assets + selected), manifest, repository, applicationId, "x86_64", 1)
        }
        for (asset in listOf(selected.copy(browser_download_url = "https://example.com/update.apk"), selected.copy(size = 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                selectReleaseApk(release.copy(assets = release.assets - selected + asset), manifest, repository, applicationId, "x86_64", 1)
            }
        }
    }

    @Test fun theReleasesFeedIncludesPrereleasesAndSelectsTheNewestCompatibleVersionCode() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val client = HttpMediaClient(OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .addInterceptor { chain ->
                val destination = server.url(chain.request().url.encodedPath).newBuilder().encodedQuery(chain.request().url.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(destination).build())
            }.build())
        try {
            val stable = manifest(3, "0.2.0")
            val prerelease = manifest(4, "0.3.0-alpha.1")
            fun withMetadata(manifest: AppReleaseManifest, prerelease: Boolean): GitHubRelease {
                val payload = mediaJson.encodeToString(manifest)
                return release(manifest, prerelease).let { it.copy(assets = it.assets + GitHubAsset("yoinkd-update.json",
                    "https://github.com/$repository/releases/download/${it.tag_name}/yoinkd-update.json", payload.toByteArray().size.toLong())) }
            }
            server.enqueue(MockResponse().setBody(mediaJson.encodeToString(listOf(withMetadata(stable, false), withMetadata(prerelease, true)))))
            server.enqueue(MockResponse().setBody(mediaJson.encodeToString(stable)))
            server.enqueue(MockResponse().setBody(mediaJson.encodeToString(prerelease)))
            val candidate = AppReleases(client, repository, applicationId).latest("arm64-v8a", 2)!!
            assertEquals(4L, candidate.versionCode)
            assertEquals("arm64-v8a", candidate.abi)
            assertEquals("/repos/$repository/releases?per_page=20", server.takeRequest().path)
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
            client.client.connectionPool.evictAll()
            client.client.dispatcher.executorService.shutdown()
        }
    }
}
