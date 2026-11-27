package com.fem.linexa.service.update

import com.fem.linexa.domain.update.GithubReleaseAsset
import com.fem.linexa.service.distro.DownloadResult
import com.fem.linexa.service.distro.HttpDistroDownloader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads the selected release APK into the app's private updates folder.
 *
 * The heavy lifting is the project's own resumable
 * [HttpDistroDownloader] (Range+If-Range resume, SHA-256 of the streamed
 * bytes, connection-close detection) — this layer adds the two checks that
 * are specific to a security-sensitive INSTALLABLE file:
 *
 *   * the downloaded length must match the asset's declared size (GitHub
 *     publishes `size` per asset);
 *   * the downloaded content's SHA-256 must match the GitHub-provided asset
 *     digest when one exists (`digest: "sha256:…"`).
 *
 * A mismatch DELETES the file before returning: a failed verification never
 * leaves an installable-looking artifact behind.
 */
interface ApkUpdateDownloader {

    /**
     * Download [asset] to [destination], reporting progress in bytes.
     * Success is returned only for a complete, size- and digest-verified
     * file.
     */
    suspend fun download(
        asset: GithubReleaseAsset,
        destination: File,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit
    ): Outcome

    sealed interface Outcome {
        data class Success(val file: File, val sha256: String?) : Outcome
        data class Failure(val kind: FailureKind, val detail: String? = null) : Outcome
    }

    enum class FailureKind {
        /** Network/server trouble — retry is meaningful. */
        NETWORK,

        /** Storage trouble (mkdirs/write failed). */
        DISK,

        /** Length or digest mismatch — the file was deleted. */
        VERIFY,

        /** The release asset URL is not a trusted Linexa GitHub download URL. */
        UNTRUSTED
    }
}

@Singleton
class HttpApkUpdateDownloader @Inject constructor(
    private val downloader: HttpDistroDownloader
) : ApkUpdateDownloader {

    /**
     * The URL trust rule applied before any bytes are fetched.
     *
     * A reassignable property rather than a constructor parameter because Hilt
     * constructs the production instance with only the downloader, while the
     * JVM tests exercise the size/digest layer against a localhost server. The
     * production value is never changed at runtime; the dedicated
     * `untrustedUrl` test covers the default rule.
     */
    internal var assetUrlPolicy: (String) -> Boolean = UpdateServerConfig::isTrustedAssetUrl

    override suspend fun download(
        asset: GithubReleaseAsset,
        destination: File,
        onProgress: suspend (Long, Long?) -> Unit
    ): ApkUpdateDownloader.Outcome {
        if (!assetUrlPolicy(asset.downloadUrl)) {
            return ApkUpdateDownloader.Outcome.Failure(
                ApkUpdateDownloader.FailureKind.UNTRUSTED,
                "Release asset URL is not a trusted ${UpdateServerConfig.REPO_OWNER}/" +
                    "${UpdateServerConfig.REPO_NAME} GitHub download URL"
            )
        }
        val result = downloader.download(asset.downloadUrl, destination, onProgress)
        return when (result) {
            is DownloadResult.Failure ->
                ApkUpdateDownloader.Outcome.Failure(
                    ApkUpdateDownloader.FailureKind.NETWORK,
                    result.reason
                )
            is DownloadResult.Success -> {
                if (!verifySize(asset, destination)) {
                    destination.delete()
                    return ApkUpdateDownloader.Outcome.Failure(
                        ApkUpdateDownloader.FailureKind.VERIFY,
                        "Downloaded ${destination.length()} bytes, release declared ${asset.sizeBytes}"
                    )
                }
                if (!verifyDigest(asset, result.sha256)) {
                    destination.delete()
                    return ApkUpdateDownloader.Outcome.Failure(
                        ApkUpdateDownloader.FailureKind.VERIFY,
                        "SHA-256 of the download does not match the release digest"
                    )
                }
                ApkUpdateDownloader.Outcome.Success(destination, result.sha256)
            }
        }
    }

    private fun verifySize(asset: GithubReleaseAsset, file: File): Boolean {
        val expected = asset.sizeBytes ?: return true
        return file.length() == expected
    }

    private fun verifyDigest(asset: GithubReleaseAsset, computedSha256: String): Boolean {
        val expected = asset.sha256Hex ?: return true
        return computedSha256.equals(expected, ignoreCase = true)
    }
}
