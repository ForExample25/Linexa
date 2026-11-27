package com.fem.linexa.domain.update

/**
 * Picks the APK asset a release offers for installation.
 *
 * The project's distribution strategy is ONE universal APK
 * (`app-release.apk`, arm64-v8a + armeabi-v7a inside — the release notes'
 * "APK" section documents exactly that). The selector prefers that shape and
 * REFUSES to guess when it cannot establish compatibility:
 *
 *   * only files ending in `.apk` are candidates — never the source zip/tar
 *     archives or the `SHA256SUMS` sidecar;
 *   * a single candidate is taken as-is;
 *   * with several candidates, an explicit `universal` asset wins, then the
 *     single candidate WITHOUT an ABI marker (a plain `app-release.apk`);
 *   * several ABI-suffixed candidates and no universal one is unresolvable —
 *     returning null (the UI explains the problem) beats downloading a file
 *     that may not match the device.
 */
object ApkAssetSelector {

    private const val APK_SUFFIX = ".apk"
    private const val UNIVERSAL_MARKER = "universal"

    private val ABI_MARKERS = listOf(
        "arm64-v8a", "armeabi-v7a", "x86_64", "x86", "mips", "mips64"
    )

    /** The installable APK of [release], or null when none can be chosen reliably. */
    fun select(release: GithubRelease): GithubReleaseAsset? = select(release.assets)

    fun select(assets: List<GithubReleaseAsset>): GithubReleaseAsset? {
        val candidates = assets.filter { it.name.isNotBlank() && it.name.lowercase().endsWith(APK_SUFFIX) }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()

        candidates.firstOrNull { it.name.lowercase().contains(UNIVERSAL_MARKER) }?.let { return it }

        val unmarked = candidates.filter { !hasAbiMarker(it.name) }
        if (unmarked.size == 1) return unmarked.single()

        return null
    }

    private fun hasAbiMarker(name: String): Boolean {
        val lower = name.lowercase().removeSuffix(APK_SUFFIX)
        return ABI_MARKERS.any { marker -> lower.contains(marker) }
    }
}
