package com.fem.linexa.domain.distro

import java.net.URI

/**
 * §25 — User-choosable rootfs download mirror, layered on top of the
 * catalog.
 *
 * The distro catalog's URLs are hard-coded upstreams, and some users
 * cannot reach them at usable speed (or at all). This resolver lets a
 * caller swap **only the archive URL** for a mirror while keeping
 * everything else about the [ResolvedDownload] untouched — most
 * importantly the checksum: the sidecar still comes from the
 * distribution's official catalog URL, so a mirror serving different
 * bytes fails validation and the install aborts. A mirror can therefore
 * be slow or even malicious, but never silently wrong: integrity is
 * never weakened by mirroring.
 *
 * Deliberately fail-open to the catalog default: an override that fails
 * validation (a typo in a mirror URL must not brick installs) returns the
 * original [ResolvedDownload] unchanged, and the UI validates on the
 * write path before persisting anything — this function is the last
 * safety net, not the first.
 */
object RootfsMirrorResolver {

    /**
     * Apply [overrideUrl] to [resolved]. null/blank → [resolved] returned
     * unchanged (no mirror requested). Valid (see
     * [isValidOverrideUrl]) → a copy whose [ResolvedDownload.downloadUrl]
     * is the override and whose [ResolvedDownload.checksumUrl] /
     * [ResolvedDownload.checksumFormat] are preserved verbatim from the
     * catalog, so verification still happens against the distribution's
     * official sidecar. Invalid → [resolved] unchanged, as if no override
     * had been given (fail-open to the catalog default; the write path is
     * expected to have validated already).
     *
     * [manifest] is carried in the signature so callers can pass the full
     * catalog entry (and so a future per-distro restriction — e.g. only
     * allowing mirrors for sources that publish checksums — needs no
     * signature change).
     */
    fun resolve(manifest: DistroManifest, resolved: ResolvedDownload, overrideUrl: String?): ResolvedDownload {
        val candidate = overrideUrl?.trim().orEmpty()
        if (candidate.isEmpty() || !isValidOverrideUrl(candidate)) return resolved
        return resolved.copy(downloadUrl = candidate)
    }

    /**
     * Whether [url] is an acceptable mirror override: absolute http/https
     * URL with a non-empty host ([URI]-parsed, no network touched), and no
     * whitespace, quotes or control characters — the same characters that
     * would make the value unsafe to persist into settings or interpolate
     * into any future command/shell context.
     */
    fun isValidOverrideUrl(url: String): Boolean {
        val candidate = url.trim()
        if (candidate.isEmpty()) return false
        if (candidate.any { it.isWhitespace() || it.isISOControl() || it == '\'' || it == '"' }) return false
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
