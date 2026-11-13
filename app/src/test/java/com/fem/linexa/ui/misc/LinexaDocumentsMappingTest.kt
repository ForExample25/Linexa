package com.fem.linexa.ui.misc

import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.ui.misc.LinexaDocumentsMapping.Decoded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.74 — read-only SAF DocumentsProvider: pure, framework-free tests for
 * [LinexaDocumentsMapping] (document-ID parsing, traversal guards, display
 * names, browsability gate). The provider itself is a thin Android shell
 * over this mapping; every security-relevant decision lives here so it can
 * run on the JVM without the framework.
 */
class LinexaDocumentsMappingTest {

    // ── decode: roots ─────────────────────────────────────────────────

    @Test
    fun `bare root ids decode to their virtual root`() {
        assertEquals(Decoded.ContainersRoot, LinexaDocumentsMapping.decode("containers"))
        assertEquals(Decoded.PublicRoot, LinexaDocumentsMapping.decode("public"))
    }

    @Test
    fun `unknown bare ids are rejected`() {
        assertNull(LinexaDocumentsMapping.decode("other"))
        assertNull(LinexaDocumentsMapping.decode(""))
        assertNull(LinexaDocumentsMapping.decode("Containers"))
    }

    // ── decode: workspaces and files ──────────────────────────────────

    @Test
    fun `workspace document id decodes to its rootfs directory`() {
        assertEquals(
            Decoded.Workspace("abc-123"),
            LinexaDocumentsMapping.decode(LinexaDocumentsMapping.workspaceDocumentId("abc-123"))
        )
    }

    @Test
    fun `nested containers file decodes workspace plus relative segments`() {
        assertEquals(
            Decoded.ContainersFile("abc-123", listOf("usr", "bin", "bash")),
            LinexaDocumentsMapping.decode("containers:abc-123/usr/bin/bash")
        )
    }

    @Test
    fun `colons inside names are legal after the first colon`() {
        assertEquals(
            Decoded.ContainersFile("abc-123", listOf("a:b.txt")),
            LinexaDocumentsMapping.decode("containers:abc-123/a:b.txt")
        )
        assertEquals(
            Decoded.PublicFile(listOf("notes", "1:2.md")),
            LinexaDocumentsMapping.decode("public:notes/1:2.md")
        )
    }

    @Test
    fun `public file decodes relative segments`() {
        assertEquals(
            Decoded.PublicFile(listOf("Shared Music", "song.mp3")),
            LinexaDocumentsMapping.decode("public:Shared Music/song.mp3")
        )
    }

    @Test
    fun `root prefix must be exact`() {
        assertNull(LinexaDocumentsMapping.decode("container:x"))
        assertNull(LinexaDocumentsMapping.decode("containerss:x"))
        assertNull(LinexaDocumentsMapping.decode("publicx:y"))
    }

    @Test
    fun `child document ids round-trip`() {
        val parent = "containers:abc-123"
        val child = LinexaDocumentsMapping.childDocumentId(parent, "etc")
        assertEquals(Decoded.ContainersFile("abc-123", listOf("etc")), LinexaDocumentsMapping.decode(child))
        val grandChild = LinexaDocumentsMapping.childDocumentId(child, "passwd")
        assertEquals(
            Decoded.ContainersFile("abc-123", listOf("etc", "passwd")),
            LinexaDocumentsMapping.decode(grandChild)
        )
    }

    @Test
    fun `children of a virtual root use the colon form`() {
        assertEquals("public:x", LinexaDocumentsMapping.childDocumentId("public", "x"))
        assertEquals(
            Decoded.PublicFile(listOf("Shared Music")),
            LinexaDocumentsMapping.decode(LinexaDocumentsMapping.childDocumentId("public", "Shared Music"))
        )
        assertEquals(
            Decoded.Workspace("abc-123"),
            LinexaDocumentsMapping.decode(LinexaDocumentsMapping.childDocumentId("containers", "abc-123"))
        )
        // Once below the root, plain "/" concatenation resumes.
        assertEquals(
            Decoded.PublicFile(listOf("Shared Music", "a.mp3")),
            LinexaDocumentsMapping.decode(LinexaDocumentsMapping.childDocumentId("public:Shared Music", "a.mp3"))
        )
    }

    // ── decode: traversal guards ──────────────────────────────────────

    @Test
    fun `parent traversal is rejected`() {
        assertNull(LinexaDocumentsMapping.decode("containers:abc-123/.."))
        assertNull(LinexaDocumentsMapping.decode("containers:abc-123/../etc"))
        assertNull(LinexaDocumentsMapping.decode("public:../etc"))
        assertNull(LinexaDocumentsMapping.decode("public:a/../../b"))
    }

    @Test
    fun `absolute and empty segments are rejected`() {
        assertNull(LinexaDocumentsMapping.decode("containers:abc-123//etc"))
        assertNull(LinexaDocumentsMapping.decode("containers:abc-123/etc/"))
        assertNull(LinexaDocumentsMapping.decode("containers:abc-123/usr//bin"))
        assertNull(LinexaDocumentsMapping.decode("public:/etc"))
        assertNull(LinexaDocumentsMapping.decode("public:a//b"))
        assertNull(LinexaDocumentsMapping.decode("public:a/b/"))
    }

    @Test
    fun `empty path after the root colon is the root itself`() {
        // "containers:" == the Containers root; "public:" == the Public root,
        // both legal document IDs the provider returns via rootDocumentId.
        assertEquals(Decoded.ContainersRoot, LinexaDocumentsMapping.decode("containers:"))
        assertEquals(Decoded.PublicRoot, LinexaDocumentsMapping.decode("public:"))
        assertEquals(
            Decoded.Workspace("abc-123"),
            LinexaDocumentsMapping.decode("containers:abc-123")
        )
    }

    // ── isBrowsable: Ready + disk truth ───────────────────────────────

    @Test
    fun `browsable only when status Ready and rootfs on disk`() {
        assertTrue(LinexaDocumentsMapping.isBrowsable(WorkspaceStatus.Ready, rootfsOnDisk = true))
        assertFalse(LinexaDocumentsMapping.isBrowsable(WorkspaceStatus.Ready, rootfsOnDisk = false))
    }

    @Test
    fun `never browsable while another status claims disk exists`() {
        val nonReady = listOf(
            WorkspaceStatus.Created,
            WorkspaceStatus.Importing(0.5f),
            WorkspaceStatus.Installed,
            WorkspaceStatus.Broken("corrupt"),
            WorkspaceStatus.Repairing,
            WorkspaceStatus.Deleting
        )
        nonReady.forEach { status ->
            assertFalse(LinexaDocumentsMapping.isBrowsable(status, rootfsOnDisk = true))
        }
    }

    // ── sanitizeDisplayName ───────────────────────────────────────────

    @Test
    fun `sanitize keeps safe characters and collapses runs`() {
        assertEquals("Debian 12", LinexaDocumentsMapping.sanitizeDisplayName("Debian 12"))
        assertEquals("Debian_12_arm64", LinexaDocumentsMapping.sanitizeDisplayName("Debian/12:arm64"))
        assertEquals("Debian---12", LinexaDocumentsMapping.sanitizeDisplayName("Debian---12"))
        assertEquals("a_b", LinexaDocumentsMapping.sanitizeDisplayName("a__b"))
        assertEquals("name", LinexaDocumentsMapping.sanitizeDisplayName("__name__"))
    }

    @Test
    fun `sanitize trims dots and spaces and never returns empty`() {
        assertEquals("name", LinexaDocumentsMapping.sanitizeDisplayName(".name."))
        assertEquals("name", LinexaDocumentsMapping.sanitizeDisplayName("  name  "))
        assertEquals("workspace", LinexaDocumentsMapping.sanitizeDisplayName("..."))
        assertEquals("workspace", LinexaDocumentsMapping.sanitizeDisplayName(""))
        assertEquals("workspace", LinexaDocumentsMapping.sanitizeDisplayName("***"))
    }

    @Test
    fun `sanitize caps length`() {
        val long = "x".repeat(200)
        assertEquals(64, LinexaDocumentsMapping.sanitizeDisplayName(long).length)
        assertTrue(LinexaDocumentsMapping.sanitizeDisplayName(long).all { it == 'x' })
    }

    // ── assignDisplayNames ────────────────────────────────────────────

    @Test
    fun `unique names are kept as-is`() {
        val names = LinexaDocumentsMapping.assignDisplayNames(
            listOf("id-a" to "Debian 12", "id-b" to "Ubuntu 24.04")
        )
        assertEquals("Debian 12", names["id-a"])
        assertEquals("Ubuntu 24.04", names["id-b"])
    }

    @Test
    fun `colliding names get deterministic short-id suffixes`() {
        val names = LinexaDocumentsMapping.assignDisplayNames(
            listOf("abcdef1234567890" to "Debian", "1234567890abcdef" to "Debian")
        )
        assertEquals("Debian-abcdef12", names["abcdef1234567890"])
        assertEquals("Debian-12345678", names["1234567890abcdef"])
    }

    @Test
    fun `display names are unique and order-independent`() {
        val input = listOf(
            "abc-123" to "Debian 12",
            "def-456" to "Debian 12",
            "ghi-789" to "Fedora"
        )
        val forward = LinexaDocumentsMapping.assignDisplayNames(input)
        val reversed = LinexaDocumentsMapping.assignDisplayNames(input.reversed())
        assertEquals(forward, reversed)
        assertEquals(3, forward.values.toSet().size)
        assertTrue(forward["abc-123"]!!.startsWith("Debian 12"))
        assertTrue(forward["def-456"]!!.startsWith("Debian 12"))
        assertEquals("Fedora", forward["ghi-789"])
    }

    @Test
    fun `natural name matching an existing suffix is itself suffixed`() {
        // "Debian-12345678" is a legal sanitized name; it must not end up
        // duplicating the suffixed "Debian" workspace.
        val names = LinexaDocumentsMapping.assignDisplayNames(
            listOf(
                "1234567890abcdef" to "Debian",
                "aaaaaaaaaaaaaaaa" to "Debian",
                "0000000012345678" to "Debian-12345678"
            )
        )
        assertEquals(3, names.values.toSet().size)
        assertEquals("Debian-12345678", names["1234567890abcdef"])
        assertEquals("Debian-aaaaaaaa", names["aaaaaaaaaaaaaaaa"])
        // The natural name "Debian-12345678" was already taken by the
        // suffixed "Debian" workspace, so it is itself suffixed.
        assertEquals("Debian-12345678-00000000", names["0000000012345678"])
    }
}
