package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * Issue #4 / #7 (2026-09-03) — the guest workspace directory and the shell
 * convenience provisioning: both are idempotent managed writes with the same
 * discipline as every other ensure* in [GuestRootfsInitializer].
 */
class GuestRootfsInitializerWorkspaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var initializer: GuestRootfsInitializer
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        val binDir = File(filesDir, "bootstrap/bin")
        require(binDir.mkdirs())
        val chmod = File(binDir, "chmod")
        chmod.writeText("#!/bin/sh\nexit 0\n")
        require(chmod.setExecutable(true))

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))

        rootfs = tmp.newFolder("rootfs")
    }

    // ── workspace directory ──────────────────────────────────────────────

    @Test
    fun `creates the workspace directory and seeds the readme`() {
        assertNull(initializer.ensureGuestWorkspace(rootfs, "ws1", "/home/linexa"))

        val dir = File(rootfs, "home/linexa")
        assertTrue("nested parents created", dir.isDirectory)
        val readme = File(dir, GuestRootfsInitializer.WORKSPACE_README_NAME)
        assertTrue("README seeded", readme.isFile)
        assertEquals(
            GuestRootfsInitializer.WORKSPACE_README_BYTES.toList(),
            readme.readBytes().toList()
        )
    }

    @Test
    fun `is idempotent and preserves user content`() {
        initializer.ensureGuestWorkspace(rootfs, "ws1", "/home/linexa")
        val dir = File(rootfs, "home/linexa")
        val userFile = File(dir, "project.txt")
        userFile.writeText("mine")
        // User edits the README — the ensure pass must NOT clobber it back.
        File(dir, GuestRootfsInitializer.WORKSPACE_README_NAME).writeText("my own readme\n")

        assertNull(initializer.ensureGuestWorkspace(rootfs, "ws1", "/home/linexa"))
        assertEquals("mine", userFile.readText())
        assertEquals("my own readme\n", File(dir, GuestRootfsInitializer.WORKSPACE_README_NAME).readText())
    }

    @Test
    fun `rejects traversal paths before touching the filesystem`() {
        val failure = initializer.ensureGuestWorkspace(rootfs, "ws1", "/home/../etc")
        assertNotNull(failure)
        assertFalse("no traversal target created", File(rootfs, "etc").exists())
    }

    @Test
    fun `rejects relative paths`() {
        assertNotNull(initializer.ensureGuestWorkspace(rootfs, "ws1", "home/linexa"))
    }

    // ── shell config (aliases) ───────────────────────────────────────────

    @Test
    fun `writes the profile d alias script with escaped expansions`() {
        assertNull(initializer.ensureGuestShellConfig(rootfs, "ws1", mapOf("la" to "ls -A")))

        val script = File(rootfs, "etc/profile.d/${GuestRootfsInitializer.SHELL_CONFIG_FILE_NAME}")
        assertTrue(script.isFile)
        val body = script.readText()
        assertTrue(body.startsWith(GuestRootfsInitializer.SHELL_CONFIG_MARKER))
        assertTrue(body.contains("alias la='ls -A'"))
    }

    @Test
    fun `escapes single quotes in alias expansions`() {
        assertNull(
            initializer.ensureGuestShellConfig(
                rootfs, "ws1",
                mapOf("greet" to "echo 'hello'")
            )
        )
        val body = File(
            rootfs, "etc/profile.d/${GuestRootfsInitializer.SHELL_CONFIG_FILE_NAME}"
        ).readText()
        // POSIX single-quote escaping: ' → '\''
        assertTrue(body.contains("alias greet='echo '\\''hello'\\'''"))
    }

    @Test
    fun `rewrites its own managed file but never a foreign one`() {
        val script = File(rootfs, "etc/profile.d/${GuestRootfsInitializer.SHELL_CONFIG_FILE_NAME}")
        val profileDir = script.parentFile!!
        require(profileDir.mkdirs())

        // A foreign file at the same path stays untouched.
        script.writeText("#!/bin/sh\n# someone else's script\nexit 0\n")
        assertNull(initializer.ensureGuestShellConfig(rootfs, "ws1", mapOf("la" to "ls -A")))
        assertTrue(script.readText().contains("someone else's script"))

        // A Linexa-managed file is updated when the alias set changes.
        script.writeText(GuestRootfsInitializer.SHELL_CONFIG_MARKER + "alias old='x'\n")
        assertNull(initializer.ensureGuestShellConfig(rootfs, "ws1", mapOf("la" to "ls -A")))
        assertTrue(script.readText().contains("alias la='ls -A'"))
        assertFalse(script.readText().contains("alias old"))
    }

    @Test
    fun `default alias set is the Termux-like baseline`() {
        val aliases = GuestRootfsInitializer.DEFAULT_SHELL_ALIASES
        assertEquals("ls -A", aliases["la"])
        assertEquals("ls -alF", aliases["ll"])
        assertEquals("ls -CF", aliases["l"])
        // And the merged view (declared wins) behaves additively.
        val merged = aliases + mapOf("la" to "ls -A -B")
        assertEquals("ls -A -B", merged["la"])
        assertEquals("ls -alF", merged["ll"])
    }
}
