package com.firesms.app.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/** A stable release; pageUrl always points to this project's GitHub release page. */
data class AppRelease(val version: String, val pageUrl: String)

class UpdateCheckException(message: String, cause: Throwable? = null) : IOException(message, cause)

class GitHubReleaseClient {
    suspend fun findUpdate(installedVersion: String): AppRelease? {
        try {
            val response = client.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                header("User-Agent", "FireSMS-update-checker")
            }
            return decodeLatestReleaseResponse(response.status.value, response.bodyAsText(), installedVersion)
        } catch (error: CancellationException) {
            throw error
        } catch (error: UpdateCheckException) {
            throw error
        } catch (error: Exception) {
            throw UpdateCheckException("Couldn't reach GitHub. Check your connection and try again.", error)
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/PepeByte/FireSMS/releases/latest"

        // Deliberately separate from FireflyApi: never sends the user's Firefly token to GitHub.
        private val client by lazy {
            HttpClient(OkHttp) {
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 20_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 20_000
                }
            }
        }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    val prerelease: Boolean
)

private val releaseJson = Json { ignoreUnknownKeys = true }

/** Pure response selection, shared by the network client and JVM tests. */
internal fun decodeLatestReleaseResponse(
    status: Int,
    body: String,
    installedVersion: String
): AppRelease? {
    val installed = StableVersion.parse(installedVersion)
        ?: throw UpdateCheckException("The installed app version couldn't be compared with GitHub releases.")
    when (status) {
        404 -> return null // GitHub has no published stable release yet.
        403, 429 -> throw UpdateCheckException("GitHub refused the update check or its rate limit was reached. Try again later.")
        200 -> Unit
        else -> throw UpdateCheckException("GitHub update check failed (HTTP $status). Try again later.")
    }
    val release = try {
        releaseJson.decodeFromString<GitHubRelease>(body)
    } catch (error: SerializationException) {
        throw UpdateCheckException("GitHub returned an invalid release response. Try again later.", error)
    }
    if (release.draft || release.prerelease) return null
    val latest = StableVersion.parse(release.tag)
        ?: throw UpdateCheckException("GitHub's latest release has an unsupported version tag.")
    if (latest <= installed) return null
    // The validated stable tag contains no path separators, query delimiters, or escapes.
    // Do not trust html_url from a remote payload to choose where to send the user.
    return AppRelease(
        version = release.tag.removePrefix("v"),
        pageUrl = "https://github.com/PepeByte/FireSMS/releases/tag/${release.tag}"
    )
}
