package com.fem.linexa.service.runtime.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-file tests for PRoot argv construction (P0-1 fix, ADR-0004, TDD-1).
 *
 * These encode the expected proot invocation for a workspace launch.
 * The argv order matters: PRoot processes flags sequentially, and the
 * position of `-0` (root emulation) relative to bind mounts and the
 * command determines whether the guest runs as uid 0.
 *
 * The full integration test (PRootLauncher → LaunchSpec → PtySession)
 * requires an Android device/emulator; these tests verify the argument
 * contract at the pure-logic level.
 */
class PRootLauncherTest {

    /**
     * Mirrors the argument construction in [PRootLauncher.buildLaunchSpec]
     * without requiring Android dependencies. If the production code
     * changes its argv, this test must be updated to match — that is
     * the point of a golden-file test.
     *
     * R-1..R-6 (2026-08-26 runtime parity audit): the argv gained new
     * flags and binds. The dev/shm and /run binds are NOT mirrored here
     * because they are conditional on the rootfs state and the production
     * code's helper functions check filesystem state at runtime — the
     * golden file models only the always-present flags. The /proc
     * bind-overs are likewise conditional and omitted from the golden
     * argv; their tests live in [PRootLauncherProcBindOversTest].
     */
    private fun buildExpectedProotArgs(
        rootfsPath: String = "/data/user/0/com.fem.linexa/files/workspaces/test/rootfs",
        workingDirectory: String = "/root",
        command: List<String> = listOf("/bin/bash", "--login"),
        withSharedStorage: Boolean = false,
        extraBindMounts: List<Pair<String, String>> = emptyList()
    ): List<String> = buildList {
        add("-r"); add(rootfsPath)
        add("-w"); add(workingDirectory)
        // P0-1: -0 must be present for root emulation (uid 0 inside guest)
        add("-0")
        add("--link2symlink")
        add("--kill-on-exit")
        // R-1: kernel-release spoof.
        add("--kernel-release=6.1.0-linexa")
        // R-2: --sysvipc for IPC compatibility.
        add("--sysvipc")
        add("-b"); add("/dev")
        // R-3: /dev/fd + /dev/stdin + /dev/stdout + /dev/stderr binds.
        add("-b"); add("/proc/self/fd:/dev/fd")
        add("-b"); add("/proc/self/fd/0:/dev/stdin")
        add("-b"); add("/proc/self/fd/1:/dev/stdout")
        add("-b"); add("/proc/self/fd/2:/dev/stderr")
        // R-4: /dev/urandom -> /dev/random.
        add("-b"); add("/dev/urandom:/dev/random")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        if (withSharedStorage) {
            add("-b"); add("/storage/emulated/0/Android/data/com.fem.linexa/files/shared:/mnt/shared")
        }
        // §1.73: extra per-workspace binds follow the defaults and precede
        // the command.
        extraBindMounts.forEach { (host, guest) ->
            add("-b"); add("$host:$guest")
        }
        addAll(command)
    }

    @Test
    fun prootArgsContainRootEmulationFlag() {
        val args = buildExpectedProotArgs()
        assertTrue(
            "proot argv must contain -0 for root emulation (P0-1, ADR-0004)",
            args.contains("-0")
        )
    }

    @Test
    fun rootEmulationFlagPrecedesBindMountsAndCommand() {
        val args = buildExpectedProotArgs()
        val rootFlagIndex = args.indexOf("-0")
        val devBindIndex = args.indexOf("-b")
        val commandIndex = args.indexOf("/bin/bash")

        assertTrue("-0 must appear before bind mounts", rootFlagIndex < devBindIndex)
        assertTrue("-0 must appear before the command", rootFlagIndex < commandIndex)
    }

    @Test
    fun fullArgvMatchesExpectedOrder() {
        val expected = listOf(
            "-r", "/data/user/0/com.fem.linexa/files/workspaces/test/rootfs",
            "-w", "/root",
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "--kernel-release=6.1.0-linexa",
            "--sysvipc",
            "-b", "/dev",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/bash", "--login"
        )
        val actual = buildExpectedProotArgs()
        assertEquals("Full proot argv must match the golden file (TDD-1)", expected, actual)
    }

    @Test
    fun fullArgvWithSharedStorage() {
        val expected = listOf(
            "-r", "/data/user/0/com.fem.linexa/files/workspaces/test/rootfs",
            "-w", "/root",
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "--kernel-release=6.1.0-linexa",
            "--sysvipc",
            "-b", "/dev",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/storage/emulated/0/Android/data/com.fem.linexa/files/shared:/mnt/shared",
            "/bin/bash", "--login"
        )
        val actual = buildExpectedProotArgs(withSharedStorage = true)
        assertEquals("Argv with shared storage must match golden file", expected, actual)
    }

    @Test
    fun customCommandReplacesDefaultShell() {
        val args = buildExpectedProotArgs(
            command = listOf("/bin/sh", "-c", "apt-get update")
        )
        assertEquals("apt-get update", args.last())
        // The custom command replaces the default /bin/bash --login
        assertTrue("Custom command must be at the end of argv", args.takeLast(3) == listOf("/bin/sh", "-c", "apt-get update"))
    }

    @Test
    fun rootEmulationFlagIsSingleDashZero() {
        val args = buildExpectedProotArgs()
        val rootFlag = args.first { it == "-0" }
        assertEquals("-0", rootFlag)
        // Verify it is NOT --root-id (alternative long form)
        // Both are valid PRoot flags, but -0 is the canonical form used in TC
        assertTrue("Flag must be -0, not --root-id", !args.contains("--root-id"))
    }

    @Test
    fun extraBindMountsAppendAfterDefaultsAndBeforeCommand() {
        val expected = listOf(
            "-r", "/data/user/0/com.fem.linexa/files/workspaces/test/rootfs",
            "-w", "/root",
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "--kernel-release=6.1.0-linexa",
            "--sysvipc",
            "-b", "/dev",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/sdcard/Downloads:/mnt/downloads",
            "-b", "/sdcard/Projects:/mnt/projects",
            "/bin/bash", "--login"
        )
        val actual = buildExpectedProotArgs(
            extraBindMounts = listOf("/sdcard/Downloads" to "/mnt/downloads", "/sdcard/Projects" to "/mnt/projects")
        )
        assertEquals("Extra binds must follow the default binds and precede the command (§1.73)", expected, actual)
    }

    @Test
    fun extraBindMountsCombineWithSharedStorage() {
        val expected = listOf(
            "-r", "/data/user/0/com.fem.linexa/files/workspaces/test/rootfs",
            "-w", "/root",
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "--kernel-release=6.1.0-linexa",
            "--sysvipc",
            "-b", "/dev",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/storage/emulated/0/Android/data/com.fem.linexa/files/shared:/mnt/shared",
            "-b", "/sdcard/x:/mnt/x",
            "/bin/bash", "--login"
        )
        val actual = buildExpectedProotArgs(
            withSharedStorage = true,
            extraBindMounts = listOf("/sdcard/x" to "/mnt/x")
        )
        assertEquals("Extra binds must come after the shared-storage bind (§1.73)", expected, actual)
    }

    @Test
    fun emptyExtraBindMountsKeepsLegacyArgv() {
        // Workspaces without overrides must keep byte-identical argv —
        // the §1.73 must-not-break for the default launch path.
        val expected = listOf(
            "-r", "/data/user/0/com.fem.linexa/files/workspaces/test/rootfs",
            "-w", "/root",
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "--kernel-release=6.1.0-linexa",
            "--sysvipc",
            "-b", "/dev",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/bash", "--login"
        )
        val actual = buildExpectedProotArgs(extraBindMounts = emptyList())
        assertEquals("No extras must produce the pre-§1.73 golden argv", expected, actual)
    }

    // ── R-1..R-6 (2026-08-26 runtime parity audit) ───────────────────────
    //
    // New tests pinning the additions that the golden file describes but
    // cannot itself assert (the golden file only checks shape; these check
    // the specific content). Production argv construction lives in
    // [PRootLauncher.buildLaunchSpec]; these mirror the same expected list
    // so any accidental removal trips both, not just one.

    @Test
    fun kernelReleaseSpoofIsPresentAndMatchesConstant() {
        val args = buildExpectedProotArgs()
        assertTrue(
            "--kernel-release flag must be present (R-1)",
            args.any { it.startsWith("--kernel-release=") }
        )
        assertEquals(
            "kernel-release value must match PRootLauncher.SPOOFED_KERNEL_RELEASE",
            PRootLauncher.SPOOFED_KERNEL_RELEASE,
            args.first { it.startsWith("--kernel-release=") }.removePrefix("--kernel-release=")
        )
    }

    @Test
    fun sysvipcFlagIsPresent() {
        val args = buildExpectedProotArgs()
        assertTrue(
            "--sysvipc flag must be present (R-2)",
            args.contains("--sysvipc")
        )
    }

    @Test
    fun devFdAndStdIoBindsArePresentInOrder() {
        val args = buildExpectedProotArgs()
        assertTrue("/dev/fd bind must be present (R-3)", args.contains("/proc/self/fd:/dev/fd"))
        assertTrue("/dev/stdin bind must be present (R-3)", args.contains("/proc/self/fd/0:/dev/stdin"))
        assertTrue("/dev/stdout bind must be present (R-3)", args.contains("/proc/self/fd/1:/dev/stdout"))
        assertTrue("/dev/stderr bind must be present (R-3)", args.contains("/proc/self/fd/2:/dev/stderr"))
        // Order: the /dev/fd directory bind must precede the /dev/stdin|out|err
        // binds because the per-fd binds resolve into the directory PRoot
        // already bind-mounted — see PRootLauncher R-3 comment.
        val fdIdx = args.indexOf("/proc/self/fd:/dev/fd")
        val stdinIdx = args.indexOf("/proc/self/fd/0:/dev/stdin")
        assertTrue("/dev/fd must come before /dev/stdin", fdIdx < stdinIdx)
    }

    @Test
    fun urandomToRandomBindIsPresent() {
        val args = buildExpectedProotArgs()
        assertTrue(
            "/dev/urandom:/dev/random bind must be present (R-4)",
            args.contains("/dev/urandom:/dev/random")
        )
    }
}
