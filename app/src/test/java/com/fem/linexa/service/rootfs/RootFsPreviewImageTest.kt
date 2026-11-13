package com.fem.linexa.service.rootfs

import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.121 — tests for the declared preview image's two-stage safety rule.
 *
 * The property that carries the decision: a preview is the only declaration
 * that names a **file the app then renders**, so both stages have to hold, and
 * each one catches something the other cannot. [normalizeRejectsTraversal]
 * covers the path-shape half; [aSymlinkOutOfTheRootfsIsRejectedAtReadTime]
 * covers the half that only exists because the guest can rewrite its own
 * filesystem after import — which is also why the read-time check must not be
 * cached alongside the declaration.
 */
class RootFsPreviewImageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val inspector = RootFsInspector(WorkspaceConfigParser())

    // ── stage 1: normalize ──────────────────────────────────────────────

    @Test
    fun aPlainRelativePathSurvivesUnchanged() {
        assertEquals("opt/tiny/preview.png", RootFsPreviewImage.normalize("opt/tiny/preview.png"))
    }

    @Test
    fun aLeadingSlashIsStrippedRatherThanRejected() {
        // `.tiny.yaml` writes `/opt/tiny/preview.png` and means "inside the
        // container" — the guest's own view of the rootfs root.
        assertEquals("opt/tiny/preview.png", RootFsPreviewImage.normalize("/opt/tiny/preview.png"))
    }

    @Test
    fun redundantSegmentsAreCollapsed() {
        assertEquals("opt/preview.png", RootFsPreviewImage.normalize("./opt//./preview.png"))
    }

    @Test
    fun normalizeRejectsTraversal() {
        assertNull(RootFsPreviewImage.normalize("../../../etc/passwd.png"))
        assertNull(RootFsPreviewImage.normalize("opt/../../etc/shadow.png"))
    }

    @Test
    fun aSchemeOrDrivePrefixIsRejected() {
        // Meaningless inside a rootfs, and accepting it would leave a
        // colon-bearing literal filename on Linux.
        assertNull(RootFsPreviewImage.normalize("file:/opt/preview.png"))
        assertNull(RootFsPreviewImage.normalize("http://example.com/a.png"))
        assertNull(RootFsPreviewImage.normalize("C:/opt/preview.png"))
    }

    @Test
    fun aBackslashIsRejectedSoTheRuleDoesNotDependOnTheHostPathParser() {
        assertNull(RootFsPreviewImage.normalize("opt\\tiny\\preview.png"))
    }

    @Test
    fun controlCharactersAndNulAreRejected() {
        assertNull(RootFsPreviewImage.normalize("opt/pre\u0000view.png"))
        assertNull(RootFsPreviewImage.normalize("opt/pre\nview.png"))
    }

    @Test
    fun anUnsupportedOrAbsentExtensionIsRejected() {
        // So a `preview: /etc/shadow` never reaches an image decoder at all.
        assertNull(RootFsPreviewImage.normalize("etc/shadow"))
        assertNull(RootFsPreviewImage.normalize("opt/preview.svg"))
        assertNull(RootFsPreviewImage.normalize("opt/preview"))
    }

    @Test
    fun everyAdvertisedExtensionIsAccepted() {
        RootFsPreviewImage.SUPPORTED_EXTENSIONS.forEach { extension ->
            assertNotNull(
                "SUPPORTED_EXTENSIONS advertises '$extension'",
                RootFsPreviewImage.normalize("opt/preview.$extension")
            )
        }
    }

    @Test
    fun extensionMatchingIsCaseInsensitive() {
        assertEquals("opt/preview.PNG", RootFsPreviewImage.normalize("opt/preview.PNG"))
    }

    @Test
    fun anEmptyOrOverlongPathIsRejected() {
        assertNull(RootFsPreviewImage.normalize(""))
        assertNull(RootFsPreviewImage.normalize("   "))
        assertNull(RootFsPreviewImage.normalize("/"))
        val long = "a".repeat(RootFsPreviewImage.MAX_PATH_CHARS) + "/preview.png"
        assertNull(RootFsPreviewImage.normalize(long))
    }

    // ── stage 2: resolveInside ──────────────────────────────────────────

    private fun rootfsWith(relativePath: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File {
        val rootfs = tmp.newFolder("rootfs")
        val target = File(rootfs, relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return rootfs
    }

    @Test
    fun aRealFileInsideTheRootfsResolves() {
        val rootfs = rootfsWith("opt/tiny/preview.png")
        val resolved = RootFsPreviewImage.resolveInside(rootfs, "opt/tiny/preview.png")
        assertNotNull(resolved)
        assertEquals("preview.png", resolved!!.name)
    }

    @Test
    fun aMissingFileIsNoPreviewRatherThanAnError() {
        assertNull(RootFsPreviewImage.resolveInside(tmp.newFolder("rootfs"), "opt/preview.png"))
    }

    @Test
    fun anEmptyFileIsRejected() {
        // A zero-length file would decode to nothing; the letter avatar is the
        // honest rendering.
        val rootfs = rootfsWith("opt/preview.png", ByteArray(0))
        assertNull(RootFsPreviewImage.resolveInside(rootfs, "opt/preview.png"))
    }

    @Test
    fun aDirectoryIsRejected() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "opt/preview.png").mkdirs()
        assertNull(RootFsPreviewImage.resolveInside(rootfs, "opt/preview.png"))
    }

    @Test
    fun aNullOrUnnormalizablePathIsRejectedAtReadTimeToo() {
        val rootfs = rootfsWith("opt/preview.png")
        assertNull(RootFsPreviewImage.resolveInside(rootfs, null))
        // Stage 1 is re-applied at read time, so a value that somehow bypassed
        // import-time validation still cannot escape.
        assertNull(RootFsPreviewImage.resolveInside(rootfs, "../../../etc/passwd.png"))
    }

    @Test
    fun aSymlinkOutOfTheRootfsIsRejectedAtReadTime() {
        // The case stage 1 structurally cannot catch: `opt/preview.png` is a
        // perfectly valid relative path, and the guest can replace it with a
        // symlink long after any import-time check ran.
        val secret = tmp.newFile("secret.png").also { it.writeBytes(byteArrayOf(9, 9, 9)) }
        val rootfs = tmp.newFolder("rootfs")
        val link = File(rootfs, "opt/preview.png")
        link.parentFile.mkdirs()
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (e: UnsupportedOperationException) {
            return // no symlink support on this filesystem; the assertion below is moot
        }
        assertNull(RootFsPreviewImage.resolveInside(rootfs, "opt/preview.png"))
    }

    @Test
    fun aSymlinkInsideTheRootfsIsStillAccepted() {
        val rootfs = rootfsWith("usr/share/logo.png")
        val link = File(rootfs, "opt/preview.png")
        link.parentFile.mkdirs()
        try {
            java.nio.file.Files.createSymbolicLink(
                link.toPath(),
                File(rootfs, "usr/share/logo.png").toPath()
            )
        } catch (e: UnsupportedOperationException) {
            return
        }
        // Containment is the rule, not "must not be a symlink" — an image is
        // free to arrange its own files.
        assertNotNull(RootFsPreviewImage.resolveInside(rootfs, "opt/preview.png"))
    }

    @Test
    fun aSiblingDirectoryWithTheRootfsAsAPrefixIsNotInside() {
        // The classic prefix-string bypass: `/…/rootfs-evil/x.png` starts with
        // `/…/rootfs` but is not inside it.
        val parent = tmp.newFolder("ws")
        val rootfs = File(parent, "rootfs").also { it.mkdirs() }
        val evil = File(parent, "rootfs-evil").also { it.mkdirs() }
        File(evil, "x.png").writeBytes(byteArrayOf(1))

        val link = File(rootfs, "preview.png")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(evil, "x.png").toPath())
        } catch (e: UnsupportedOperationException) {
            return
        }
        assertNull(RootFsPreviewImage.resolveInside(rootfs, "preview.png"))
    }

    @Test
    fun aMissingRootfsDirectoryIsNoPreviewRatherThanAThrow() {
        // Reached on the Home list; an exception here would break the whole
        // screen for one cosmetic field.
        val absent = File(tmp.root, "never-created/rootfs")
        assertNull(RootFsPreviewImage.resolveInside(absent, "opt/preview.png"))
    }

    // ── the native schema's use of stage 1 ──────────────────────────────

    @Test
    fun anInvalidDeclaredPreviewFailsTheNativeImport() {
        // Fail-hard for Linexa's own schema (§1.121): the author can see and fix
        // it, and silently dropping the field would leave them debugging a card
        // that renders the letter avatar for no visible reason.
        val result = inspector.inspect(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              metadata:
                display_name: Debian
                preview: ../../../etc/passwd.png
            """.trimIndent().toByteArray()
        )
        assertTrue("expected Invalid, got $result", result is RootFsInspector.Result.Invalid)
        // Named explicitly so this cannot start passing for an unrelated reason
        // (a typo'd key elsewhere in the fixture would also produce Invalid).
        assertTrue(
            "the reason must name the offending field",
            (result as RootFsInspector.Result.Invalid).reason.contains("preview")
        )
    }

    @Test
    fun aValidDeclaredPreviewIsPersistedNormalized() {
        val result = inspector.inspect(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              metadata:
                display_name: Debian
                preview: /opt/./tiny/preview.png
            """.trimIndent().toByteArray()
        ) as RootFsInspector.Result.Success
        // No consumer should ever see the author's raw string.
        assertEquals(
            "opt/tiny/preview.png",
            result.descriptor.presentation?.previewImagePath
        )
    }
}
