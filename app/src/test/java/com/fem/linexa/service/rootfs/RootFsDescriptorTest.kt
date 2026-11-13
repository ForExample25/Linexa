package com.fem.linexa.service.rootfs

import com.fem.linexa.service.installer.RootFsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.118 — [RootFsDescriptor]'s two jobs, separately from the parsing pipeline
 * that produces it: translating a declaration into [RootFsProbe]'s vocabulary
 * (so declaring and probed archives are interchangeable downstream) and
 * deciding what counts as a warning rather than a failure.
 *
 * The distinction under test is the one that is easy to get backwards: a
 * declaration-vs-reality disagreement must never block an install, because the
 * disagreeing field is self-reported and extraction does not depend on it.
 */
class RootFsDescriptorTest {

    private fun metadata(
        distroId: String = "debian",
        distroName: String = "Debian GNU/Linux",
        version: String? = "13",
        architecture: DeclaredArchitecture = DeclaredArchitecture.ARM64,
        compressedFormat: String? = null
    ) = RootFsMetadata(
        schemaVersion = 1,
        distro = DeclaredDistro(id = distroId, name = distroName, version = version),
        architecture = architecture,
        rootfs = compressedFormat?.let { DeclaredRootFs(compressedFormat = it) }
    )

    // ---- undeclared ----

    @Test
    fun undeclaredCarriesNoMetadataAndNoWarnings() {
        assertNull(RootFsDescriptor.UNDECLARED.metadata)
        assertTrue(RootFsDescriptor.UNDECLARED.warnings.isEmpty())
        assertTrue(!RootFsDescriptor.UNDECLARED.isDeclared)
    }

    @Test
    fun undeclaredHasNoIdentityToOfferSoTheProbeStaysInCharge() {
        assertNull(RootFsDescriptor.UNDECLARED.declaredIdentity())
    }

    @Test
    fun aNullMetadataStillRecordsTheSniffedFormat() {
        // The format is a property of the bytes, not of the declaration.
        val descriptor = RootFsDescriptor.of(null, RootFsArchiveFormat.TAR_ZST, listOf("arm64-v8a"))
        assertEquals(RootFsArchiveFormat.TAR_ZST, descriptor.archiveFormat)
        assertTrue(descriptor.warnings.isEmpty())
    }

    // ---- identity translation ----

    @Test
    fun aDeclaredIdentityIsMarkedDetected() {
        // A declaration is a stronger signal than any probe, so the install
        // screen must not show the "could not be detected" note for it.
        val identity = RootFsDescriptor.of(metadata(), null).declaredIdentity()!!
        assertTrue(identity.detected)
        assertEquals(RootFsProbe.DistroId.DEBIAN, identity.distroId)
        assertEquals("Debian GNU/Linux", identity.distroName)
        assertEquals("13", identity.version)
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, identity.arch)
    }

    @Test
    fun everyArchitectureTranslatesToADistinctProbeArch() {
        val mapped = DeclaredArchitecture.entries.map { arch ->
            RootFsDescriptor.of(metadata(architecture = arch), null).declaredIdentity()!!.arch
        }
        assertEquals(DeclaredArchitecture.entries.size, mapped.distinct().size)
    }

    @Test
    fun eachArchitecturesAbiMatchesTheProbeArchItMapsTo() {
        // A mismatch here would persist one arch on the workspace record while
        // the ABI check warned about a different one.
        DeclaredArchitecture.entries.forEach { arch ->
            val identity = RootFsDescriptor.of(metadata(architecture = arch), null).declaredIdentity()!!
            assertEquals("mismatch for $arch", arch.abi, identity.arch.abi)
        }
    }

    @Test
    fun aRollingReleaseReportsUnknownRatherThanAnEmptyVersion() {
        val identity = RootFsDescriptor.of(metadata(version = null), null).declaredIdentity()!!
        assertEquals("unknown", identity.version)
    }

    @Test
    fun aBlankVersionIsTreatedAsAbsent() {
        val identity = RootFsDescriptor.of(metadata(version = "   "), null).declaredIdentity()!!
        assertEquals("unknown", identity.version)
    }

    @Test
    fun anUnbrandedDistroIdDegradesToOtherAndKeepsItsName() {
        // The enum exists for iconography; the displayed name comes from
        // distro.name regardless, so an unknown id must not fail.
        val identity = RootFsDescriptor.of(
            metadata(distroId = "void", distroName = "Void Linux"),
            null
        ).declaredIdentity()!!
        assertEquals(RootFsProbe.DistroId.OTHER, identity.distroId)
        assertEquals("Void Linux", identity.distroName)
    }

    @Test
    fun archAliasesAllResolveToTheSameBrandedDistroId() {
        listOf("arch", "archarm", "archlinux", "archlinuxarm").forEach {
            assertEquals("'$it' should be ARCH", RootFsProbe.DistroId.ARCH, RootFsDescriptor.distroIdOf(it))
        }
    }

    @Test
    fun distroIdMatchingIsCaseInsensitive() {
        assertEquals(RootFsProbe.DistroId.DEBIAN, RootFsDescriptor.distroIdOf("Debian"))
        assertEquals(RootFsProbe.DistroId.UBUNTU, RootFsDescriptor.distroIdOf("UBUNTU"))
    }

    // ---- compressed-format warning ----

    @Test
    fun aWrongDeclaredFormatWarnsAndNamesBothFormats() {
        val descriptor = RootFsDescriptor.of(
            metadata(compressedFormat = "tar.gz"),
            RootFsArchiveFormat.TAR_ZST
        )
        assertEquals(1, descriptor.warnings.size)
        assertTrue(descriptor.warnings.single().contains("tar.gz"))
        assertTrue(descriptor.warnings.single().contains("tar.zst"))
    }

    @Test
    fun aCorrectDeclaredFormatIsSilent() {
        val descriptor = RootFsDescriptor.of(
            metadata(compressedFormat = "zstd"),
            RootFsArchiveFormat.TAR_ZST
        )
        assertTrue(descriptor.warnings.isEmpty())
    }

    @Test
    fun anUndeclaredFormatIsNotAMismatch() {
        assertTrue(RootFsDescriptor.of(metadata(), RootFsArchiveFormat.TAR_XZ).warnings.isEmpty())
    }

    @Test
    fun aFormatDeclarationCannotBeCheckedWithoutASniffedFormat() {
        // Inspecting a bare config.yaml (WorkspaceRootFsMetadata) has no
        // archive, so this must not warn about every installed workspace.
        assertTrue(RootFsDescriptor.of(metadata(compressedFormat = "tar.gz"), null).warnings.isEmpty())
    }

    // ---- architecture warning ----

    @Test
    fun anUnsupportedDeclaredArchitectureWarnsButStaysInstallable() {
        val descriptor = RootFsDescriptor.of(
            metadata(architecture = DeclaredArchitecture.X86_64),
            RootFsArchiveFormat.TAR_ZST,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals(1, descriptor.warnings.size)
        assertTrue(descriptor.warnings.single().contains("x86_64"))
        // Warned, not refused: the field is self-reported, and a mislabelled
        // but working rootfs must remain installable.
        assertTrue(descriptor.isDeclared)
    }

    @Test
    fun aSupportedDeclaredArchitectureIsSilent() {
        val descriptor = RootFsDescriptor.of(
            metadata(architecture = DeclaredArchitecture.ARM64),
            null,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )
        assertTrue(descriptor.warnings.isEmpty())
    }

    @Test
    fun a32BitRootfsOnA64BitOnlyAbiListWarns() {
        val descriptor = RootFsDescriptor.of(
            metadata(architecture = DeclaredArchitecture.ARM32),
            null,
            supportedAbis = listOf("arm64-v8a")
        )
        assertEquals(1, descriptor.warnings.size)
    }

    @Test
    fun anEmptyAbiListSkipsTheCheckInsteadOfWarningAboutEverything() {
        val descriptor = RootFsDescriptor.of(
            metadata(architecture = DeclaredArchitecture.X86_64),
            null,
            supportedAbis = emptyList()
        )
        assertTrue(descriptor.warnings.isEmpty())
    }

    @Test
    fun bothWarningsCanBeReportedTogether() {
        val descriptor = RootFsDescriptor.of(
            metadata(architecture = DeclaredArchitecture.X86, compressedFormat = "tar.xz"),
            RootFsArchiveFormat.TAR_GZ,
            supportedAbis = listOf("arm64-v8a")
        )
        assertEquals(2, descriptor.warnings.size)
    }
}
