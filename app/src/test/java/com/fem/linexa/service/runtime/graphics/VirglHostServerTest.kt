package com.fem.linexa.service.runtime.graphics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2026-09 GPU work: the pure provisioning logic — availability probe, host
 * command/env construction, and the guest/host socket-path pairing that the
 * R-5 `/run` bind makes a single directory. All plain-JUnit; no Android.
 */
class VirglHostServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Availability probe ────────────────────────────────────────────────

    @Test
    fun `probe reports the full triplet as available`() {
        val dir = tmp.newFolder("native")
        File(dir, VirglHostServer.BINARY_FILE).writeText("x")
        File(dir, VirglHostServer.LIB_VIRGL_FILE).writeText("x")
        File(dir, VirglHostServer.LIB_EPOXY_FILE).writeText("x")

        val availability = VirglHostServer.probe(dir.absolutePath)

        assertTrue(availability.isAvailable)
        assertEquals(File(dir, VirglHostServer.BINARY_FILE).absolutePath, availability.binaryPath)
        assertTrue(availability.missing.isEmpty())
    }

    @Test
    fun `probe on an empty directory names every missing artifact`() {
        val dir = tmp.newFolder("empty")

        val availability = VirglHostServer.probe(dir.absolutePath)

        assertFalse(availability.isAvailable)
        assertEquals(null, availability.binaryPath)
        assertEquals(
            listOf(
                VirglHostServer.BINARY_FILE,
                VirglHostServer.LIB_VIRGL_FILE,
                VirglHostServer.LIB_EPOXY_FILE
            ),
            availability.missing
        )
    }

    @Test
    fun `probe with a missing library reports only that library`() {
        val dir = tmp.newFolder("partial")
        File(dir, VirglHostServer.BINARY_FILE).writeText("x")
        File(dir, VirglHostServer.LIB_VIRGL_FILE).writeText("x")

        val availability = VirglHostServer.probe(dir.absolutePath)

        assertFalse(availability.isAvailable)
        assertEquals(listOf(VirglHostServer.LIB_EPOXY_FILE), availability.missing)
    }

    @Test
    fun `probe on a nonexistent directory degrades instead of throwing`() {
        val availability = VirglHostServer.probe("/nonexistent/native/dir")

        assertFalse(availability.isAvailable)
        assertEquals(3, availability.missing.size)
    }

    // ── Host command / env ────────────────────────────────────────────────

    @Test
    fun `command pins the backend flag and the exact socket path`() {
        val socketDir = tmp.newFolder("sock")

        val command = VirglHostServer.buildCommand("/lib/lib__bin__virgl_test_server__.so", socketDir)

        assertEquals(
            listOf(
                "/lib/lib__bin__virgl_test_server__.so",
                "--angle-gl",
                "--socket-path",
                File(socketDir, VirglHostServer.SOCKET_FILE_NAME).absolutePath
            ),
            command
        )
    }

    @Test
    fun `host env resolves the bundled libraries from nativeLibraryDir`() {
        val env = VirglHostServer.buildHostEnv("/data/app/lib/arm64")

        assertEquals(mapOf("LD_LIBRARY_PATH" to "/data/app/lib/arm64"), env)
    }

    // ── Socket path pairing (the R-5 invariant) ───────────────────────────

    @Test
    fun `host socket dir sits under the rootfs run directory`() {
        val hostDir = VirglHostServer.hostSocketDir("/workspaces/ws1/rootfs")

        assertEquals("/workspaces/ws1/rootfs/run/linexa-gpu", hostDir.path)
        assertEquals(
            // The R-5 bind maps {rootfs}/run ↔ /run, so the guest view of the
            // socket file must be exactly the host path with the rootfs
            // prefix stripped. This equality is the whole feature's glue.
            hostDir.path.removeSuffix("/run/linexa-gpu") + VirglHostServer.GUEST_SOCKET_PATH,
            hostDir.path + "/" + VirglHostServer.SOCKET_FILE_NAME
        )
    }

    @Test
    fun `guest env names the virpipe driver and the guest socket`() {
        val env = VirglHostServer.guestLaunchEnv()

        assertEquals(
            mapOf(
                "GALLIUM_DRIVER" to "virpipe",
                "VTEST_SOCKET_NAME" to VirglHostServer.GUEST_SOCKET_PATH
            ),
            env
        )
        // The socket the guest connects to must live under /run — the only
        // path the existing R-5 bind already exposes.
        assertTrue(VirglHostServer.GUEST_SOCKET_PATH.startsWith("/run/"))
    }
}
