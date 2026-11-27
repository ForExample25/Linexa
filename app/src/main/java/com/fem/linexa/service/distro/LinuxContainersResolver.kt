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

        val latestTimestamp = latestBuildTimestamp(html)
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

    /**
     * Newest build timestamp in an Apache autoindex body, or null.
     *
     * Acceptance finding (2026-11-26): the live server now percent-encodes the
     * colon in the href (`href="20261116_22%3A14/"`), so a `HH:MM`-only
     * pattern matched nothing and every linuxcontainers.org resolve failed
     * with "No build directories found" even though the directory exists.
     * Both spellings are accepted; the captured value is used verbatim in the
     * build URL (the server serves either form). Timestamps in this
     * fixed-width `YYYYMMDD_HH:MM` shape sort correctly as strings, so max()
     * picks the newest build without date parsing.
     */
    internal fun latestBuildTimestamp(html: String): String? =
        TIMESTAMP_DIR_REGEX.findAll(html).map { it.groupValues[1] }.maxOrNull()

    companion object {
        private const val BASE_URL = "https://images.linuxcontainers.org/images"

        private val TIMESTAMP_DIR_REGEX =
            Regex("""href="(\d{8}_\d{2}(?::|%3A)\d{2})/"""")
    }
}
