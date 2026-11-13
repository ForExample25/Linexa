package com.fem.linexa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * §1.147 — real-archive entry-name inventory guard for [RealArchiveValidator].
 *
 * A Fedora/Arch install failure was a host-side
 * validator rejecting a legitimate entry name. Nothing in the suite fed the
 * validator names harvested from real rootfs images, so the bug — and two
 * earlier occurrences of the same class — could not be caught
 * by a unit test.
 *
 * This test closes that gap without checking in archives (the Arch sample is
 * ~180 MB): it feeds the validator a fixture of the *interesting* entry names
 * taken from the five sampled images and asserts zero rejections, then asserts
 * a curated attack list is still fully rejected. The two directions live in one
 * class deliberately, so a change that widens acceptance cannot quietly stop
 * proving that traversal is still refused.
 *
 * Inventory provenance (verified against the archives):
 *
 * | image                     | entries | names with `\` | any name with 2+ `\` |
 * |---------------------------|---------|----------------|----------------------|
 * | debian-rootfs-arm64       |  12776  | 1              | no                   |
 * | ubuntu-base-22.04.5-arm64 |   3497  | 0              | no                   |
 * | alpine-minirootfs-3.24.1  |    515  | 0              | no                   |
 * | fedora-44-arm64-rootfs    |  12717  | 3              | yes (1)              |
 * | archlinux-arm64-rootfs    |  45729  | 3              | yes (1)              |
 *
 * Pre-fix, [`every real entry name is accepted`] fails on exactly the Fedora
 * and Arch `mute-console` name; post-fix all five images' names are accepted.
 */
class RealRootFsEntryNameInventoryTest {

    private lateinit var validator: RealArchiveValidator

    @Before
    fun setUp() {
        validator = RealArchiveValidator()
    }

    @Test
    fun `every real entry name is accepted`() {
        val rejected = REAL_ENTRY_NAMES.filter { validator.checkEntry(it) != null }
        assertEquals(
            "Names harvested from real rootfs images must all be accepted, but these were rejected: " +
                rejected.joinToString { "'$it'" },
            emptyList<String>(),
            rejected
        )
    }

    @Test
    fun `every real symlink is accepted`() {
        val rejected = REAL_SYMLINKS.filter { (name, target) ->
            validator.checkEntry(name = name, isSymlink = true, linkTarget = target) != null
        }
        assertEquals(
            "Symlinks harvested from real rootfs images must all be accepted, but these were rejected: " +
                rejected.joinToString { "'${it.first}' -> '${it.second}'" },
            emptyList<Pair<String, String>>(),
            rejected
        )
    }

    @Test
    fun `every curated attack name is rejected`() {
        ATTACK_ENTRY_NAMES.forEach { name ->
            assertNotNull("Attack entry name must be rejected: '$name'", validator.checkEntry(name))
        }
    }

    private companion object {

        /**
         * Entry names present in the five sampled images. Not the full
         * inventory (61k+ names) — every name whose *shape* exercises a
         * validator predicate, plus enough ordinary names to keep the happy
         * path represented. All must be accepted.
         */
        val REAL_ENTRY_NAMES = listOf(
            // The three systemd `\xNN`-escaped unit names. The middle one is
            // the exact entry that aborted the Fedora and Arch installs: one
            // path segment, two literal backslashes, no separator semantics.
            "./usr/lib/systemd/system/system-systemd\\x2dcryptsetup.slice",
            "./usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice",
            "./usr/lib/systemd/system/system-systemd\\x2dveritysetup.slice",
            // Same three without the "./" prefix — tar producers differ on it.
            "usr/lib/systemd/system/system-systemd\\x2dcryptsetup.slice",
            "usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice",
            "usr/lib/systemd/system/system-systemd\\x2dveritysetup.slice",
            // Layout roots checked by validateExtractedRootfs.
            "./",
            "./etc/",
            "./usr/",
            "./var/",
            "./usr/bin/",
            // Ordinary files, including the shells the profile system probes.
            "./usr/bin/bash",
            "./usr/bin/sh",
            "./bin/busybox",
            "./etc/os-release",
            "./etc/apt/sources.list",
            "./usr/share/pkgconfig/systemd.pc",
            // Names with '@' and ':' — common in systemd templates and in
            // Debian multiarch paths, unremarkable but worth pinning.
            "./usr/lib/systemd/system/systemd-mute-console@.service",
            "./usr/lib/systemd/system/getty@.service",
            "./usr/lib/aarch64-linux-gnu/libc.so.6",
            // Dot-bearing names that are NOT traversal (audit §6.4). No
            // sampled image ships one, but they are legal filenames and the
            // substring-vs-segment distinction is the same error class as the
            // root cause, so they are pinned here as well.
            "./usr/lib/libfoo..so",
            "./etc/foo..bar",
            "./usr/share/doc/pkg/changelog..gz",
            // '.' segments in the middle: normalize away, must not be flagged.
            "./usr/./bin/./ls",
            // Deeply nested path — exercises the depth walk without escaping.
            "./usr/lib/python3.13/site-packages/pip/_vendor/urllib3/util/ssl_.py"
        )

        /**
         * Symlinks present in the sampled images. No sampled image has a
         * backslash in a *target* (audit §6.1), so the escaped-name symlink
         * here is the name-side case; the rest are the absolute-target shapes
         * Alpine and Debian rely on heavily.
         */
        val REAL_SYMLINKS = listOf(
            "./bin" to "usr/bin",
            "./usr/bin/yes" to "/bin/busybox",
            "./usr/bin/sh" to "bash",
            "./etc/os-release" to "../usr/lib/os-release",
            "./etc/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice" to
                "/usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice"
        )

        /**
         * Curated attack names. Every one must stay rejected — this is what
         * keeps the acceptance list above from reading as a relaxation.
         */
        val ATTACK_ENTRY_NAMES = listOf(
            "../../etc/passwd",
            "usr/../../etc/passwd",
            "a/b/../../../etc/shadow",
            "/etc/passwd",
            "/absolute/path",
            "\\absolute",
            "\\\\server\\share",
            "usr\\bin\\sh",
            "a\\..\\..\\etc\\passwd",
            "..\\..\\etc\\passwd",
            "usr/..\\..\\etc/passwd",
            "usr/bin/\u0000evil",
            "etc/pas\u0000swd"
        )
    }
}
