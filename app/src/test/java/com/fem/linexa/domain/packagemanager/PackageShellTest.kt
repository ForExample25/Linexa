package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 — JVM tests for the shell-safety guards.
 *
 * A regression here is a security issue: every value these accept is
 * interpolated verbatim into a shell command inside a Linux workspace.
 */
class PackageShellTest {

    @Test
    fun validPackageNamesAccepted() {
        listOf(
            "bash", "curl", "libc6", "python3", "htop", "ca-certificates",
            "zlib1g-dev", "g++", "libssl1.1", "nodejs", "0ad", "dhcpcd"
        ).forEach { assertTrue("expected $it to be safe", PackageShell.isSafePackageName(it)) }
    }

    @Test
    fun invalidPackageNamesRejected() {
        listOf(
            "", " ", "-foo", ".foo", "bash;", "a|b", "a&b", "a\$b", "a`b",
            "a b", "bash\n", "a\"b", "a'b", "a(b)", "a*b", "a?b", "a~b",
            "a#b", "a%b", "a!b", "a<b", "a>b", "a\\b", "/etc/passwd",
            "../evil", "$(whoami)", "a]b", "a[b"
        ).forEach { assertFalse("expected '$it' to be rejected", PackageShell.isSafePackageName(it)) }
    }

    @Test
    fun validSearchQueriesAccepted() {
        listOf(
            "git", "libssl", "python3", "http server", "g++", "v2.5",
            "nodejs@18", "gcc:fortran", "libx11-dev"
        ).forEach { assertTrue(PackageShell.isSafeSearchQuery(it)) }
    }

    @Test
    fun invalidSearchQueriesRejected() {
        listOf(
            "", "   ", "a;b", "a|b", "a&b", "a\$b", "a`b", "a; rm -rf /",
            "a\"b", "a'b", "a(b)", "a*b", "a?b", "a\nb", "a\tb"
        ).forEach { assertFalse(PackageShell.isSafeSearchQuery(it)) }
    }
}
