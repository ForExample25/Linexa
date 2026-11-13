package com.fem.linexa.domain.packagemanager

import java.net.URI

/**
 * §24 — Per-package-manager mirror switching for the guest's package repos.
 *
 * Users behind slow or filtered routes (the gap analysis' top P0: "DNS /
 * mirror configurable") need the workspace's package repo base URL pointed
 * at a mirror they choose. Each supported manager stores its repos in a
 * different format and location, so one deterministic shell command per
 * manager is generated here — **always backing the originals up first**
 * (a `.linexa-bak` suffix, restorable via [restoreCommand]) so the switch
 * is reversible without re-provisioning the rootfs.
 *
 * Everything here is pure string work (no Android, no exec, no network):
 * the caller runs the returned one-liner through the workspace's streaming
 * exec channel (`/bin/sh -c`), so every command MUST stay single-line.
 *
 * Security posture: [mirrorUrl] is embedded into single-quoted or `#`-
 * delimited shell contexts. [isValidMirrorUrl] is the shell-injection
 * guard — it rejects quote/backtick/`$`/whitespace/`#` (and the sed
 * metacharacters `&` and `\`) — and the `apply*` builders re-check it and
 * refuse to build a command from an unvalidated URL. Unlike the project's
 * read-path fail-open convention, this write path fails **closed** (null):
 * the output is a destructive command over the guest's repo config, so a
 * malformed URL must never reach a shell.
 */
object PackageMirrorPlanner {

    /** Path of the managed deb822 sources file written for apt. */
    const val APT_MANAGED_SOURCES_FILE = "/etc/apt/sources.list.d/linexa-mirror.sources"

    /** Suffix appended to every backed-up original. */
    const val BACKUP_SUFFIX = ".linexa-bak"

    /** Repo name given to the zypper mirror service added by [applyCommand]. */
    const val ZYPPER_MIRROR_REPO_ALIAS = "linexa-mirror"

    /**
     * One deterministic, idempotent single-line shell command that switches
     * [pmKey]'s repo base URL to [mirrorUrl], backing originals up first,
     * or null when [pmKey] is not a supported key or [mirrorUrl] fails
     * [isValidMirrorUrl] (fail-closed — see class doc).
     *
     * Per manager:
     * - apt: renames the originals to `*.linexa-bak` (skipping anything
     *   already backed up or the managed file itself, so re-applying never
     *   stacks suffixes or buries the true original), then writes a managed
     *   deb822 file whose `Suites:` is derived **guest-side** from
     *   `/etc/os-release` (`VERSION_CODENAME`, falling back to `stable`) —
     *   never hardcoded, since a mirror line pinning the wrong suite makes
     *   apt fail on every future operation.
     * - apk: rewrites the mirror host in `/etc/apk/repositories` with sed
     *   (`-i.bak` keeps Alpine's own backup convention).
     * - pacman: prepends `Server = <mirror>/$repo/os/$arch` (the `$repo` /
     *   `$arch` expansions are pacman's own, kept literal here) to the
     *   mirrorlist after backing it up.
     * - dnf: rewrites `baseurl`/`metalink`/`mirrorlist=...` lines in every
     *   `.repo` file under `/etc/yum.repos.d/`. Known limitation, documented honestly:
     *   Fedora's repo topology is *approximated*
     *   (`releases/$releasever/Everything/$basearch/os/`) — correct for
     *   standard Fedora repos, and the `.bak` copy keeps any exotic repo
     *   recoverable.
     * - zypper: backs up `/etc/zypp/repos.d`, empties it and adds one
     *   mirror service — guarded so the backup taken is always the *first*
     *   (pre-Linexa) state, never an already-mirrored one.
     */
    fun applyCommand(pmKey: String, mirrorUrl: String): String? {
        if (!isValidMirrorUrl(mirrorUrl)) return null
        return when (pmKey.trim().lowercase()) {
            PackageManagerDetector.KEY_APT ->
                "for f in /etc/apt/sources.list /etc/apt/sources.list.d/*; do " +
                    "[ -f \"\$f\" ] || continue; " +
                    "case \"\$f\" in *.linexa-bak|$APT_MANAGED_SOURCES_FILE) ;; " +
                    "*) mv \"\$f\" \"\$f$BACKUP_SUFFIX\";; esac; done 2>/dev/null; " +
                    ". /etc/os-release 2>/dev/null; " +
                    "suite=\"\${VERSION_CODENAME:-stable}\"; " +
                    "printf '%s\\n' 'Types: deb' 'URIs: $mirrorUrl' \"Suites: \${suite}\" " +
                    "'Components: main' 'Signed-By: /usr/share/keyrings/' > $APT_MANAGED_SOURCES_FILE"
            PackageManagerDetector.KEY_APK ->
                "sed -i.bak -E 's#https?://[^/]+#" + mirrorUrl + "#g' /etc/apk/repositories"
            PackageManagerDetector.KEY_PACMAN ->
                "[ -f /etc/pacman.d/mirrorlist$BACKUP_SUFFIX ] || " +
                    "cp -a /etc/pacman.d/mirrorlist /etc/pacman.d/mirrorlist$BACKUP_SUFFIX 2>/dev/null; " +
                    "printf 'Server = $mirrorUrl/\$repo/os/\$arch\\n' | " +
                    "cat - /etc/pacman.d/mirrorlist$BACKUP_SUFFIX > /etc/pacman.d/mirrorlist 2>/dev/null || " +
                    "printf 'Server = $mirrorUrl/\$repo/os/\$arch\\n' > /etc/pacman.d/mirrorlist"
            PackageManagerDetector.KEY_DNF ->
                "sed -i.bak -E 's#^(baseurl|metalink|mirrorlist)=.*#baseurl=" + mirrorUrl +
                    "/releases/\$releasever/Everything/\$basearch/os/#' /etc/yum.repos.d/*.repo"
            PackageManagerDetector.KEY_ZYPPER ->
                "[ -d /etc/zypp/repos.d$BACKUP_SUFFIX ] || " +
                    "cp -a /etc/zypp/repos.d /etc/zypp/repos.d$BACKUP_SUFFIX 2>/dev/null; " +
                    "rm -f /etc/zypp/repos.d/*; " +
                    "zypper --non-interactive ar -f '$mirrorUrl' $ZYPPER_MIRROR_REPO_ALIAS"
            else -> null
        }
    }

    /**
     * The inverse of [applyCommand] for [pmKey]: a single-line command that
     * restores the `.linexa-bak` backups (consuming them, mv-style) and
     * removes whatever the apply step created, or null for an unsupported
     * key. Guarded so running it on a never-mirrored guest is a no-op that
     * exits 0 rather than an error, and so zypper's restore never empties
     * `repos.d` unless its backup actually exists.
     */
    fun restoreCommand(pmKey: String): String? = when (pmKey.trim().lowercase()) {
        PackageManagerDetector.KEY_APT ->
            "for f in /etc/apt/sources.list$BACKUP_SUFFIX /etc/apt/sources.list.d/*$BACKUP_SUFFIX; do " +
                "[ -f \"\$f\" ] && mv \"\$f\" \"\${f%.linexa-bak}\"; done 2>/dev/null; " +
                "rm -f $APT_MANAGED_SOURCES_FILE"
        PackageManagerDetector.KEY_APK ->
            "[ -f /etc/apk/repositories.bak ] && " +
                "mv -f /etc/apk/repositories.bak /etc/apk/repositories || true"
        PackageManagerDetector.KEY_PACMAN ->
            "[ -f /etc/pacman.d/mirrorlist$BACKUP_SUFFIX ] && " +
                "mv -f /etc/pacman.d/mirrorlist$BACKUP_SUFFIX /etc/pacman.d/mirrorlist || true"
        PackageManagerDetector.KEY_DNF ->
            "for f in /etc/yum.repos.d/*.repo.bak; do " +
                "[ -f \"\$f\" ] && mv -f \"\$f\" \"\${f%.bak}\"; done 2>/dev/null; true"
        PackageManagerDetector.KEY_ZYPPER ->
            "[ -d /etc/zypp/repos.d$BACKUP_SUFFIX ] && " +
                "rm -f /etc/zypp/repos.d/* && " +
                "cp -a /etc/zypp/repos.d$BACKUP_SUFFIX/. /etc/zypp/repos.d/ && " +
                "rm -rf /etc/zypp/repos.d$BACKUP_SUFFIX || true"
        else -> null
    }

    /**
     * Whether [url] may be safely embedded into the generated shell
     * commands. Requirements, in order:
     *
     * 1. **Shell-injection guard** (the "why" of the character blacklist):
     *    the URL lands inside single-quoted strings (`'URIs: …'`,
     *    `zypper ar -f '…'`, pacman's `printf 'Server = …'`) and inside
     *    `#`-delimited sed replacements (`s#…#URL#g`), so a single quote,
     *    backtick, `$`, newline or any whitespace could break out and run
     *    arbitrary guest commands, and `#` would truncate a sed
     *    replacement. `&` and `\` are additionally rejected because they
     *    are sed-replacement metacharacters (whole-match / back-reference),
     *    and `"` plus ISO control characters as belt-and-braces for future
     *    contexts. No network is touched.
     * 2. It must parse as an absolute http/https URL with a non-empty host
     *    (via [URI] — anything else cannot be a package mirror).
     */
    fun isValidMirrorUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        if (url.any { it == '\'' || it == '"' || it == '`' || it == '$' || it == '#' || it == '&' || it == '\\' }) {
            return false
        }
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
