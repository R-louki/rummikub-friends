package com.rummikub.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published version of the app that can be downloaded. */
data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList()
)

/**
 * Looks for a newer version of the app on the GitHub Releases of the project (free, no store account).
 * The release tag is the version (v1.2.4) and the APK is attached to the release.
 */
class ReleaseChecker(
    private val repo: String,
    private val client: HttpClient = HttpClient(OkHttp)
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The latest published release with an APK, or null when there is none (or GitHub is unreachable). */
    suspend fun latest(): ReleaseInfo? {
        val response = client.get("https://api.github.com/repos/$repo/releases/latest") {
            header("Accept", "application/vnd.github+json")
        }
        if (response.status == HttpStatusCode.NotFound || !response.status.isSuccess()) return null
        val release = json.decodeFromString(GitHubRelease.serializer(), response.bodyAsText())
        if (release.draft || release.prerelease) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        return ReleaseInfo(
            versionName = release.tag.trimStart('v', 'V'),
            apkUrl = apk.url,
            sizeBytes = apk.size,
            notes = release.body?.trim().orEmpty()
        )
    }

    companion object {
        /** True when [candidate] (e.g. "1.2.10") is a higher version than [current] (e.g. "1.2.9"). */
        fun isNewer(current: String, candidate: String): Boolean {
            fun parts(v: String) = v.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(current)
            val b = parts(candidate)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (y != x) return y > x
            }
            return false
        }
    }
}
