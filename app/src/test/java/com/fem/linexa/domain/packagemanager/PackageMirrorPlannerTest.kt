package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PackageMirrorPlanner]. The generated strings are the
 * contract — they go to `/bin/sh -c` in the guest — so each test pins the
 * pieces that must not drift: the mirror URL is always embedded, the
 * originals are always backed up (`.linexa-bak`), apt's suite is derived
 * guest-side from /etc/os-release, pacman's `$repo`/`$arch` stay literal
 * (they are pacman's own expansions), and everything stays single-line.
 */
class PackageMirrorPlannerTest {

    private val mirror = "https://mirror.example.com/debian"

    @Test
    fun aptCommandBacksUpDerivesSuiteAndWritesManagedFile() {
        val cmd = PackageMirrorPlanner.applyCommand("apt", mirror)!!
        assertTrue(cmd.contains(mirror))
        assertTrue(cmd.contains("os-release"))
        assertTrue(cmd.contains("linexa-mirror.sources"))
        assertTrue(cmd.contains(".linexa-bak"))
        // Suite must be guest-derived (VERSION_CODENAME with a stable
        // fallback), never a hardcoded distro release name.
        assertTrue(cmd.contains("VERSION_CODENAME:-stable"))
        assertFalse(cmd.contains("Suites: stable'"))
    }

    @Test
    fun apkCommandRewritesRepositoriesHostInPlace() {
        val cmd = PackageMirrorPlanner.applyCommand("apk", mirror)!!
        assertTrue(cmd.contains(mirror))
        assertTrue(cmd.contains("/etc/apk/repositories"))
        // sed with # delimiter (URLs contain /) and an in-place .bak copy.
        assertTrue(cmd.contains("sed -i.bak -E 's#https?://[^/]+#$mirror#g'"))
    }

    @Test
    fun pacmanCommandKeepsRepoAndArchLiteral() {
        val cmd = PackageMirrorPlanner.applyCommand("pacman", mirror)!!
        assertTrue(cmd.contains(mirror))
        // These are pacman's own variables — they must survive as literals.
        assertTrue(cmd.contains("\$repo"))
        assertTrue(cmd.contains("\$arch"))
        assertTrue(cmd.contains("mirrorlist.linexa-bak"))
    }

    @Test
    fun dnfCommandRewritesRepoFilesViaBaseurl() {
        val cmd = PackageMirrorPlanner.applyCommand("dnf", mirror)!!
        assertTrue(cmd.contains(mirror))
        assertTrue(cmd.contains("/etc/yum.repos.d/*.repo"))
        assertTrue(cmd.contains("baseurl="))
        assertTrue(cmd.contains(".repo.bak") || cmd.contains("-i.bak"))
    }

    @Test
    fun zypperCommandBacksUpReposAndAddsMirrorAlias() {
        val cmd = PackageMirrorPlanner.applyCommand("zypper", mirror)!!
        assertTrue(cmd.contains(mirror))
        assertTrue(cmd.contains("repos.d.linexa-bak"))
        assertTrue(cmd.contains("zypper --non-interactive ar -f '$mirror' linexa-mirror"))
    }

    @Test
    fun everyApplyCommandIsSingleLine() {
        for (key in listOf("apt", "apk", "pacman", "dnf", "zypper")) {
            val cmd = PackageMirrorPlanner.applyCommand(key, mirror)!!
            assertFalse("applyCommand($key) must be one line", cmd.contains('\n'))
        }
    }

    @Test
    fun applyCommandKeyMatchingIsCaseInsensitive() {
        assertEquals(
            PackageMirrorPlanner.applyCommand("apt", mirror),
            PackageMirrorPlanner.applyCommand("APT", mirror)
        )
    }

    @Test
    fun restoreCommandsAreNonNullAndReferenceTheBackups() {
        assertTrue(PackageMirrorPlanner.restoreCommand("apt")!!.contains(".linexa-bak"))
        assertTrue(PackageMirrorPlanner.restoreCommand("apk")!!.contains("repositories.bak"))
        assertTrue(PackageMirrorPlanner.restoreCommand("pacman")!!.contains("mirrorlist.linexa-bak"))
        assertTrue(PackageMirrorPlanner.restoreCommand("dnf")!!.contains(".repo.bak"))
        assertTrue(PackageMirrorPlanner.restoreCommand("zypper")!!.contains("repos.d.linexa-bak"))
    }

    @Test
    fun aptRestoreRemovesManagedFileAndPeelsBackupSuffix() {
        val cmd = PackageMirrorPlanner.restoreCommand("apt")!!
        assertTrue(cmd.contains("rm -f /etc/apt/sources.list.d/linexa-mirror.sources"))
        assertTrue(cmd.contains("%.linexa-bak"))
    }

    @Test
    fun everyRestoreCommandIsSingleLine() {
        for (key in listOf("apt", "apk", "pacman", "dnf", "zypper")) {
            val cmd = PackageMirrorPlanner.restoreCommand(key)!!
            assertFalse("restoreCommand($key) must be one line", cmd.contains('\n'))
        }
    }

    @Test
    fun unknownPackageManagerFailsClosedOnBothPlanners() {
        for (key in listOf("nix", "portage", "", "   ")) {
            assertNull(PackageMirrorPlanner.applyCommand(key, mirror))
            assertNull(PackageMirrorPlanner.restoreCommand(key))
        }
    }

    @Test
    fun invalidMirrorUrlFailsClosedOnApply() {
        // The write path is destructive over repo config: an unvalidated
        // URL must never be embedded, whatever the caller did upstream.
        for (bad in listOf("", "ftp://x/y", "not a url", "https://x'\$(id)", "#")) {
            assertNull(PackageMirrorPlanner.applyCommand("apt", bad))
        }
    }

    @Test
    fun validHttpsMirrorUrlIsAccepted() {
        assertTrue(PackageMirrorPlanner.isValidMirrorUrl("https://mirror.example.com/debian"))
        assertTrue(PackageMirrorPlanner.isValidMirrorUrl("http://mirror.example.com"))
    }

    @Test
    fun invalidMirrorUrlsAreRejected() {
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("http://mir ror/x")) // whitespace
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("http://mirror.example.com/x'; rm -rf / #")) // shell break-out
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("http://mirror.example.com/x\$(id)")) // command substitution
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("http://mirror.example.com/x`id`")) // backtick substitution
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("")) // empty
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("ftp://x/y")) // non-http scheme
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("https://x/y#frag")) // sed # delimiter collision
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("https://x/y?a=b&c=d")) // sed & metachar
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("https://x/y\\z")) // sed backslash metachar
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("not a url")) // no scheme/host
        assertFalse(PackageMirrorPlanner.isValidMirrorUrl("https://")) // no host
    }
}
