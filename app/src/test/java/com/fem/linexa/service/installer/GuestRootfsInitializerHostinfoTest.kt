package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * Phase 15 (2026-08-27 host-bridge expansion): the guest-side
 * `linexa-hostinfo` installer contract. THE point of this surface is the
 * PERMISSIONS — the previously observed on-device artifact existed as
 * `-rw------- root root`, present but unexecutable. The writer must place
 * byte-stable content and set rwxr-xr-x explicitly, every time.
 */
class GuestRootfsInitializerHostinfoTest {

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

    private fun script(): File =
        File(File(File(rootfs, "usr"), "local/bin"), GuestRootfsInitializer.HOSTINFO_HELPER_NAME)

    @Test
    fun `installs helper with explicit rwxr-xr-x`() {
        assertNull(initializer.ensureGuestHostInfo(rootfs, "ws-h1"))

        val file = script()
        assertTrue(file.isFile)
        val perms = Files.getPosixFilePermissions(file.toPath())
        assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE))
        assertTrue(perms.contains(PosixFilePermission.GROUP_EXECUTE))
        assertTrue(perms.contains(PosixFilePermission.OTHERS_EXECUTE))
        assertTrue(perms.containsAll(listOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
        )))
        assertFalse("world/owner writable breaks the read-only-helper posture",
            perms.contains(PosixFilePermission.OTHERS_WRITE))
    }

    @Test
    fun `script bytes start with shebang and are dollar-correct after decode`() {
        initializer.ensureGuestHostInfo(rootfs, "ws-h2")
        val body = script().readText()

        // Guest-native interpreter: PRoot resolves the shebang INSIDE the
        // rootfs, where /system does not exist (and is deliberately not
        // bound) — /bin/sh is the only interpreter every supported guest
        // ships. /system/bin/sh here would make the helper unexecutable.
        assertTrue(body.startsWith("#!/bin/sh"))
        // The « sentinel MUST have been replaced at construction; a leaked
        // one would produce dead shell the moment it runs.
        assertFalse(body.contains('\u00ab'))
        assertTrue(body.contains("CONF=/run/linexa/hostinfo.conf"))
        assertTrue(body.contains("${'$'}{line%%=*}"))
        // Read-only contract: no redirect-to-file verbs anywhere in the tool.
        listOf(">>", "> ", "touch ", "chmod ", "rm ").forEach { forbidden ->
            assertFalse("write-capable token '$forbidden' must not appear", body.contains(forbidden))
        }
    }

    @Test
    fun `idempotent - second run leaves identical bytes`() {
        initializer.ensureGuestHostInfo(rootfs, "ws-h3")
        val first = script().readBytes()

        initializer.ensureGuestHostInfo(rootfs, "ws-h3")
        assertEquals(first.toList(), script().readBytes().toList())
    }

    @Test
    fun `self-heals corrupted or permission-broken installs`() {
        // Simulate the historical bug state EXACTLY: exists, wrong bytes, rw-------.
        val file = script()
        Files.createDirectories(file.parentFile.toPath())
        file.writeText("legacy garbage")
        Files.setPosixFilePermissions(file.toPath(), setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
        ))

        assertNull(initializer.ensureGuestHostInfo(rootfs, "ws-h4"))
        assertEquals(GuestRootfsInitializer.HOSTINFO_HELPER_SCRIPT, script().readText())
        assertTrue(Files.getPosixFilePermissions(script().toPath())
            .contains(PosixFilePermission.OWNER_EXECUTE))
    }
}
