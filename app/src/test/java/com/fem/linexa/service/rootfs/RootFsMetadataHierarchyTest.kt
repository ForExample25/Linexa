package com.fem.linexa.service.rootfs

import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #3/#4/#5 (2026-09-03): schema v2 — hierarchical shortcut entries
 * (`children:`), optional `action:` tags, and `as_root:` elevation leaves,
 * flattened by the parser into the same flat [DeclaredShortcut] list the
 * existing UI consumes.
 *
 * Drives the real [RootFsInspector] (the RootFsMetadataTest convention), so
 * every pin here covers the whole pipeline the bundled catalogs go through.
 */
class RootFsMetadataHierarchyTest {

    private val inspector = RootFsInspector(WorkspaceConfigParser())

    private fun inspect(yaml: String): RootFsInspector.Result =
        inspector.inspect(yaml.trimIndent().toByteArray())

    private fun shortcutsOf(yaml: String): List<DeclaredShortcut> {
        val result = inspect(yaml)
        assertTrue("expected Success, got $result", result is RootFsInspector.Result.Success)
        return (result as RootFsInspector.Result.Success).descriptor.metadata!!.shortcuts!!
    }

    @Test
    fun `a hierarchical catalog flattens into path-composed labels`() {
        val shortcuts = shortcutsOf(
            """
            linexa:
              schema_version: 2
              distro: { id: debian, name: Debian }
              architecture: arm64
              shortcuts:
                - id: sw
                  label: Software Management
                  children:
                    - id: chrome
                      label: Chrome
                      children:
                        - id: chrome-install
                          label: Install
                          action: install
                          command: apt-get install -y chromium
                        - id: chrome-remove
                          label: Remove
                          action: remove
                          command: apt-get autoremove --purge -y chromium
            """
        )
        assertEquals(2, shortcuts.size)
        assertEquals("Software Management > Chrome > Install", shortcuts[0].label)
        assertEquals(DeclaredShortcut.ACTION_INSTALL, shortcuts[0].action)
        assertEquals("Software Management > Chrome > Remove", shortcuts[1].label)
        assertEquals(DeclaredShortcut.ACTION_REMOVE, shortcuts[1].action)
    }

    @Test
    fun `an as_root leaf may omit the command`() {
        val shortcuts = shortcutsOf(
            """
            linexa:
              schema_version: 2
              distro: { id: debian, name: Debian }
              architecture: arm64
              shortcuts:
                - id: root-shell
                  label: Root shell
                  as_root: true
            """
        )
        val shortcut = shortcuts.single()
        assertTrue(shortcut.asRoot)
        assertEquals("", shortcut.command)
    }

    @Test
    fun `an unknown action is rejected with a naming reason`() {
        val result = inspect(
            """
            linexa:
              schema_version: 2
              distro: { id: debian, name: Debian }
              architecture: arm64
              shortcuts:
                - id: x
                  label: Do
                  action: uninstall-everything
                  command: "true"
            """
        )
        assertTrue("expected Invalid, got $result", result is RootFsInspector.Result.Invalid)
        assertTrue((result as RootFsInspector.Result.Invalid).reason.contains("action"))
    }

    @Test
    fun `a group that also carries a command is rejected`() {
        val result = inspect(
            """
            linexa:
              schema_version: 2
              distro: { id: debian, name: Debian }
              architecture: arm64
              shortcuts:
                - id: grp
                  label: Group
                  command: "true"
                  children:
                    - id: leaf
                      label: Leaf
                      command: "true"
            """
        )
        assertTrue("expected Invalid, got $result", result is RootFsInspector.Result.Invalid)
        assertTrue((result as RootFsInspector.Result.Invalid).reason.contains("children"))
    }

    @Test
    fun `schema 1 flat files parse unchanged`() {
        val shortcuts = shortcutsOf(
            """
            linexa:
              schema_version: 1
              distro: { id: debian, name: Debian }
              architecture: arm64
              shortcuts:
                - id: old
                  label: Old style
                  command: uname -a
            """
        )
        assertEquals("Old style", shortcuts.single().label)
        assertNull(shortcuts.single().action)
        assertEquals(false, shortcuts.single().asRoot)
    }

    @Test
    fun `depth is not capped at two — four levels compose`() {
        val shortcuts = shortcutsOf(
            """
            linexa:
              schema_version: 2
              distro: { id: debian, name: Debian }
              architecture: arm64
              shortcuts:
                - id: a
                  label: A
                  children:
                    - id: b
                      label: B
                      children:
                        - id: c
                          label: C
                          children:
                            - id: leaf
                              label: Go
                              command: "true"
            """
        )
        assertEquals("A > B > C > Go", shortcuts.single().label)
    }

    @Test
    fun `the bundled debian catalog parses end to end with the linexa user`() {
        // The real asset: the exact file the Debian install profile ships.
        // Same access convention as BundledRootfsMetadataProviderVersionTest:
        // unit tests read the working-tree asset files directly.
        val bytes = java.io.File("src/main/assets/rootfs/metadata/debian.linexa.yaml").readBytes()
        val result = inspector.inspect(bytes)
        assertTrue("bundled debian profile must be valid: $result", result is RootFsInspector.Result.Success)
        val metadata = (result as RootFsInspector.Result.Success).descriptor.metadata!!
        assertEquals("linexa", metadata.environment?.user)
        assertEquals("/home/linexa", metadata.environment?.home)
        assertEquals("/home/linexa", metadata.workspace?.directory)
        val shortcuts = metadata.shortcuts!!
        assertTrue("a real catalog must exist", shortcuts.size >= 40)
        assertTrue("software management must be a category", shortcuts.any { it.label.startsWith("Software Management > ") })
        assertTrue(
            "the root-shell leaf must be an elevation entry",
            shortcuts.any { it.asRoot && it.label.endsWith("Root shell") }
        )
    }

    @Test
    fun `the bundled ubuntu catalog parses end to end`() {
        val bytes = java.io.File("src/main/assets/rootfs/metadata/ubuntu.linexa.yaml").readBytes()
        val result = inspector.inspect(bytes)
        assertTrue("bundled ubuntu profile must be valid: $result", result is RootFsInspector.Result.Success)
        val metadata = (result as RootFsInspector.Result.Success).descriptor.metadata!!
        assertEquals("linexa", metadata.environment?.user)
        // Ubuntu-specific: no chromium (snap-stub on jammy); firefox via PPA.
        val labels = metadata.shortcuts!!.map { it.label }
        assertTrue(labels.any { it.contains("Firefox ESR") })
        assertTrue(
            "ubuntu must not offer a chromium snap-stub",
            labels.none { it.contains("Chromium") }
        )
    }
}
