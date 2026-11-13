package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.installer.GuestRootfsInitializer
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TextStyle
import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2026-08-31 (terminal appearance) — the default terminal color scheme is
 * provisioned into the guest before any session opens.
 *
 * ## The device defect this pins
 *
 * The vendored terminal stack renders the emulator's OWN palette
 * (`TerminalView.onDraw` fills the canvas from
 * `mCurrentColors[COLOR_INDEX_BACKGROUND]`; the emulator copies the static
 * `TerminalColors.COLOR_SCHEME` at construction). `TermuxPtySession` applies
 * `<rootfs><HOME>/.termux/colors.properties` into that scheme — but nothing
 * ever created the file, so every session started from the vendored upstream
 * default: background `#000000`, foreground white. A fresh shell prints only
 * its prompt, every other cell is default-background (skipped by the
 * renderer), and the visible result was the on-device "new session is a
 * black rectangle" report — pixel-verified as exactly #000000 with a white
 * prompt/cursor drawn by the TerminalView itself, NOT a Compose overlay or a
 * z-order/layout failure (the UI hierarchy dumps show the TerminalView
 * attached, sized, uncovered; the render logs show updateSize BIND → onDraw
 * → RepaintBridge revision flow, all healthy).
 *
 * ## What is pinned, and the boundary that must not drift
 *
 *  - The scheme file appears under the guest home the pty layer actually
 *    reads (`PRootLauncher.DEFAULT_GUEST_HOME` = /root, resolved to its HOST
 *    path — asserted against `CreateCall.terminalConfigHome`).
 *  - The written file parses to the pinned Haven-terminal fixture values
 *    (`TerminalColorSchemePropertiesTest`: fg #C9CED4 / bg #0A0E0F /
 *    cursor #FFFFFF) — delivered as TERMINAL-OWNED configuration, never as
 *    a Haven theme token and never as a hardcoded view color.
 *  - Write-once: a workspace that ships (or a user who edits) its own
 *    colors.properties is never overwritten.
 */
class PRootTerminalColorsProvisionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        val prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        rootfs = harness.fakeRootfs()
    }

    private fun openSession(workspaceId: String = "ws-colors"): Unit = runBlocking {
        harness.runtime.openTerminal(workspaceId, rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
    }

    // Issue #1 (2026-09-03): the scheme lives in the SESSION's home —
    // /home/linexa (the resolved normal-user home), not /root.
    private val sessionHomeRelative = "home/linexa/.termux"

    private fun schemeFile(): File = File(rootfs, "$sessionHomeRelative/colors.properties")

    @Test
    fun sessionOpenProvisionsTheDefaultTerminalSchemeWhenTheWorkspaceShipsNone() {
        openSession()

        val file = schemeFile()
        assertTrue(
            "a workspace without .termux/colors.properties must be provisioned with the default terminal scheme at " +
                "$sessionHomeRelative/colors.properties (the session home — issue #1) — without it every emulator " +
                "starts from the vendored default palette (background #000000) and a fresh shell renders as a black screen",
            file.isFile
        )

        // The file must parse into exactly the pinned Haven-terminal fixture
        // values through the SAME apply path TermuxPtySession uses.
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val scheme = TerminalColorScheme()
        scheme.updateWith(props)
        assertEquals(0xFFC9CED4.toInt(), scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(0xFF0A0E0F.toInt(), scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(0xFFFFFFFF.toInt(), scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR])

        // …and the pty must have been pointed at exactly that directory.
        val home = harness.createCalls.single().terminalConfigHome
        assertEquals(
            "the session's terminalConfigHome must resolve to the guest home inside this rootfs — " +
                "the pty reads .termux/colors.properties from there, so provisioning and reading must agree",
            File(rootfs, "home/linexa").absolutePath,
            home
        )
    }

    @Test
    fun aWorkspaceProvidedSchemeIsNeverOverwritten() {
        val file = schemeFile()
        file.parentFile.mkdirs()
        val userScheme = "background = #123456\nforeground = #ABCDEF\n"
        file.writeText(userScheme)

        openSession()

        assertEquals(
            "terminal colors are terminal-owned configuration: an existing colors.properties " +
                "(image-authored or user-edited) must be left byte-for-byte alone",
            userScheme,
            file.readText()
        )
    }

    @Test
    fun provisioningIsIdempotentAcrossSessionOpens() {
        openSession("ws-idem")
        val first = schemeFile().readBytes()
        assertTrue("first open must provision the scheme file", schemeFile().isFile)

        openSession("ws-idem")
        assertTrue(
            "the provisioned file must survive a second open byte-identical (no rewrite churn, no OSC-hostility)",
            first.contentEquals(schemeFile().readBytes())
        )
    }

    @Test
    fun provisionedSchemeIsAStrictTermuxColorsFile() {
        openSession("ws-strict")

        val props = Properties()
        schemeFile().inputStream().use { props.load(it) }
        // The apply path is STRICT (TermuxPtySession discards the file on any
        // unknown key/invalid value), so the shipped default must only use
        // recognized keys and parseable XTerm color specs.
        val scheme = TerminalColorScheme()
        scheme.updateWith(props)
        assertTrue(
            "the provisioned default must define the default background explicitly — that is the whole fix",
            0xFF0A0E0F.toInt() == scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]
        )
    }
}
