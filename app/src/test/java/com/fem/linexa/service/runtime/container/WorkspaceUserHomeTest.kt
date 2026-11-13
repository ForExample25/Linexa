package com.fem.linexa.service.runtime.container

import com.fem.linexa.service.rootfs.DeclaredArchitecture
import com.fem.linexa.service.rootfs.DeclaredDistro
import com.fem.linexa.service.rootfs.DeclaredEnvironment
import com.fem.linexa.service.rootfs.RootFsMetadata
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.WorkspaceFileManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * 2026-11-13 — the application-side authority for the Workspace home.
 *
 * The File Manager used to show `/root` because the tile was hardcoded, while
 * the terminal already ran with `HOME=/home/linexa`. [WorkspaceUserHome]
 * resolves the account from the installed image's own `/etc/passwd`, so the
 * UI follows the same facts as the session — and root workspaces follow
 * root's record instead.
 */
class WorkspaceUserHomeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun metadata(
        user: String = "linexa",
        home: String = "/home/linexa"
    ) = RootFsMetadata(
        schemaVersion = 1,
        distro = DeclaredDistro(id = "debian", name = "Debian GNU/Linux"),
        architecture = DeclaredArchitecture.ARM64,
        environment = DeclaredEnvironment(user = user, home = home)
    )

    private fun rootfs(passwd: String): File {
        val rootfs = tmp.newFolder("rootfs-${System.nanoTime()}")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText(passwd)
        return rootfs
    }

    private fun service(
        declared: RootFsMetadata?,
        rootfsPath: File?,
        workspaceId: String = "ws"
    ): WorkspaceUserHome {
        val fileManager = Mockito.mock(WorkspaceFileManager::class.java)
        if (rootfsPath != null) {
            Mockito.`when`(fileManager.getRootfsPath(workspaceId)).thenReturn(rootfsPath)
        } else {
            Mockito.`when`(fileManager.getRootfsPath(workspaceId))
                .thenThrow(IllegalStateException("workspace not found"))
        }
        val store = Mockito.mock(WorkspaceRootFsMetadata::class.java)
        if (declared != null) {
            Mockito.`when`(store.of(workspaceId)).thenReturn(declared)
        } else {
            Mockito.`when`(store.of(workspaceId)).thenReturn(null)
        }
        return WorkspaceUserHome(fileManager, store)
    }

    @Test
    fun `a normal workspace resolves the passwd account, not root`() {
        val rootfs = rootfs(
            "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1001:1001:Linexa User:/home/linexa:/bin/bash\n"
        )
        val homes = service(metadata(), rootfs).resolveBlocking("ws")

        assertEquals("linexa", homes.userName)
        assertEquals("/home/linexa", homes.userHome)
        assertEquals("/root", homes.rootHome)
        assertFalse(homes.isRootIdentity)
        assertTrue("Root Home stays available as a separate place", homes.showRootHomeShortcut)
    }

    @Test
    fun `a custom passwd home wins over the declaration`() {
        val rootfs = rootfs(
            "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1042:1042:Custom:/srv/home:/usr/bin/zsh\n"
        )
        val homes = service(metadata(home = "/home/linexa"), rootfs).resolveBlocking("ws")
        assertEquals("/srv/home", homes.userHome)
    }

    @Test
    fun `a declared root workspace resolves root's home`() {
        val rootfs = rootfs("root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000::/home/linexa:/bin/bash\n")
        val homes = service(metadata(user = "root", home = "/root"), rootfs).resolveBlocking("ws")

        assertEquals("root", homes.userName)
        assertEquals("/root", homes.userHome)
        assertTrue(homes.isRootIdentity)
        assertFalse("root must not get two identical places", homes.showRootHomeShortcut)
    }

    @Test
    fun `a missing rootfs degrades to the declared normal home, never root`() {
        val homes = service(metadata(), rootfsPath = null).resolveBlocking("ws")
        assertEquals("/home/linexa", homes.userHome)
        assertFalse(homes.isRootIdentity)
    }

    @Test
    fun `missing metadata degrades to the documented defaults`() {
        val rootfs = rootfs("root:x:0:0:root:/root:/bin/bash\n")
        val homes = service(null, rootfs).resolveBlocking("ws")

        assertEquals("linexa", homes.userName)
        assertEquals("/home/linexa", homes.userHome)
        assertEquals("/root", homes.rootHome)
    }

    @Test
    fun `a misconfigured passwd home is not exposed as Home`() {
        val rootfs = rootfs(
            "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/root:/bin/bash\n"
        )
        val homes = service(metadata(), rootfs).resolveBlocking("ws")
        assertEquals("/home/linexa", homes.userHome)
    }

    @Test
    fun `the suspend entry point returns the same identity-aware answer`() = runBlocking {
        val rootfs = rootfs(
            "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1001:1001:Linexa User:/home/linexa:/bin/bash\n"
        )
        val service = service(metadata(), rootfs)

        val homes = service.resolve("ws")

        assertEquals(service.resolveBlocking("ws"), homes)
        assertEquals("/home/linexa", homes.userHome)
    }
}