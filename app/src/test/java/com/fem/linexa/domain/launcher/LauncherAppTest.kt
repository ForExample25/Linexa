package com.fem.linexa.domain.launcher

import com.fem.linexa.service.commands.QuickActionKind
import com.fem.linexa.service.commands.QuickCommandNode
import com.fem.linexa.service.commands.QuickNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAppTest {

    private fun action(id: String, kind: QuickActionKind?, command: String) = QuickCommandNode(
        id = id, name = id, type = QuickNodeType.ACTION,
        command = command, actionKind = kind
    )

    private fun app(id: String, children: List<QuickCommandNode>) = QuickCommandNode(
        id = id, name = id, type = QuickNodeType.APPLICATION, children = children
    )

    @Test
    fun `only application nodes become apps`() {
        val tree = listOf(
            QuickCommandNode("cat", "Cat", QuickNodeType.CATEGORY, children = listOf(
                app("a", listOf(action("a-install", QuickActionKind.INSTALL, "apt-get install -y a"))),
                action("bare", null, "ls")
            ))
        )
        val apps = LauncherApps.fromCatalog(tree)
        assertEquals(listOf("builtin:a"), apps.map { it.id })
    }

    @Test
    fun `category attribution walks the parent chain`() {
        val tree = listOf(
            QuickCommandNode("dev", "Dev", QuickNodeType.CATEGORY, children = listOf(
                QuickCommandNode("langs", "Langs", QuickNodeType.GROUP, children = listOf(
                    app("python3", listOf(action("python3-install", QuickActionKind.INSTALL, "apt-get install -y python3 python3-pip")))
                )),
                app("git", listOf(action("git-install", QuickActionKind.INSTALL, "apt-get install -y git")))
            ))
        )
        val apps = LauncherApps.fromCatalog(tree)
        assertEquals("Langs", apps[0].category)
        assertEquals("Dev", apps[1].category)
    }

    @Test
    fun `install command packages are extracted not invented`() {
        val app = LauncherApps.fromCatalog(listOf(
            app("p", listOf(action("p-install", QuickActionKind.INSTALL,
                "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-pip python3-venv")))
        )).single()
        assertEquals(listOf("python3", "python3-pip", "python3-venv"), app.verificationPackages)
    }

    @Test
    fun `wget deb installs yield no package names`() {
        val app = LauncherApps.fromCatalog(listOf(
            app("v", listOf(action("v-install", QuickActionKind.INSTALL,
                "wget -O /tmp/v.deb https://example.com/v.deb && apt-get install -y /tmp/v.deb")))
        )).single()
        assertTrue(app.verificationPackages.isEmpty())
    }

    @Test
    fun `launch action supplies launch command and executables`() {
        val app = LauncherApps.fromCatalog(listOf(
            app("htop", listOf(
                action("htop-launch", QuickActionKind.LAUNCH, "htop"),
                action("htop-install", QuickActionKind.INSTALL, "apt-get install -y htop")
            ))
        )).single()
        assertEquals("htop", app.launchCommand)
        assertEquals(listOf("htop"), app.verificationExecutables)
    }

    @Test
    fun `no launch action leaves null launch`() {
        val app = LauncherApps.fromCatalog(listOf(
            app("x", listOf(action("x-install", QuickActionKind.INSTALL, "apt-get install -y x")))
        )).single()
        assertNull(app.launchCommand)
        assertTrue(app.verificationExecutables.isEmpty())
    }

    @Test
    fun `command -v prefix executables skip the flag`() {
        assertEquals(listOf("fastfetch"), InstallCommandParser.executablesOf("command -v fastfetch && fastfetch || cat /etc/os-release"))
        assertEquals(listOf("python3"), InstallCommandParser.executablesOf("python3"))
    }

    @Test
    fun `multi-manager package mining across segments`() {
        val pkgs = InstallCommandParser.packagesOf(
            "apt-get update && apt-get install -y curl wget && pacman -S vim btop"
        )
        assertTrue(pkgs.containsAll(listOf("curl", "wget", "vim", "btop")))
    }

    @Test
    fun `remove commands are not mined for verification`() {
        val pkgs = InstallCommandParser.packagesOf("apt-get autoremove --purge -y python3")
        assertTrue(pkgs.isEmpty())
    }
}
