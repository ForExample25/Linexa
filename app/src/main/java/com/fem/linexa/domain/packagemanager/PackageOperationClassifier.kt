package com.fem.linexa.domain.packagemanager

/**
 * Single classification authority for "is this guest command a package
 * operation?" (issue #2, 2026-09-03 rework).
 *
 * The classification used to live as a private method inside
 * `PRootContainerRuntime`, which made it unreachable from the two callers
 * that must AGREE with the runtime's queueing decision — the Shortcuts tab
 * (which used to type package commands into a terminal, bypassing the
 * queue entirely) and diagnostics. One classifier, one meaning: any
 * command the runtime serializes per workspace is exactly a command the
 * rest of the app also treats as a package operation.
 *
 * The classification is a deliberate conservative SUPERSET over
 * whitespace tokens: if any token of the command names a mutating
 * package-manager binary, the command counts. This catches the
 * multi-segment shapes Linexa actually issues —
 * `apt-get update && apt-get install -y …` (provisioners, desktop
 * presets, metadata shortcuts), `dpkg --configure -a … ; <command>` (the
 * recovery prepend) and `if command -v apt-get …; then apt-get update …`
 * (update dispatchers) — while leaving the read-only query vocabulary out
 * (`dpkg-query`, `apt-cache`, `apt list`, `apk info`, …): queries keep
 * their latency, mutations keep their exclusivity.
 *
 * Pure and framework-free on purpose: the runtime, the Shortcuts
 * ViewModel and unit tests all run this on the JVM with no Android
 * dependencies.
 */
object PackageOperationClassifier {

    /**
     * Mutating package-manager binaries, matched on the token's basename
     * (`/usr/bin/apt-get` matches). Read-only query front-ends are
     * deliberately absent — see the class kdoc.
     */
    val PACKAGE_MANAGER_COMMANDS: Set<String> = setOf(
        "apt-get",
        "apt",
        "dpkg",
        "apk",
        "pacman",
        "dnf",
        "zypper"
    )

    private val WHITESPACE = Regex("\\s+")

    /** True when [command] must run through the per-workspace package queue. */
    fun isPackageOperation(command: String): Boolean =
        command.split(WHITESPACE).any { it.substringAfterLast('/') in PACKAGE_MANAGER_COMMANDS }

    /**
     * Bounded, single-line human label for diagnostics ("who owns the
     * package queue right now"). Control characters are squashed so a
     * hostile command string can never inject newlines into logs or UI.
     */
    fun shortLabel(command: String, maxChars: Int = 64): String {
        val cleaned = buildString(command.length) {
            for (c in command) append(if (c.isISOControl() || c == '\t') ' ' else c)
        }.trim().replace(WHITESPACE, " ")
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars - 1) + "…"
    }
}
