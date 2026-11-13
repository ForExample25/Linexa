package com.fem.linexa.service.rootfs

import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.147 — every bundled `.linexa.yaml` is schema-valid and self-consistent.
 *
 * `BundledRootfsMetadataProviderVersionTest` pins Debian and Ubuntu one by one.
 * That is exactly the shape of test that lets a newly added Alpine/Fedora/Arch
 * file ship broken: nobody remembers to add the third assertion. This class
 * discovers the directory instead, so adding an asset without a valid schema
 * fails the suite immediately, and it pins the catalog ids the import screen
 * can actually resolve.
 */
class BundledRootfsMetadataAssetsTest {

    private fun metadataDir(): File {
        val relative = "src/main/assets/rootfs/metadata"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isDirectory }?.let { return it }
            File(dir, "app/$relative").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("could not locate $relative")
    }

    private fun shippedAssets(): List<File> =
        metadataDir().listFiles { file -> file.isFile && file.name.endsWith(".linexa.yaml") }
            ?.sortedBy { it.name }
            .orEmpty()

    @Test
    fun everyBundledAssetParsesAndItsFileNameMatchesItsDistroId() {
        val assets = shippedAssets()
        assertTrue("no bundled metadata assets found", assets.isNotEmpty())

        assets.forEach { asset ->
            val expectedId = asset.name.removeSuffix(".linexa.yaml")
            val result = RootFsInspector(WorkspaceConfigParser())
                .inspect(asset.readBytes(), source = RootFsMetadataFile.NATIVE_DOT)

            assertTrue(
                "${asset.name} must pass the fail-hard schema: $result",
                result is RootFsInspector.Result.Success
            )
            val metadata = (result as RootFsInspector.Result.Success).descriptor.metadata
            assertNotNull("${asset.name} must produce metadata", metadata)
            val declared = metadata!!
            assertEquals(
                "${asset.name} must describe the distro its name claims",
                expectedId,
                declared.distro.id
            )
            assertTrue(
                "${asset.name} must declare a display name",
                declared.distro.name.isNotBlank()
            )
            assertTrue(
                "${asset.name} must declare an architecture",
                declared.architecture.label.isNotBlank()
            )
        }
    }

    @Test
    fun theFiveCatalogDistributionsAllShipMetadata() {
        val ids = shippedAssets().map { it.name.removeSuffix(".linexa.yaml") }.toSet()
        assertEquals(
            "the import screen offers five base distributions (Arch ships as archlinux)",
            setOf("alpine", "ubuntu", "debian", "fedora", "archlinux"),
            ids
        )
    }

    @Test
    fun unknownDistroStillResolvesToNull() {
        // Guards the discovery test above: enumerating files must not turn into
        // "any id resolves", which would hide a missing asset behind another.
        val provider = BundledRootfsMetadataProvider { fileName ->
            File(metadataDir(), fileName).takeIf { it.isFile }?.readBytes()
        }
        val alpine = provider.forDistro("alpine", "3.24.1")
        assertNotNull("alpine metadata must resolve by distro id", alpine)
        val inspected = RootFsInspector(WorkspaceConfigParser())
            .inspect(alpine!!, source = RootFsMetadataFile.NATIVE_DOT)
        assertTrue(inspected is RootFsInspector.Result.Success)
        assertEquals(
            "alpine",
            (inspected as RootFsInspector.Result.Success).descriptor.metadata!!.distro.id
        )
        assertEquals(null, provider.forDistro("gentoo", "2.15"))
    }
}
