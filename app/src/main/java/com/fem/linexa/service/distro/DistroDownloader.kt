package com.fem.linexa.service.distro

import java.io.File

/**
 * Downloads a distro archive to [File], resuming from whatever's already on
 * disk at that path if the previous attempt didn't finish. See
 * [HttpDistroDownloader] for the one real implementation.
 */
interface DistroDownloader {
    suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit
    ): DownloadResult
}

sealed interface DownloadResult {
    data class Success(val file: File, val sha256: String) : DownloadResult
    data class Failure(val reason: String) : DownloadResult
}
