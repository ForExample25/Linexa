package com.fem.linexa.service.rootfs

import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #10/#11 (2026-09-03) — bundled metadata discovery:
 *
 *  1. **Version-specific selection** — `<distro>-<version>.linexa.yaml`
 *     beats `<distro>.linexa.yaml`, with dot-component fallback
 *     (`22.04.5` tries `22.04.5`, `22.04`, `22`).
 *  2. **The SHIPPED files are valid** — the test-suite byte source points at
 *     the real `app/src/main/assets/rootfs/metadata/` files, so a bundled
 *     profile that fails the §1.118 schema fails a TEST, not a user's
 *     install (the same contract RootFsProfileCatalogTest pins for profiles).
 */
class BundledRootfsMetadataProviderVersionTest {

    /** Serves the REAL shipped asset files from disk — the production shape. */
    private fun providerWithRealAssets(): BundledRootfsMetadataProvider {
        val dir = File("src/main/assets/rootfs/metadata")
        assertTrue("shipped metadata dir missing at ${dir.absolutePath}", dir.isDirectory)
        return BundledRootfsMetadataProvider { fileName ->
            File(dir, fileName).takeIf { it.isFile }?.readBytes()
        }
    }

    /** Serves from a map — the fixture shape. */
    private fun providerWith(files: Map<String, String>): BundledRootfsMetadataProvider =
        BundledRootfsMetadataProvider { fileName -> files[fileName]?.toByteArray() }

    // ── version candidate generation ─────────────────────────────────────

    @Test
    fun `version candidates walk most-specific first`() {
        assertEquals(
            listOf("22.04.5", "22.04", "22"),
            BundledRootfsMetadataProvider.versionCandidates("22.04.5")
        )
        assertEquals(listOf("12"), BundledRootfsMetadataProvider.versionCandidates("12"))
        assertEquals(listOf("sid"), BundledRootfsMetadataProvider.versionCandidates("sid"))
        assertEquals(emptyList<String>(), BundledRootfsMetadataProvider.versionCandidates(null))
        assertEquals(emptyList<String>(), BundledRootfsMetadataProvider.versionCandidates("  "))
    }

    // ── selection order ──────────────────────────────────────────────────

    @Test
    fun `version-specific file wins over generic`() {
        val provider = providerWith(
            mapOf(
                "debian-12.linexa.yaml" to "version-specific",
                "debian.linexa.yaml" to "generic"
            )
        )
        assertEquals("version-specific", provider.forDistro("debian", "12.7")!!.decodeToString())
    }

    @Test
    fun `point-release version falls back to the major component`() {
        val provider = providerWith(
            mapOf(
                "ubuntu-22.04.linexa.yaml" to "branch",
                "ubuntu.linexa.yaml" to "generic"
            )
        )
        assertEquals("branch", provider.forDistro("ubuntu", "22.04.5")!!.decodeToString())
    }

    @Test
    fun `generic profile is the final fallback`() {
        val provider = providerWith(mapOf("debian.linexa.yaml" to "generic"))
        assertEquals("generic", provider.forDistro("debian", "13")!!.decodeToString())
    }

    @Test
    fun `null version skips straight to generic`() {
        val provider = providerWith(
            mapOf(
                "debian-12.linexa.yaml" to "version-specific",
                "debian.linexa.yaml" to "generic"
            )
        )
        assertEquals("generic", provider.forDistro("debian")!!.decodeToString())
    }

    @Test
    fun `unknown distro resolves to null`() {
        val provider = providerWith(mapOf("debian.linexa.yaml" to "generic"))
        assertNull(provider.forDistro("alpine", "3.20"))
    }

    @Test
    fun `unsafe ids never reach the asset layer`() {
        val provider = providerWith(emptyMap())
        assertNull(provider.forDistro("../etc", "12"))
        assertNull(provider.forDistro("", "12"))
        assertNull(provider.forDistro("Debian", "12"))
    }

    // ── the shipped Debian/Ubuntu files are schema-valid ─────────────────

    @Test
    fun `shipped debian profile parses and validates with the new schema`() {
        val bytes = providerWithRealAssets().forDistro("debian", "12")
        assertNotNull("debian.linexa.yaml must ship", bytes)
        validateShipped(bytes!!, expectedDistroId = "debian")
    }

    @Test
    fun `shipped ubuntu profile parses and validates with the new schema`() {
        val bytes = providerWithRealAssets().forDistro("ubuntu", "22.04")
        assertNotNull("ubuntu.linexa.yaml must ship", bytes)
        validateShipped(bytes!!, expectedDistroId = "ubuntu")
    }

    @Test
    fun `shipped profiles declare the issue-4 workspace and shell experience`() {
        val provider = providerWithRealAssets()
        for (id in listOf("debian", "ubuntu")) {
            val md = validateShipped(provider.forDistro(id)!!, expectedDistroId = id)
            val reason = { what: String -> "shipped $id profile must declare $what" }
            // Issue #4: a dedicated workspace directory, NOT /root.
            val workspace = md.workspace!!.directory
            assertTrue(reason("a workspace directory"), !workspace.isNullOrBlank())
            assertTrue(
                reason("workspace under a normal home, not /root"),
                workspace!!.startsWith("/home/")
            )
            // Issue #7: the Termux-like alias baseline is declared and documented.
            val aliases = md.shellConfig!!.aliases
            assertTrue(reason("a `la` alias"), aliases.containsKey("la"))
            assertTrue(reason("an `ll` alias"), aliases.containsKey("ll"))
            // Issue #2: a health check the install pipeline can run.
            assertTrue(reason("a health_check command"), md.healthCheck!!.command.isNotBlank())
            // Issue #3: init commands are declared (may be an explicit empty list).
            assertNotNull(reason("an init block"), md.init)
        }
    }

    /** Drives the shipped bytes through the real fail-hard pipeline. */
    private fun validateShipped(bytes: ByteArray, expectedDistroId: String): RootFsMetadata {
        val result = RootFsInspector(WorkspaceConfigParser())
            .inspect(bytes, source = RootFsMetadataFile.NATIVE_DOT)
        assertTrue(
            "shipped profile must pass the fail-hard schema: $result",
            result is RootFsInspector.Result.Success
        )
        val md = (result as RootFsInspector.Result.Success).descriptor.metadata
        assertNotNull(md)
        assertEquals(expectedDistroId, md!!.distro.id)
        return md
    }
}
