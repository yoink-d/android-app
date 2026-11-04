package com.maynkudu.yoinkd.data.updates

import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.storage.HttpMediaClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class ReleaseApk(val filename: String, val bytes: Long, val sha256: String)
@Serializable
data class AppReleaseManifest(val schema: Int, val applicationId: String, val versionCode: Long,
    val versionName: String, val apks: Map<String, ReleaseApk>)
@Serializable
internal data class GitHubAsset(val name: String, val browser_download_url: String, val size: Long)
@Serializable
internal data class GitHubRelease(val tag_name: String, val assets: List<GitHubAsset>,
    val draft: Boolean = false, val prerelease: Boolean = false)
@Serializable
data class AppUpdateCandidate(val tag: String, val versionCode: Long, val versionName: String, val abi: String,
    val filename: String, val url: String, val bytes: Long, val sha256: String)

internal fun selectReleaseApk(release: GitHubRelease, manifest: AppReleaseManifest, repository: String,
    applicationId: String, abi: String, installedVersion: Long): AppUpdateCandidate? {
    if (release.draft || manifest.versionCode <= installedVersion) return null
    require(manifest.schema == 1 && manifest.applicationId == applicationId && manifest.versionCode in 1..Int.MAX_VALUE &&
        manifest.versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) &&
        release.tag_name == "v${manifest.versionName}") { "The app release metadata is invalid." }
    val apk = manifest.apks[abi] ?: return null
    require(apk.filename == "yoinkd-${manifest.versionName}-$abi.apk" && apk.bytes in 1..100_000_000 &&
        apk.sha256.matches(Regex("[a-f0-9]{64}"))) { "The app release APK metadata is invalid." }
    val asset = release.assets.singleOrNull { it.name == apk.filename }
        ?: error("The app release is missing its $abi APK.")
    require(asset.size == apk.bytes &&
        asset.browser_download_url == "https://github.com/$repository/releases/download/${release.tag_name}/${apk.filename}") {
        "The app release asset does not match its metadata."
    }
    return AppUpdateCandidate(release.tag_name, manifest.versionCode, manifest.versionName, abi,
        apk.filename, asset.browser_download_url, apk.bytes, apk.sha256)
}

class AppReleases(private val http: HttpMediaClient, private val repository: String, private val applicationId: String) {
    suspend fun latest(abi: String, installedVersion: Long): AppUpdateCandidate? {
        val headers = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "yoinkd-android")
        val releases = mediaJson.decodeFromString<List<GitHubRelease>>(http.bytes(
            "https://api.github.com/repos/$repository/releases?per_page=20", 5_000_000, headers).decodeToString())
        val candidates = mutableListOf<AppUpdateCandidate>()
        var failure: Exception? = null
        for (release in releases.filter { !it.draft }) {
            val metadata = release.assets.singleOrNull { it.name == "yoinkd-update.json" } ?: continue
            try {
                require(release.tag_name.matches(Regex("v[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) &&
                    metadata.size in 1..64_000 && metadata.browser_download_url ==
                    "https://github.com/$repository/releases/download/${release.tag_name}/yoinkd-update.json") {
                    "The app release metadata address is invalid."
                }
                val manifest = mediaJson.decodeFromString<AppReleaseManifest>(http.bytes(metadata.browser_download_url, 64_000).decodeToString())
                selectReleaseApk(release, manifest, repository, applicationId, abi, installedVersion)?.let(candidates::add)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        return candidates.maxByOrNull { it.versionCode } ?: if (failure != null) throw failure else null
    }
}
