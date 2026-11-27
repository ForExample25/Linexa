package com.fem.linexa.service.exporter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § finding 022 — the exact shape of the export credential filter.
 *
 * The filter is intentionally path-name based and scoped to the credential
 * directories, so a harmless user file that merely contains "passwd" or lives
 * next to `.gnupg` is never removed from a backup. These cases pin both the
 * three required patterns and the near-misses.
 */
class ExportCredentialPathsTest {

    @Test
    fun vncPasswordFilesAreMatchedAtAnyDepth() {
        assertTrue(ExportCredentialPaths.matches("home/linexa/.vnc/linexa-vnc-passwd"))
        assertTrue(ExportCredentialPaths.matches("root/.vnc/passwd"))
        assertTrue(ExportCredentialPaths.matches(".vnc/mypasswd"))
    }

    @Test
    fun vncFilesThatAreNotPasswordsAreKept() {
        assertFalse(ExportCredentialPaths.matches("home/linexa/.vnc/config"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/.vnc/passwd.txt"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/vnc-passwd"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/notes/vnc-passwd.txt"))
    }

    @Test
    fun sshPrivateKeysAreMatchedAndPublicKeysAreKept() {
        assertTrue(ExportCredentialPaths.matches("home/linexa/.ssh/id_rsa"))
        assertTrue(ExportCredentialPaths.matches("home/linexa/.ssh/id_ed25519"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/.ssh/id_rsa.pub"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/.ssh/id_ed25519-cert.pub"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/.ssh/known_hosts"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/other/id_rsa"))
    }

    @Test
    fun theWholeGnupgHomeIsMatched() {
        assertTrue(ExportCredentialPaths.matches("home/linexa/.gnupg"))
        assertTrue(ExportCredentialPaths.matches("home/linexa/.gnupg/pubring.kbx"))
        assertTrue(
            ExportCredentialPaths.matches("home/linexa/.gnupg/private-keys-v1.d/key.key")
        )
        assertTrue(ExportCredentialPaths.matches(".gnupg/trustdb.gpg"))
    }

    @Test
    fun lookalikeGnupgNamesAreKept() {
        assertFalse(ExportCredentialPaths.matches("home/linexa/.gnupg-backup/key"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/gnupg/key"))
        assertFalse(ExportCredentialPaths.matches("home/linexa/notes/.gnupgfile"))
    }

    @Test
    fun theRootAndAnEmptyPathAreNotCredentials() {
        assertFalse(ExportCredentialPaths.matches(""))
        assertFalse(ExportCredentialPaths.matches("/"))
        assertFalse(ExportCredentialPaths.matches("etc/os-release"))
    }
}
