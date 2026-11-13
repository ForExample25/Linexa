package com.fem.linexa.domain.packagemanager

/**
 * §1.92 — Shared shell-safety guards for package-manager commands.
 *
 * Phase 0 hardening required every user-supplied value to be validated
 * before it is interpolated into a shell command; this is that guard,
 * extracted so every [PackageProvider] uses the identical rules instead of
 * each provider re-implementing (and possibly drifting on) its own regex.
 */
object PackageShell {

    /**
     * Package names (Debian/Arch/Alpine/Fedora/openSUSE): must start with a
     * letter/digit, then only letters, digits, `+`, `.`, `-`, `_`. Rejects
     * every shell metacharacter (`;`, `|`, `&`, `$`, backtick, quotes,
     * globs, whitespace, …).
     */
    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9+.\\-_]*$")

    /**
     * Free-text store search: alphanumerics plus the separators the package
     * tools' search syntax uses (`+`, `-`, `:`, `/`, `.`, `@` for e.g.
     * `python3@2.7`); a single space separates terms. No shell
     * metacharacters and no command substitution.
     */
    private val SEARCH_QUERY_REGEX = Regex("^[A-Za-z0-9+:@/_. -]*$")

    /** True when [name] may be safely interpolated into a shell command. */
    fun isSafePackageName(name: String): Boolean =
        name.isNotBlank() && PACKAGE_NAME_REGEX.matches(name)

    /** True when [query] may be safely passed to a search command. */
    fun isSafeSearchQuery(query: String): Boolean =
        query.isNotBlank() && SEARCH_QUERY_REGEX.matches(query)
}
