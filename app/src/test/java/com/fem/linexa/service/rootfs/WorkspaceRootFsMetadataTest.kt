package com.fem.linexa.service.rootfs

import android.app.Application
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
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
 * §1.118 — tests for the installed-workspace metadata store.
 *
 * Two behaviours are worth pinning, and neither is visible from reading the
 * class: the fail-open contract (a broken file must read as "no declaration",
 * never as an exception on a launch path) and the memo's self-revalidation (an
 * id-keyed memo that trusted `invalidate()` would serve the *previous* image's
 * declared shell after a reinstall).
 */
class WorkspaceRootFsMetadataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var store: WorkspaceRootFsMetadata

    private val declaring = """
        linexa:
          schema_version: 1
          distro:
            id: debian
            name: Debian GNU/Linux
            version: 13
          architecture: arm64
          environment:
            shell: /bin/bash
    """.trimIndent()

    private val declaringAlpine = """
        linexa:
          schema_version: 1
          distro:
            id: alpine
            name: Alpine Linux
          architecture: arm64
          environment:
            shell: /bin/ash
    """.trimIndent()

    /** §1.123: a native declaration that also carries presentation text. */
    private val declaringWithPresentation = """
        linexa:
          schema_version: 1
          distro:
            id: debian
            name: Debian GNU/Linux
          architecture: arm64
          metadata:
            display_name: Debian (native)
    """.trimIndent()

    @Before
    fun setUp() {
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(tmp.newFolder("files"))
        fileManager = WorkspaceFileManager(app)
        store = WorkspaceRootFsMetadata(fileManager, RootFsInspector(WorkspaceConfigParser()))
    }

    private fun writeConfig(workspaceId: String, yaml: String): File {
        val base = fileManager.getWorkspaceBasePath(workspaceId)
        base.mkdirs()
        return File(base, "config.yaml").also { it.writeText(yaml) }
    }

    @Test
    fun readsADeclarationFromTheInstalledConfigFile() {
        writeConfig("ws1", declaring)
        val metadata = store.of("ws1")
        assertNotNull(metadata)
        assertEquals("debian", metadata!!.distro.id)
        assertEquals("/bin/bash", metadata.environment?.shell)
    }

    @Test
    fun aWorkspaceWithNoConfigFileHasNoDeclaration() {
        assertNull(store.of("never-installed"))
    }

    @Test
    fun aConfigOnlyFileHasNoDeclarationAndIsNotAnError() {
        writeConfig("ws1", "linexa:\n  features:\n    audio: true\n")
        assertNull(store.of("ws1"))
    }

    @Test
    fun aCorruptFileReadsAsNoDeclarationRatherThanThrowing() {
        // Fail-open (§1.27): this is called on the launch path, and every
        // consumer has a probe fallback. Refusing to launch over a tampered
        // *optional* declaration would be strictly worse.
        writeConfig("ws1", "linexa:\n  architecture: [not, a, scalar]\n")
        assertNull(store.of("ws1"))
    }

    @Test
    fun anInvalidDeclarationReadsAsNoDeclaration() {
        // Well-formed YAML, semantically rejected (no schema_version).
        writeConfig("ws1", "linexa:\n  distro:\n    id: debian\n    name: Debian\n")
        assertNull(store.of("ws1"))
    }

    @Test
    fun aRewrittenFileIsRePickedUpWithoutAnExplicitInvalidate() {
        // The regression this guards: a reinstall over the same workspace id
        // must not keep serving the previous image's declared shell.
        writeConfig("ws1", declaring)
        assertEquals("/bin/bash", store.of("ws1")?.environment?.shell)

        val file = writeConfig("ws1", declaringAlpine)
        file.setLastModified(file.lastModified() + 5_000)

        assertEquals("/bin/ash", store.of("ws1")?.environment?.shell)
    }

    @Test
    fun aWorkspaceThatGainsAConfigFileLaterPicksItUp() {
        assertNull(store.of("ws1"))
        writeConfig("ws1", declaring)
        assertNotNull(store.of("ws1"))
    }

    @Test
    fun aDeletedConfigFileStopsBeingReported() {
        writeConfig("ws1", declaring)
        assertNotNull(store.of("ws1"))
        File(fileManager.getWorkspaceBasePath("ws1"), "config.yaml").delete()
        assertNull(store.of("ws1"))
    }

    @Test
    fun invalidateForcesAReReadWithoutChangingTheAnswer() {
        writeConfig("ws1", declaring)
        val first = store.of("ws1")
        store.invalidate("ws1")
        assertEquals(first, store.of("ws1"))
    }

    @Test
    fun workspacesDoNotShareOneAnothersDeclarations() {
        writeConfig("ws1", declaring)
        writeConfig("ws2", declaringAlpine)
        assertEquals("debian", store.of("ws1")?.distro?.id)
        assertEquals("alpine", store.of("ws2")?.distro?.id)
    }

    @Test
    fun repeatedReadsAreStable() {
        writeConfig("ws1", declaring)
        val a = store.of("ws1")
        val b = store.of("ws1")
        assertEquals(a, b)
    }

    // ── §1.123: the legacy half, read where it actually lives ───────────

    private val legacyTiny = """
        name: Debian Trixie
        description: Republished for Linexa.
        preview: /opt/tiny/preview.png
        boot_command: /bin/bash
        quick_commands:
          - type: command
            name: Update
            command: apt-get update
    """.trimIndent()

    private fun writeLegacy(workspaceId: String, yaml: String): File {
        val rootfs = fileManager.getRootfsPath(workspaceId)
        rootfs.mkdirs()
        return File(rootfs, RootFsMetadataFile.LEGACY_TINY.fileName).also { it.writeText(yaml) }
    }

    @Test
    fun aLegacyDescriptorIsReadFromInsideTheRootfsNotTheWorkspaceRoot() {
        // §1.119 deliberately does NOT persist a foreign document as
        // config.yaml, so the only place to find it is where extraction left
        // it: the rootfs root (tar -xf, no --strip-components).
        writeLegacy("ws1", legacyTiny)

        val descriptor = store.descriptorOf("ws1")
        assertTrue(descriptor.isLegacy)
        assertEquals("Debian Trixie", descriptor.presentation?.displayName)
        assertEquals("Republished for Linexa.", descriptor.presentation?.description)
    }

    @Test
    fun aLegacyWorkspaceStillHasNoNativeMetadataSoEveryFactIsStillProbed() {
        writeLegacy("ws1", legacyTiny)
        // The §1.119 invariant at the store boundary: `of()` is what the launch
        // path, the desktop and the package manager call, and it must stay null.
        assertNull(store.of("ws1"))
        assertNull(store.descriptorOf("ws1").declaredIdentity())
    }

    @Test
    fun aLegacyWorkspacesQuickCommandsHaveNoReaderAnymore() {
        // Quick Commands re-architecture: built-ins come from the project's
        // own distribution-aware catalog. A legacy `.tiny.yaml`'s
        // `quick_commands` tree (still present in the fixture below) must
        // contribute NOTHING to the command surface — the descriptor's
        // format-independent shortcut view reads native declarations only,
        // and a legacy image has none.
        writeLegacy("ws1", legacyTiny)
        val descriptor = store.descriptorOf("ws1")
        assertTrue(descriptor.isLegacy)
        assertNull(descriptor.declaredShortcuts)
        // The presentation half is unaffected — that is all a legacy file
        // contributes now.
        assertEquals("Debian Trixie", descriptor.presentation?.displayName)
    }

    @Test
    fun aNativeConfigOutranksALegacyDescriptorOnTheSameWorkspace() {
        // Same priority as the import-time scan, so an image carrying both
        // resolves identically before and after installation — otherwise a
        // workspace could show one name at the confirmation screen and another
        // on the Home card.
        writeConfig("ws1", declaringWithPresentation)
        writeLegacy("ws1", legacyTiny)

        val descriptor = store.descriptorOf("ws1")
        assertEquals("Debian (native)", descriptor.presentation?.displayName)
        assertNotNull(descriptor.metadata)
        assertFalse(descriptor.isLegacy)
    }

    @Test
    fun aNativeConfigWithNoDeclarationFallsThroughToTheLegacyDescriptor() {
        // A config-only file (features/env, no metadata block) is not a
        // declaration, so it must not shadow a legacy sibling.
        writeConfig("ws1", "linexa:\n  features:\n    audio: true\n")
        writeLegacy("ws1", legacyTiny)
        assertEquals("Debian Trixie", store.descriptorOf("ws1").presentation?.displayName)
    }

    @Test
    fun anEditedLegacyDescriptorIsRePickedUp() {
        // The memo is keyed on BOTH files, so a .tiny.yaml edited inside the
        // guest is not served stale.
        writeLegacy("ws1", legacyTiny)
        assertEquals("Debian Trixie", store.descriptorOf("ws1").presentation?.displayName)

        val file = writeLegacy("ws1", "name: Renamed")
        file.setLastModified(file.lastModified() + 5_000)

        assertEquals("Renamed", store.descriptorOf("ws1").presentation?.displayName)
    }

    @Test
    fun aBrokenLegacyDescriptorReadsAsUndeclaredRatherThanThrowing() {
        writeLegacy("ws1", "name: [unclosed")
        assertEquals(RootFsDescriptor.UNDECLARED, store.descriptorOf("ws1"))
    }

    @Test
    fun anOversizedLegacyDescriptorIsIgnored() {
        writeLegacy("ws1", "name: Debian\ndescription: " + "x".repeat(TinyMetadataAdapter.MAX_BYTES))
        assertNull(store.descriptorOf("ws1").presentation)
    }

    @Test
    fun aWorkspaceWithNeitherFileIsUndeclared() {
        assertEquals(RootFsDescriptor.UNDECLARED, store.descriptorOf("never-installed"))
    }

    // ── §1.123: the preview image, resolved live every call ─────────────

    private fun writePreview(workspaceId: String, relativePath: String): File {
        val target = File(fileManager.getRootfsPath(workspaceId), relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))
        return target
    }

    @Test
    fun aDeclaredPreviewResolvesInsideTheRootfs() {
        writeLegacy("ws1", legacyTiny)
        writePreview("ws1", "opt/tiny/preview.png")
        assertNotNull(store.previewImage("ws1"))
    }

    @Test
    fun aDeclaredPreviewThatIsNotThereIsNoPreviewRatherThanAnError() {
        writeLegacy("ws1", legacyTiny)
        assertNull(store.previewImage("ws1"))
    }

    @Test
    fun aWorkspaceThatDeclaresNoPreviewHasNone() {
        writeConfig("ws1", declaring)
        assertNull(store.previewImage("ws1"))
    }

    @Test
    fun thePreviewIsReCheckedOnEveryCallSoALaterSymlinkIsCaught() {
        // The reason previewImage() is not memoized with the declaration: the
        // rootfs is guest-writable, so a file that was legitimate at import can
        // become a symlink out of the rootfs afterwards.
        writeLegacy("ws1", legacyTiny)
        val preview = writePreview("ws1", "opt/tiny/preview.png")
        assertNotNull(store.previewImage("ws1"))

        val secret = tmp.newFile("secret.png").also { it.writeBytes(byteArrayOf(9)) }
        preview.delete()
        try {
            java.nio.file.Files.createSymbolicLink(preview.toPath(), secret.toPath())
        } catch (e: UnsupportedOperationException) {
            return // no symlink support here; the property is covered in RootFsPreviewImageTest
        }
        assertNull("a declaration cached at import time would have served this", store.previewImage("ws1"))
    }
}
