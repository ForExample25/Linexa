package com.fem.linexa.service.update

/**
 * The single place the update checker's GitHub coordinates live.
 *
 * One repository, spelled once: the release list API endpoint, the human
 * releases page and the raw asset host are all derived from the owner/name
 * pair here, so a repository move is one edit (and `UpdateServerConfigTest`
 * pins the pair against the user-facing `HelpLink` catalog so the two can
 * never drift apart silently).
 *
 * Everything is HTTPS and unauthenticated — no tokens, no secrets, ever: the
 * releases API is public, and embedding a credential in the app would put it
 * on every user's device.
 */
object UpdateServerConfig {

    const val REPO_OWNER = "ForExample25"
    const val REPO_NAME = "Linexa"

    /** The human releases page (shown when no installable APK can be found). */
    const val RELEASES_PAGE_URL: String = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"

    /** The GitHub REST releases endpoint — newest-first, unauthenticated. */
    const val RELEASES_API_URL: String =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=20"

    /** The repository page itself, for diagnostics strings. */
    const val REPOSITORY_URL: String = "https://github.com/$REPO_OWNER/$REPO_NAME"

    /** The only path GitHub uses for a release asset's `browser_download_url`. */
    private const val ASSET_PATH_PREFIX = "/$REPO_OWNER/$REPO_NAME/releases/download/"

    /**
     * Whether [url] is a release asset served from THIS repository.
     *
     * The update checker consumes URLs from remote release metadata. GitHub is
     * trusted to describe its own assets, but a malformed or tampered response must
     * not be able to point the downloader at an arbitrary host. This is a
     * defence-in-depth check on top of HTTPS and the APK's signature:
     *
     *   * scheme must be exactly `https`;
     *   * host must be exactly `github.com` (not a lookalike suffix);
     *   * no userinfo or non-default port;
     *   * path must start with `/ForExample25/Linexa/releases/download/`.
     *
     * GitHub's own redirect to its asset CDN is followed by
     * `HttpDistroDownloader`; the initial URL is the one release metadata controls.
     */
    fun isTrustedAssetUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.equals("github.com", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val path = uri.path ?: return false
        return path.startsWith(ASSET_PATH_PREFIX)
    }
}
