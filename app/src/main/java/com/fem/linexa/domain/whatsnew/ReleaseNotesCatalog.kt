package com.fem.linexa.domain.whatsnew

/**
 * Every release's notes, keyed by `versionCode`. **This is the one file to edit when
 * shipping a new version.**
 *
 * Adding a release is a single entry appended to [entries] whose [ReleaseNotes.versionCode]
 * matches the `versionCode` in `app/build.gradle.kts`. Nothing else in the feature
 * needs touching: the card, the decision rule and the persistence are all
 * version-agnostic and read whatever this catalog says.
 *
 * Why a compiled Kotlin object rather than an asset (JSON/YAML) or a string resource
 * array. The changelog ships *with* the build it describes — it can never be out of
 * date relative to the running code, which is the entire failure mode a remotely
 * fetched or separately parsed changelog has. It also means a malformed entry is a
 * compile error rather than a parse failure at render time on a user's device, and it
 * costs no I/O on the startup path. The project already keeps this class of fixed,
 * ships-with-the-build data as Kotlin (`DistroCatalog`, `AttributionCatalog`,
 * `HelpLink`), so this is the existing pattern rather than a new one.
 *
 * The text is deliberately plain literals rather than `@StringRes` lookups: it is
 * per-release prose that is written once, superseded by the next release, and never
 * translated. A localized changelog would need one string resource per line per
 * release, permanently accumulating, for text that is obsolete by the following
 * version. The fixed furniture around it (section labels, date prefix, beta badge)
 * *is* localized.
 */
object ReleaseNotesCatalog {

    /**
     * All known releases, newest last.
     *
     * Order is not load-bearing — [forVersion] looks up by [ReleaseNotes.versionCode]
     * rather than by position — but is kept chronological so the file reads as a
     * changelog and appending is the natural edit.
     *
     * The published 0.1.0-beta artifact used `versionCode = 1` (the historical
     * literal); 0.2.0 uses the pinned `1002` from `app/build.gradle.kts`.
     * `ReleaseNotesCatalogTest` enforces the match for the running release build.
     */
    val entries: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            versionCode = 1,
            versionName = "0.1.0-beta",
            releaseDate = "2026-11-14",
            isBeta = true,
            summary = "First public beta: real Linux workspaces on Android, a full terminal, desktop access, and local-first workspace management.",
            changes = listOf(
                ReleaseChange(
                    ChangeCategory.NEW,
                    "Run supported Linux distributions in isolated workspaces on top of " +
                        "PRoot, with a staged and verified RootFS import flow."
                ),
                ReleaseChange(
                    ChangeCategory.NEW,
                    "A real terminal with a PTY, full ANSI/cursor rendering, scrollback, " +
                        "selection, and an extra-keys row — no line-buffered process pipe."
                ),
                ReleaseChange(
                    ChangeCategory.NEW,
                    "Desktop access per workspace: the built-in VNC client or an in-app " +
                        "web view for a guest web server."
                ),
                ReleaseChange(
                    ChangeCategory.NEW,
                    "Per-workspace file manager, package manager, task manager, network " +
                        "settings, commands, and shortcuts."
                ),
                ReleaseChange(
                    ChangeCategory.NEW,
                    "Legacy .tiny.yaml workspace definitions remain importable as a " +
                        "compatibility source; Linexa's own metadata takes precedence."
                ),
                ReleaseChange(
                    ChangeCategory.IMPROVED,
                    "Foreground workspace runtime with honest session status, an opt-in " +
                        "wake lock, and a clear enter/leave lifecycle."
                ),
                ReleaseChange(
                    ChangeCategory.CHANGED,
                    "Published under GPL-3.0-only, with every bundled third-party " +
                        "program attributed in Settings → About."
                )
            )
        ),
        ReleaseNotes(
            versionCode = 1002,
            versionName = "0.2.0",
            releaseDate = "2026-11-28",
            isBeta = true,
            summary = "A faster, smaller beta with an in-app update checker, a more " +
                "reliable Linux runtime, and a polished bilingual interface.",
            changes = listOf(
                ReleaseChange(
                    ChangeCategory.NEW,
                    "In-app update checker: discovers published GitHub releases " +
                        "(including this beta channel), shows the release notes and " +
                        "download size, verifies the APK, and hands it to Android's " +
                        "installer."
                ),
                ReleaseChange(
                    ChangeCategory.NEW,
                    "Persian interface with RTL-aware typography, plus a per-app " +
                        "language choice in Global Settings."
                ),
                ReleaseChange(
                    ChangeCategory.NEW,
                    "Clearer terminal diagnostics: structured exit explanations, a " +
                        "silent-output watchdog, and a copyable error report."
                ),
                ReleaseChange(
                    ChangeCategory.NEW,
                    "What's New now shows the release date, a beta badge, and the " +
                        "notes of previous releases."
                ),
                ReleaseChange(
                    ChangeCategory.IMPROVED,
                    "Workspace re-entry after Stop is roughly ten times faster in " +
                        "device testing: the previous size is shown immediately while " +
                        "a background measurement refreshes it."
                ),
                ReleaseChange(
                    ChangeCategory.IMPROVED,
                    "Re-entering a workspace that is already running attaches to the " +
                        "live session instead of starting the runtime service again."
                ),
                ReleaseChange(
                    ChangeCategory.IMPROVED,
                    "Terminal layout stays stable while the terminal opens, and the " +
                        "requested session's first frame is ready without a resize jump."
                ),
                ReleaseChange(
                    ChangeCategory.IMPROVED,
                    "Package Manager sorting, the Apps category filter, and the File " +
                        "Manager storage overview no longer reset or blank unexpectedly."
                ),
                ReleaseChange(
                    ChangeCategory.FIXED,
                    "Workspace deletion and install cleanup no longer follow " +
                        "guest-authored directory symlinks outside the workspace."
                ),
                ReleaseChange(
                    ChangeCategory.FIXED,
                    "PTY lifecycle hardening: stale descriptor reuse after exit, lost " +
                        "final output, and native failure-path cleanup are corrected."
                ),
                ReleaseChange(
                    ChangeCategory.FIXED,
                    "Install recovery now sweeps stale rootfs backups and quarantine " +
                        "directories, and installs can be cancelled safely."
                ),
                ReleaseChange(
                    ChangeCategory.PERFORMANCE,
                    "Release builds now use R8 code shrinking, optimization, and " +
                        "resource shrinking. The 0.2.0 APK is 13.5 MB versus " +
                        "57.3 MB for 0.1.0 — 76% smaller."
                ),
                ReleaseChange(
                    ChangeCategory.PERFORMANCE,
                    "Workspace re-entry after Stop measured 2.5 s before and about " +
                        "0.24 s after the fix (median, same device and procedure)."
                ),
                ReleaseChange(
                    ChangeCategory.CHANGED,
                    "Linexa's own source is now consistently published under " +
                        "GPL-3.0-only; bundled components keep their own licenses."
                )
            )
        )
    )

    /**
     * The notes for [versionCode], or null when this build ships none.
     *
     * Null is a supported, non-exceptional result. A build whose `versionCode` was
     * bumped without a catalog entry — a hotfix, a CI build, a developer's local
     * bump — must simply show no card, which is what the caller does with null. It
     * must specifically *not* fall back to the newest entry it has: that would
     * attribute another release's changes to this build, which is worse than showing
     * nothing.
     */
    fun forVersion(versionCode: Int): ReleaseNotes? =
        entries.firstOrNull { it.versionCode == versionCode }

    /**
     * Releases older than [versionCode], newest first — the "Previous releases"
     * section of the expanded What's New card.
     *
     * Entries without content are omitted, and the running release is excluded even
     * if a malformed catalog listed it twice. The result is a display list, not a
     * second source of truth: every item still comes from [entries].
     */
    fun previousVersions(versionCode: Int): List<ReleaseNotes> =
        entries
            .asSequence()
            .filter { it.versionCode < versionCode && it.hasContent }
            .sortedByDescending { it.versionCode }
            .toList()
}
