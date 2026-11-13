package com.fem.linexa.core.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for symlink validation in [RealArchiveValidator] (§1.35, Phase 9).
 *
 * The validator allows absolute symlinks that resolve inside the extracted
 * rootfs boundary, while rejecting symlinks that escape it.
 *
 * Test matrix (see issue §5 §1.26 finding):
 * 1. `./usr/bin/yes -> /bin/busybox` — allowed (absolute, stays inside rootfs)
 * 2. `./bin/sh -> ../../etc/passwd` — rejected (relative traversal escapes rootfs)
 * 3. `./a/b/file -> ../../../outside` — rejected (relative traversal escapes rootfs)
 */
class RealArchiveValidatorSymlinkTest {

    private lateinit var validator: RealArchiveValidator

    @Before
    fun setUp() {
        validator = RealArchiveValidator()
    }

    // ── Allowed: absolute symlinks that resolve inside rootfs ────────

    @Test
    fun `absolute symlink to busybox from usr bin is allowed`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = "/bin/busybox"
        )
        assertNull("Absolute symlink /bin/busybox should be allowed (resolves inside rootfs)", result)
    }

    @Test
    fun `absolute symlink to lib is allowed`() {
        val result = validator.checkEntry(
            name = "./lib/libc.musl-x86_64.so.1",
            isSymlink = true,
            linkTarget = "/lib/libc.so"
        )
        assertNull(result)
    }

    @Test
    fun `absolute symlink from root level is allowed`() {
        val result = validator.checkEntry(
            name = "./bin/sh",
            isSymlink = true,
            linkTarget = "/bin/busybox"
        )
        assertNull(result)
    }

    @Test
    fun `relative symlink within same directory is allowed`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = "busybox"
        )
        assertNull(result)
    }

    @Test
    fun `relative symlink to sibling directory is allowed`() {
        val result = validator.checkEntry(
            name = "./lib/libc.musl-x86_64.so.1",
            isSymlink = true,
            linkTarget = "../lib/libc.so"
        )
        assertNull(result)
    }

    @Test
    fun `relative symlink resolving inside rootfs from deep entry is allowed`() {
        // ./usr/bin/yes -> ../../etc/passwd resolves to etc/passwd (inside rootfs)
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = "../../etc/passwd"
        )
        assertNull("Relative symlink resolving inside rootfs should be allowed", result)
    }

    @Test
    fun `non-symlink entry is always allowed`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = false,
            linkTarget = null
        )
        assertNull(result)
    }

    @Test
    fun `symlink with null target is allowed`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = null
        )
        assertNull(result)
    }

    @Test
    fun `symlink with blank target is allowed`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = ""
        )
        assertNull(result)
    }

    // ── Rejected: relative traversal escaping rootfs ──────────────────

    @Test
    fun `relative symlink escaping from shallow entry is rejected`() {
        // ./bin/sh -> ../../etc/passwd: anchor=["bin"], target=["..","..","etc","passwd"]
        // Walk: bin(+1)=1, ..(0)=0, ..(-1)=-1 → escapes
        val result = validator.checkEntry(
            name = "./bin/sh",
            isSymlink = true,
            linkTarget = "../../etc/passwd"
        )
        assertNotNull("Relative traversal ../../etc/passwd from ./bin/sh should be rejected", result)
    }

    @Test
    fun `relative symlink escaping multiple levels is rejected`() {
        // ./a/b/file -> ../../../outside: anchor=["a","b"], target=["..","..","..","outside"]
        // Walk: a(+1)=1, b(+1)=2, ..(1)=1, ..(0)=0, ..(-1)=-1 → escapes
        val result = validator.checkEntry(
            name = "./a/b/file",
            isSymlink = true,
            linkTarget = "../../../outside"
        )
        assertNotNull("Relative traversal ../../../outside from ./a/b/file should be rejected", result)
    }

    @Test
    fun `relative symlink escaping from root entry is rejected`() {
        // ./file -> ../etc/shadow: anchor=[], target=["..","etc","shadow"]
        // Walk: ..(-1)=-1 → escapes
        val result = validator.checkEntry(
            name = "./file",
            isSymlink = true,
            linkTarget = "../etc/shadow"
        )
        assertNotNull("Relative traversal ../etc/shadow from ./file should be rejected", result)
    }

    @Test
    fun `relative symlink escaping with many dotdot segments is rejected`() {
        // ./a/file -> ../../../../etc/shadow: anchor=["a"], target=["..","..","..","..","etc","shadow"]
        // Walk: a(+1)=1, ..(0)=0, ..(-1)=-1 → escapes
        val result = validator.checkEntry(
            name = "./a/file",
            isSymlink = true,
            linkTarget = "../../../../etc/shadow"
        )
        assertNotNull(result)
    }

    // ── Rejected: absolute symlink with traversal ────────────────────

    @Test
    fun `absolute symlink with leading dotdot is rejected`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = "/../../etc/passwd"
        )
        assertNotNull("Absolute symlink with /.. traversal should be rejected", result)
    }

    @Test
    fun `absolute symlink with embedded dotdot is rejected`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = "/bin/../../etc/shadow"
        )
        assertNotNull("Absolute symlink with embedded /../ should be rejected", result)
    }

    // ── Rejected: other attacks ──────────────────────────────────────

    @Test
    fun `symlink target with null byte is rejected`() {
        val result = validator.checkEntry(
            name = "./usr/bin/yes",
            isSymlink = true,
            linkTarget = "/bin/busybox\u0000/etc/passwd"
        )
        assertNotNull("Null byte in symlink target should be rejected", result)
    }

    @Test
    fun `symlink with backslash traversal is rejected`() {
        // ./bin/sh -> ..\..\etc\passwd: after normalization, target resolves
        // to ../../etc/passwd from bin/ → depth goes negative → escapes.
        val result = validator.checkEntry(
            name = "./bin/sh",
            isSymlink = true,
            linkTarget = "..\\..\\etc\\passwd"
        )
        assertNotNull("Backslash traversal should be rejected", result)
    }

    // ── Allowed: systemd-escaped names on the symlink side ───────────

    @Test
    fun `symlink whose own name carries systemd escaping is allowed`() {
        // §1.147: target-side backslash handling was already covered; the
        // symlink *name* was not. A systemd-escaped unit name with two
        // "\xNN" escapes is one ordinary path segment and must not be
        // rejected just because it is the name of a symlink.
        val result = validator.checkEntry(
            name = "./etc/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice",
            isSymlink = true,
            linkTarget = "/usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice"
        )
        assertNull("Escaped unit name on a symlink should be allowed", result)
    }

    // ── Path traversal (entry name, not symlink) ────────────────────

    @Test
    fun `entry with absolute path is rejected`() {
        val result = validator.checkEntry(
            name = "/usr/bin/yes",
            isSymlink = false,
            linkTarget = null
        )
        assertNotNull("Absolute entry path should be rejected", result)
    }

    @Test
    fun `entry with parent traversal is rejected`() {
        val result = validator.checkEntry(
            name = "../../etc/passwd",
            isSymlink = false,
            linkTarget = null
        )
        assertNotNull("Entry path with traversal should be rejected", result)
    }

    @Test
    fun `entry with null byte is rejected`() {
        val result = validator.checkEntry(
            name = "usr/bin/yes\u0000/etc/passwd",
            isSymlink = false,
            linkTarget = null
        )
        assertNotNull("Null byte in entry name should be rejected", result)
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    fun `entry starting with dot-dot-slash is rejected`() {
        val result = validator.checkEntry(
            name = "./../../etc/passwd",
            isSymlink = false,
            linkTarget = null
        )
        assertNotNull(result)
    }

    @Test
    fun `deeply nested entry with traversal is rejected`() {
        val result = validator.checkEntry(
            name = "./a/b/c/../../../../../etc/shadow",
            isSymlink = false,
            linkTarget = null
        )
        assertNotNull(result)
    }
}
