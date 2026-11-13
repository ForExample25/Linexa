package com.fem.linexa.service.runtime.container

import com.fem.linexa.service.rootfs.DeclaredArchitecture
import com.fem.linexa.service.rootfs.DeclaredDistro
import com.fem.linexa.service.rootfs.DeclaredEnvironment
import com.fem.linexa.service.rootfs.DeclaredWorkspace
import com.fem.linexa.service.rootfs.RootFsMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #4 (2026-09-03) — the guest environment resolution: HOME, the
 * terminal's startup directory (the workspace), and USER, derived from the
 * declared RootFS metadata with the historical defaults as the fallback.
 */
class GuestEnvironmentResolverTest {

    private fun metadata(
        home: String? = null,
        user: String? = null,
        workspace: String? = null
    ): RootFsMetadata = RootFsMetadata(
        schemaVersion = 1,
        distro = DeclaredDistro(id = "debian", name = "Debian"),
        architecture = DeclaredArchitecture.ARM64,
        environment = DeclaredEnvironment(home = home, user = user),
        workspace = workspace?.let { DeclaredWorkspace(directory = it) }
    )

    @Test
    fun `undeclared image resolves the normal-user defaults`() {
        // Issue #1 (2026-09-03): the defaults are the NORMAL-USER environment
        // (linexa at /home/linexa) — the bundled profiles declare the same
        // values explicitly, so this only governs undeclared imports.
        val env = GuestEnvironmentResolver.resolve(null)
        assertEquals("/home/linexa", env.home)
        assertEquals("linexa", env.user)
        assertFalse(env.workspaceDeclared)
    }

    @Test
    fun `default workspace is the home itself`() {
        // The startup directory defaults to HOME so a fresh prompt reads `~`;
        // the ~/linexa project directory is a filesystem ensure, not a
        // resolver concern.
        val env = GuestEnvironmentResolver.resolve(metadata())
        assertEquals("/home/linexa", env.startupDirectory)
        assertFalse(env.workspaceDeclared)
    }

    @Test
    fun `declared workspace is ignored — the startup directory is always the home`() {
        // Issue #2 (2026-09-04): the previously-honored declaration kept
        // existing workspaces opening in /home/linexa/linexa (their persisted
        // config.yaml still carried it), so the resolver deliberately ignores
        // it. The flag stays informational.
        val env = GuestEnvironmentResolver.resolve(metadata(workspace = "/home/linexa/ws"))
        assertEquals("/home/linexa", env.startupDirectory)
        assertTrue(env.workspaceDeclared)
    }

    @Test
    fun `declared home shifts the default workspace base`() {
        // Issue #1: the startup directory defaults to the HOME itself.
        val env = GuestEnvironmentResolver.resolve(metadata(home = "/home/linexa"))
        assertEquals("/home/linexa", env.home)
        assertEquals("/home/linexa", env.startupDirectory)
    }

    @Test
    fun `declared user is honored`() {
        val env = GuestEnvironmentResolver.resolve(metadata(user = "linexa"))
        assertEquals("linexa", env.user)
    }

    @Test
    fun `malformed declarations fall back to defaults`() {
        // Relative home, home that trims to "/", and a relative workspace all
        // collapse to the safe defaults — the resolver is total by contract.
        assertEquals(
            "/home/linexa",
            GuestEnvironmentResolver.resolve(metadata(home = "relative")).home
        )
        assertEquals(
            "/home/linexa",
            GuestEnvironmentResolver.resolve(metadata(home = "/")).home
        )
        val relative = GuestEnvironmentResolver.resolve(metadata(workspace = "relative"))
        assertEquals("/home/linexa", relative.startupDirectory)
        assertFalse(relative.workspaceDeclared)
    }

    @Test
    fun `trailing slashes are normalized`() {
        val env = GuestEnvironmentResolver.resolve(
            metadata(home = "/home/linexa/", workspace = "/home/linexa/ws/")
        )
        assertEquals("/home/linexa", env.home)
        // Issue #2: the declared workspace no longer moves the startup dir.
        assertEquals("/home/linexa", env.startupDirectory)
    }
}
