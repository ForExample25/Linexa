package com.fem.linexa.service.distro

import com.fem.linexa.domain.distro.ChecksumFormat
import com.fem.linexa.domain.distro.DistroResolverIds
import com.fem.linexa.domain.distro.DistroSource
import com.fem.linexa.domain.distro.DistroSourceResolver
import com.fem.linexa.domain.distro.ResolvedDownload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a [DistroSource.Resolved] against images.linuxcontainers.org's
 * LXC image server. Used for Fedora in [com.fem.linexa.domain.distro.DistroCatalog]
 * because, unlike Alpine/Ubuntu/Arch/Debian, Fedora doesn't publish its own
 * stable minimal-rootfs tarball URL — but linuxcontainers.org mirrors one
 * per release under `images/{distro}/{release}/{arch}/default/`.
 *
 * The catch this class exists to handle: that directory's *contents* are
 * build-timestamped subdirectories (`20240724_20:33/`, one per rebuild) —
 * there is no fixed "current" URL, so the actual current one has to be
 * looked up by listing the directory and taking the newest timestamp. Real,
 * currently-live server as of when this catalog entry was written — the
 * exact directory layout (Apache-style autoindex HTML) was confirmed
 * against this server; if linuxcontainers.org ever changes that layout,
 * only this one class needs to change, not [com.fem.linexa.domain.distro.DistroCatalog]
 * or anything downstream of it.
 */
@Singleton
class LinuxContainersResolver @Inject constructor(
    private val textFetcher: SimpleHttpTextFetcher
) : DistroSourceResolver {

    override val id: String = DistroResolverIds.LINUX_CONTAINERS

    override suspend fun resolve(source: DistroSource.Resolved): ResolvedDownload {
        val listingUrl = "$BASE_URL/${source.distro}/${source.release}/${source.arch}/default/"
        val html = textFetcher.getText(listingUrl)

        val latestTimestamp = TIMESTAMP_DIR_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .maxOrNull()
            ?: throw IllegalStateException(
                "No build directories found at $listingUrl — linuxcontainers.org's layout " +
                    "may have changed, or ${source.distro}/${source.release}/${source.arch} doesn't exist"
            )

        val buildUrl = "$listingUrl$latestTimestamp/"
        return ResolvedDownload(
            downloadUrl = "${buildUrl}rootfs.tar.xz",
            checksumUrl = "${buildUrl}SHA256SUMS",
            checksumFormat = ChecksumFormat.MULTI_HASH_LIST
        )
    }

    companion object {
        private const val BASE_URL = "https://images.linuxcontainers.org/images"

        // Apache-style autoindex: `<a href="20240724_20:33/">20240724_20:33/</a>`.
        // Timestamps in this YYYYMMDD_HH:MM form sort correctly as plain
        // strings, so plain max() over the matches picks the newest build
        // without needing to actually parse them as dates.
        private val TIMESTAMP_DIR_REGEX = Regex("""href="(\d{8}_\d{2}:\d{2})/"""")
    }
}
