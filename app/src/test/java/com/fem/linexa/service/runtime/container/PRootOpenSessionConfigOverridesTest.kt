package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.73 — Roadmap Item 7: end-to-end JVM tests for the config.yaml launch
 * overrides (shell, shellArgs, bindMounts) through the REAL
 * [PRootContainerRuntime] launch path against the fake PRoot stand-in
 * ([FakeProot]).
 *
 * These assert the actual wiring — config.yaml is read by
 * [PRootContainerRuntime], the shell/args/binds flow into
 * [PRootLauncher.buildLaunchSpec]'s argv, the env merge still goes through
 * the HOST_ENV_KEYS filter — and the §1.73 must-not-breaks:
 *
 *  - no config.yaml → default `resolveShellPath` fallback + `--login`
 *    (byte-identical argv to pre-§1.73),
 *  - `launch.shell` without `launch.shellArgs` keeps the default `--login`,
 *  - one-shot `exec` keeps the fixed `/bin/sh -c` bridge (shell override is
 *    interactive-only) but DOES get the extra binds,
 *  - a tampered/unparseable config.yaml fails open to defaults.
 */
class PRootOpenSessionConfigOverridesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    /** Writes a persisted config.yaml at the workspace path the runtime reads. */
    private fun writeConfig(workspaceId: String, yaml: String) {
        val dir = File(harness.filesDir, "workspaces/$workspaceId")
        dir.mkdirs()
        File(dir, "config.yaml").writeText(yaml)
    }

    private fun openSession(workspaceId: String = "ws-1"): Unit = runBlocking {
        harness.runtime.openTerminal(workspaceId, harness.fakeRootfs().absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
    }

    @Test
    fun launchOverridesReachThePtyArgv() {
        writeConfig(
            "ws-1",
            """
            linexa:
              env:
                MY_KEY: my-value
                PATH: should-be-dropped
              launch:
                shell: /bin/zsh
                shellArgs:
                  - --login
                  - --no-globalrc
                bindMounts:
                  - host: /sdcard/foo
                    guest: /mnt/foo
            """.trimIndent()
        )

        openSession("ws-1")

        val call = harness.createCalls.single()
        val args = call.args
        // command = override shell + override args, at the end of argv
        assertEquals(
            "launch.shell + launch.shellArgs must be the final argv entries",
            listOf("/bin/zsh", "--login", "--no-globalrc"),
            args.takeLast(3)
        )
        // extra binds must appear as typed -b host:guest tokens
        val bindIndex = args.indexOf("-b")
        assertTrue("extra bind must be present", bindIndex >= 0)
        assertTrue("extra bind token must be typed", args.contains("/sdcard/foo:/mnt/foo"))
        assertTrue(
            "extra bind must precede the command",
            args.indexOf("/sdcard/foo:/mnt/foo") < args.indexOf("/bin/zsh")
        )
        // env override applied; host-owned PATH untouched (§1.49 discipline)
        assertEquals("my-value", call.env["MY_KEY"])
        assertNotEquals("PATH must stay host-launcher-owned", "should-be-dropped", call.env["PATH"])
    }

    @Test
    fun shellOnlyOverrideKeepsDefaultLoginArg() {
        writeConfig(
            "ws-1",
            """
            linexa:
              launch:
                shell: /bin/ash
            """.trimIndent()
        )

        openSession("ws-1")

        val args = harness.createCalls.single().args
        assertEquals(
            "empty shellArgs must keep the default --login",
            listOf("/bin/ash", "--login"),
            args.takeLast(2)
        )
    }

    @Test
    fun noConfigKeepsResolveShellPathFallback() {
        // No config.yaml at all — must behave byte-identically to pre-§1.73:
        // resolveShellPath over the empty fake rootfs → /bin/sh + --login,
        // no extra binds.
        openSession("ws-1")

        val args = harness.createCalls.single().args
        assertEquals(
            "default fallback shell + --login must be preserved",
            listOf("/bin/sh", "--login"),
            args.takeLast(2)
        )
        assertTrue("no config must add no binds", !args.contains("/mnt/foo:/mnt/foo"))
        // ── RE-BASELINED 2026-08-26 (host-info track; explicit re-decision) ──
        // The old pin asserted exactly FOUR -b tokens (/dev, rootfs /dev/shm,
        // /proc, /sys). That was true pre-R-1..R-6 only: Runtime-Parity (Track
        // F, same working tree) DELIBERATELY grew the default bind set —
        // /proc/self/fd + stdin/out/err, /dev/urandom:/dev/random, /run — and
        // pre-existing in pristine tree runs already counted 10 here. Counting
        // a total is brittle against documented growth; the invariant worth
        // pinning is the SET of host-owned tokens. Nothing below is authored
        // by config, and nothing here comes from Track G (the bridge adds ZERO
        // -b argv tokens — it only rewrites overlay file CONTENT).
        check(args.count { it == "-b" } >= 4) { "at minimum the historic four binds must remain" }
        assertTrue("the /dev bind must stay", args.contains("/dev"))
        assertTrue("the /proc bind must stay", args.contains("/proc"))
        assertTrue("the /sys bind must stay", args.contains("/sys"))
        assertTrue(
            "the F-5 /dev/shm bind must be a typed rootfs-absolute token",
            args.any { it.endsWith("/dev/shm:/dev/shm") }
        )
        assertTrue(
            "the R-3 fd-bind family must be present",
            args.contains("/proc/self/fd:/dev/fd")
        )
        assertTrue(
            "the R-4 urandom→random mapping must be present",
            args.contains("/dev/urandom:/dev/random")
        )
        assertTrue(
            "the R-5 /run bind must be present",
            args.any { it.endsWith(":/run") }
        )
    }

    @Test
    fun tamperedConfigFailsOpenToDefaults() {
        // Fail-open: an unparseable config.yaml at launch (tampering after
        // import) must degrade to defaults, not refuse to start.
        writeConfig("ws-1", "linexa: [not: a: valid: launch: [shape")

        openSession("ws-1")

        val args = harness.createCalls.single().args
        assertEquals(
            "tampered config must degrade to the default shell",
            listOf("/bin/sh", "--login"),
            args.takeLast(2)
        )
    }

    @Test
    fun execAppliesExtraBindsButKeepsShCatchAllBridge() {
        writeConfig(
            "ws-1",
            """
            linexa:
              env:
                MY_KEY: my-value
              launch:
                shell: /bin/zsh
                shellArgs: ["--login"]
                bindMounts:
                  - host: /sdcard/foo
                    guest: /mnt/foo
            """.trimIndent()
        )

        val result = runBlocking { harness.runtime.exec("ws-1", harness.fakeRootfs().absolutePath, "true") }

        assertEquals(0, result.exitCode)
        val invocation = FakeProot.invocations(prootDir).single()
        // one-shot exec must NOT use the interactive shell override
        assertEquals(
            "exec must keep the fixed /bin/sh -c bridge",
            listOf("/bin/sh", "-c", "true"),
            invocation.argv.takeLast(3)
        )
        // but extra binds DO apply to one-shot execs
        assertTrue("exec must carry the extra bind", invocation.argv.contains("/sdcard/foo:/mnt/foo"))
        assertTrue(
            "exec bind must precede the command",
            invocation.argv.indexOf("/sdcard/foo:/mnt/foo") < invocation.argv.indexOf("/bin/sh")
        )
        assertEquals("exec env override must apply", "my-value", invocation.env["MY_KEY"])
    }
}