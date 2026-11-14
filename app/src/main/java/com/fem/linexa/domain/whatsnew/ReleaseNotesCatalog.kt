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
 * translated (the project ships one `values/strings.xml` — the many `application-label-*`
 * entries in the APK come from AndroidX, not from Linexa translations). A localized
 * changelog would need one string resource per line per release, permanently
 * accumulating, for text that is obsolete by the following version.
 */
object ReleaseNotesCatalog {

    /**
     * All known releases, newest last.
     *
     * Order is not load-bearing — [forVersion] looks up by [ReleaseNotes.versionCode]
     * rather than by position — but is kept chronological so the file reads as a
     * changelog and appending is the natural edit.
     *
     * The first public release starts a fresh versionCode sequence at 1. Pre-public
     * development builds are not part of this changelog.
     */
    val entries: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            versionCode = 1,
            versionName = "0.1.0-beta",
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
}
