package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §26.2 (terminal audit) — the guest launch env must advertise truecolour.
 *
 * ## Why this needs a test at all
 *
 * `COLORTERM` is a *capability claim made to software we do not control*, and
 * that is what makes it worth pinning. `TERM=xterm-256color` cannot express
 * 24-bit colour — there is no terminfo capability for it — so tools that can
 * emit truecolour (`bat`, `delta`, several `fastfetch` themes) read this
 * de-facto-standard variable instead and silently fall back to 256 indexed
 * colours when it is absent. The audit verified from terminal-lib's bytecode
 * that the emulator genuinely implements truecolour
 * (`CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND = 512` / `_BACKGROUND = 1024`,
 * `TextStyle.encode`'s 24-bit path), so the claim is true — it was just never
 * made.
 *
 * A regression here is **invisible in every other test and silently wrong on
 * device**: nothing fails, no shell breaks, no log line appears. Colours are
 * merely less rich than the emulator supports, which is exactly the shape of
 * the audit's "incomplete colours" report. Deleting the line to tidy the env
 * map would therefore cost nothing that any existing assertion notices, which
 * is the definition of a line that needs a test.
 *
 * ## What is asserted, and what deliberately is not
 *
 * The assertions go through the REAL [PRootContainerRuntime] →
 * [PRootLauncher.buildLaunchSpec] path against [FakeProot], so they pin the env
 * a workspace terminal *actually launches with*, not the literal in the source.
 *
 * Two properties beyond "the key is present":
 *
 *  - **`TERM` is unchanged.** `COLORTERM` supplements it; a future change that
 *    "upgraded" `TERM` to something truecolour-flavoured would break guests
 *    whose terminfo database only has `xterm-256color` — and Alpine has no
 *    terminfo at all (device report §18.6), so `TERM` is the more fragile of
 *    the two and must not drift as a side effect of this one.
 *
 *  - **`COLORTERM` is NOT host-protected.** It is deliberately absent from
 *    [BootstrapManager.HOST_ENV_KEYS] — those exist to stop guest-authored
 *    `config.yaml` from hijacking Linexa's own proot/tmux plumbing (§1.49), and
 *    a colour hint is not plumbing. An imported image whose tooling misbehaves
 *    on truecolour must be able to override or clear it. The override path is
 *    asserted end-to-end rather than by reading the constant, because a key
 *    added to `HOST_ENV_KEYS` would silently start being *dropped with a
 *    warning* instead of applied.
 *
 * The management terminal's copy of this env lives in
 * `ManagementTerminalViewModel` and is covered by
 * [ManagementTerminalColorEnvSourceTest] — it builds its env inline in an
 * `AndroidViewModel`, with no seam this harness can reach.
 */
class PRootLaunchColorEnvTest {

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

    private fun openSession(workspaceId: String = "ws-color"): Unit = runBlocking {
        harness.runtime.openTerminal(
            workspaceId,
            harness.fakeRootfs().absolutePath,
            DEFAULT_TERMINAL_ID
        , asRoot = false)
    }

    private fun writeConfig(workspaceId: String, yaml: String) {
        val dir = File(harness.filesDir, "workspaces/$workspaceId")
        dir.mkdirs()
        File(dir, "config.yaml").writeText(yaml)
    }

    @Test
    fun guestLaunchEnvAdvertisesTruecolourAlongsideTerm() {
        openSession()

        val env = harness.createCalls.single().env
        assertEquals(
            "the guest launch env must advertise 24-bit colour — terminal-lib's emulator " +
                "implements truecolour, and TERM alone cannot say so, so tools that gate on " +
                "COLORTERM downgrade to 256 colours without it (§26.2)",
            "truecolor",
            env["COLORTERM"]
        )
        assertEquals(
            "TERM must stay xterm-256color — COLORTERM supplements it and must never " +
                "replace it; guests resolve TERM against their own terminfo database, and " +
                "some ship none at all",
            "xterm-256color",
            env["TERM"]
        )
    }

    @Test
    fun truecolourHintIsNotHostProtectedAndAnImageMayOverrideIt() {
        // An image whose tooling renders badly under truecolour must be able to
        // opt out. If COLORTERM were ever added to HOST_ENV_KEYS this override
        // would be dropped with a warning instead of applied — silently, from
        // the image author's point of view.
        assertFalse(
            "COLORTERM is a guest-facing capability hint, not host plumbing — it must not " +
                "be in HOST_ENV_KEYS (§1.49 protects proot/tmux infrastructure keys only)",
            "COLORTERM" in BootstrapManager.HOST_ENV_KEYS
        )

        writeConfig(
            "ws-color",
            """
            linexa:
              env:
                COLORTERM: 24bit
            """.trimIndent()
        )

        openSession()

        val env = harness.createCalls.single().env
        // A distinct non-empty value, not the empty string: clearing it would
        // read as `""` whether the launcher had set the default or never set it
        // at all, so an emptied override would pass this test even with the fix
        // reverted. Overriding to a different value can only pass if the
        // launcher's default is really there and really overridable.
        assertEquals(
            "a workspace's own config.yaml must be able to replace the colour hint",
            "24bit",
            env["COLORTERM"]
        )
        assertTrue(
            "overriding the colour hint must not disturb TERM",
            env["TERM"] == "xterm-256color"
        )
    }
}
