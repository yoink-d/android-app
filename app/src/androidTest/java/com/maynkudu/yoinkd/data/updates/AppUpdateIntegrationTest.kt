package com.maynkudu.yoinkd.data.updates

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.extraction.installedRuntimeAbi
import com.maynkudu.yoinkd.data.storage.sha256
import com.maynkudu.yoinkd.support.HttpsFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdateIntegrationTest {
    private lateinit var fixture: HttpsFixture
    private lateinit var abi: String
    private lateinit var root: File
    private var oldPointer: ByteArray? = null
    private val version = "0.2.0-verification"
    private val repository = "yoink-d/android-app"

    @Before fun setUp() {
        fixture = HttpsFixture()
        abi = installedRuntimeAbi(File(fixture.base.applicationInfo.nativeLibraryDir))
        root = File(fixture.base.cacheDir, "app-updates")
        oldPointer = File(root, "ready.json").takeIf { it.isFile }?.readBytes()
        File(root, "ready.json").delete()
    }

    @After fun tearDown() {
        root.listFiles()?.filter { it.name.startsWith("yoinkd-$version-") || it.name == "ready.json" }?.forEach { it.delete() }
        oldPointer?.let { File(root, "ready.json").writeBytes(it) }
        fixture.close()
    }

    @Suppress("DEPRECATION")
    private fun candidate(name: String, checksum: String? = null): AppUpdateCandidate {
        val file = File(fixture.inputs, "$name.apk")
        val archive = fixture.base.packageManager.getPackageArchiveInfo(file.absolutePath, 0)!!
        val releaseVersion = archive.versionName!!
        val filename = "yoinkd-$releaseVersion-$abi.apk"
        return AppUpdateCandidate("v$releaseVersion", archive.longVersionCode, releaseVersion, abi, filename,
            "https://github.com/$repository/releases/download/v$releaseVersion/$filename", file.length(),
            checksum ?: file.inputStream().use(::sha256))
    }

    private fun offer(candidate: AppUpdateCandidate) {
        val manifest = AppReleaseManifest(1, fixture.base.packageName, candidate.versionCode, candidate.versionName,
            mapOf(abi to ReleaseApk(candidate.filename, candidate.bytes, candidate.sha256)))
        val payload = mediaJson.encodeToString(manifest)
        val release = GitHubRelease(candidate.tag, listOf(
            GitHubAsset(candidate.filename, candidate.url, candidate.bytes),
            GitHubAsset("yoinkd-update.json", "https://github.com/$repository/releases/download/${candidate.tag}/yoinkd-update.json",
                payload.toByteArray().size.toLong())), prerelease = true)
        fixture.server.enqueue(MockResponse().setBody(mediaJson.encodeToString(listOf(release))))
        fixture.server.enqueue(MockResponse().setBody(payload))
        fixture.server.enqueue(MockResponse().setBody(File(fixture.inputs, "same-key.apk").inputStream().use { Buffer().readFrom(it) }))
    }

    @Test fun aSameKeyUpdateDownloadsAndRestoresOfflineBeforeAndroidInstallation() = runBlocking {
        val candidate = candidate("same-key")
        offer(candidate)
        val updates = AppUpdates(fixture.base, fixture.http) { abi }
        updates.check()
        assertEquals(AppUpdatePhase.AVAILABLE, updates.state.value.phase)
        updates.download()
        assertEquals(updates.state.value.error, AppUpdatePhase.READY, updates.state.value.phase)
        val intent = updates.installationIntent(candidate)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(candidate.sha256, fixture.base.contentResolver.openInputStream(intent.data!!)!!.use(::sha256))
        fixture.online = false
        val reopened = AppUpdates(fixture.base, fixture.http) { abi }
        reopened.check()
        assertEquals(AppUpdatePhase.READY, reopened.state.value.phase)
        assertNotNull(reopened.state.value.error)
        assertNotNull(reopened.installationIntent(candidate).data)
        assertEquals(3, fixture.server.requestCount)
    }

    @Test fun anIncorrectDownloadChecksumNeverBecomesAnInstallableUpdate() = runBlocking {
        val candidate = candidate("same-key", "0".repeat(64))
        offer(candidate)
        val updates = AppUpdates(fixture.base, fixture.http) { abi }
        updates.check()
        updates.download()
        assertEquals(AppUpdatePhase.AVAILABLE, updates.state.value.phase)
        assertTrue(updates.state.value.error.orEmpty().contains("checksum verification failed"))
        assertFalse(File(root, "${candidate.filename}.part").exists())
        assertFalse(File(root, "ready.json").exists())
        assertNotNull(runCatching { updates.installationIntent(candidate) }.exceptionOrNull())
    }

    @Test fun anotherSigningKeyCannotUpdateTheInstalledApp() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            validateUpdateApk(fixture.base, File(fixture.inputs, "wrong-key.apk"), candidate("wrong-key"), abi)
        }
        assertTrue(failure.message.orEmpty().contains("different signing key"))
    }

    @Test fun foreignOlderAndWrongArchitectureApksAreRejected() {
        for ((name, message) in listOf("wrong-package" to "another app", "older" to "newer release version",
            "wrong-architecture" to "installed architecture")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                validateUpdateApk(fixture.base, File(fixture.inputs, "$name.apk"), candidate(name), abi)
            }
            assertTrue("$name: ${failure.message}", failure.message.orEmpty().contains(message))
        }
    }

    @Test fun aRateLimitedCheckKeepsTheAppUsableAndCanRetry() = runBlocking {
        fixture.server.enqueue(MockResponse().setResponseCode(403).setBody("API rate limit exceeded"))
        val updates = AppUpdates(fixture.base, fixture.http) { abi }
        updates.check()
        assertEquals(AppUpdatePhase.IDLE, updates.state.value.phase)
        assertFalse(updates.state.value.checking)
        assertTrue(updates.state.value.error.orEmpty().contains("403"))
        fixture.server.enqueue(MockResponse().setBody("[]"))
        updates.check()
        assertNull(updates.state.value.error)
        assertEquals("App is up to date.", updates.state.value.message)
    }
}
