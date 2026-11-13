package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §1.146 — resolution against the **real shipped profiles**, using signal sets
 * taken from the actual test images.
 *
 * Every fixture below mirrors a verified archive rather than an invented one,
 * because the bug being fixed was precisely that the invented model (bare entry
 * names, glibc multiarch everywhere, bash always present) did not match reality:
 *
 *  - `alpine-minirootfs-3.24.1-aarch64.tar.gz` — all entries `./`-prefixed,
 *    `etc/os-release` a symlink, no bash;
 *  - Arch Linux ARM aarch64 — `./`-prefixed, empty `etc/arch-release`, and no
 *    architecture-bearing entry name anywhere;
 *  - `Fedora-Container-Base-Generic-42-1.1.aarch64.oci.tar.xz` — an OCI layout,
 *    with the distro markers only inside the layer.
 */
class RootFsProfileResolverTest {

    private val profiles: List<RootFsProfile> = AssetRootFsProfileCatalog.load { fileName ->
        File(assetDir(), fileName).takeIf { it.isFile }?.readBytes()
    }

    // ── Alpine ───────────────────────────────────────────────────────────

    @Test
    fun alpineResolvesFromItsReleaseMarkerAloneWithNoOsRelease() {
        // The real image's `etc/os-release` is a symlink, so a tar walk captures
        // no text for it — only `usr/lib/os-release` and `etc/alpine-release`
        // carry content. This is the exact case that used to fail.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf("etc/alpine-release" to "3.24.1"),
                presentPaths = setOf("etc/alpine-release", "etc/os-release", "bin/busybox"),
                elfArchitectures = mapOf("bin/busybox" to RootFsProbe.RootFsArch.ARM64_V8A)
            ),
            profiles
        )

        assertEquals("alpine", resolution.profile.id)
        assertEquals(RootFsProbe.DistroId.ALPINE, resolution.identity.distroId)
        assertEquals("3.24.1", resolution.identity.version)
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, resolution.identity.arch)
        assertTrue(resolution.detected)
        assertEquals(RootFsProfileSource.BUILT_IN, resolution.source)
    }

    @Test
    fun alpinesShellPlanPutsShBeforeBash() {
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(presentPaths = setOf("etc/alpine-release")),
            profiles
        )
        // The image ships no bash; a bash-first list would report "no shell".
        assertTrue(resolution.profile.layout.shellCandidates.first().endsWith("/sh"))
    }

    // ── Arch ─────────────────────────────────────────────────────────────

    @Test
    fun archResolvesArchitectureFromAnElfHeaderBecauseNoEntryNameCarriesIt() {
        // The whole reason ElfArchitecture exists: Arch's only "aarch64" entry
        // name is a kernel preset file, which would be a guess. With no hints at
        // all, the header is the sole signal.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                presentPaths = setOf("etc/arch-release", "etc/os-release", "usr/bin/bash"),
                elfArchitectures = mapOf("usr/bin/bash" to RootFsProbe.RootFsArch.ARM64_V8A)
            ),
            profiles
        )

        assertEquals("arch", resolution.profile.id)
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, resolution.identity.arch)
        assertTrue("Arch must now be fully detected, not just identified", resolution.detected)
    }

    @Test
    fun archWithoutAnyArchitectureSignalIsIdentifiedButNotClaimedAsDetected() {
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(presentPaths = setOf("etc/arch-release")),
            profiles
        )
        assertEquals("arch", resolution.profile.id)
        assertEquals(RootFsProbe.RootFsArch.UNKNOWN, resolution.identity.arch)
        assertFalse(resolution.detected)
        assertTrue(resolution.notes.any { it.contains("architecture could not be determined") })
    }

    @Test
    fun archReportsUnknownVersionBecauseItIsARollingRelease() {
        // `etc/arch-release` is empty by design. "unknown" is the honest answer;
        // fabricating a version would be worse.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(presentPaths = setOf("etc/arch-release")),
            profiles
        )
        assertEquals("unknown", resolution.identity.version)
    }

    @Test
    fun archArmsOwnOsReleaseIdResolvesToTheArchProfile() {
        // ArchLinuxARM reports ID=archarm, not ID=arch.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(markerTexts = mapOf("etc/os-release" to "ID=archarm\nNAME=\"Arch Linux ARM\"\n")),
            profiles
        )
        assertEquals("arch", resolution.profile.id)
    }

    // ── Debian / Ubuntu ──────────────────────────────────────────────────

    @Test
    fun debianAndUbuntuAreToldApartByOsReleaseNotByTheSharedMarker() {
        val debian = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf("etc/os-release" to "ID=debian\nVERSION_ID=\"12\"\n"),
                presentPaths = setOf("etc/debian_version")
            ),
            profiles
        )
        val ubuntu = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf("etc/os-release" to "ID=ubuntu\nVERSION_ID=\"24.04\"\n"),
                presentPaths = setOf("etc/debian_version")
            ),
            profiles
        )

        assertEquals("debian", debian.profile.id)
        assertEquals("12", debian.identity.version)
        assertEquals("ubuntu", ubuntu.profile.id)
        assertEquals("24.04", ubuntu.identity.version)
        assertNotEquals(debian.profile.id, ubuntu.profile.id)
    }

    @Test
    fun aSharedMarkerAloneResolvesToNeitherDistribution() {
        // `etc/debian_version` is claimed by both profiles, so it is distinctive
        // to neither. Guessing "Ubuntu" from a Debian marker would be worse than
        // falling through to generic.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(presentPaths = setOf("etc/debian_version")),
            profiles
        )
        assertEquals(RootFsProfileSource.GENERIC, resolution.source)
    }

    // ── generic fallback ─────────────────────────────────────────────────

    @Test
    fun anUnknownArchiveGetsTheGenericPlanRatherThanDebiansHardcodedOne() {
        // The substantive half of §1.146: before it, an undetected archive was
        // still pushed through Debian-shaped requirements.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(presentPaths = setOf("etc", "usr")),
            profiles,
            fallbackName = "Void Linux"
        )

        assertEquals(RootFsProfileResolver.GENERIC_PROFILE.id, resolution.profile.id)
        assertEquals(setOf("etc", "usr"), resolution.profile.layout.requiredDirectories)
        assertFalse(resolution.detected)
        // The user's manual selection still labels it.
        assertEquals("Void Linux", resolution.identity.distroName)
        assertTrue(resolution.notes.any { it.contains("no profile matched") })
    }

    @Test
    fun anUnrecognizedOsReleaseIdIsReportedInTheNotes() {
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(markerTexts = mapOf("etc/os-release" to "ID=void\nVERSION_ID=\"rolling\"\n")),
            profiles
        )
        assertEquals(RootFsProfileSource.GENERIC, resolution.source)
        assertTrue(resolution.notes.any { it.contains("'void' matches no built-in profile") })
    }

    @Test
    fun resolutionNeverReturnsANullProfileEvenWithNoProfilesLoaded() {
        // The catalog degrades to an empty list when assets are unreadable; the
        // import must still run a real plan.
        val resolution = RootFsProfileResolver.resolve(ArchiveSignals(), emptyList())
        assertEquals(RootFsProfileResolver.GENERIC_PROFILE.id, resolution.profile.id)
        assertTrue(resolution.plan.isNotEmpty())
    }

    // ── precedence ───────────────────────────────────────────────────────

    @Test
    fun anEmbeddedDeclarationOutranksDetection() {
        val embedded = RootFsProfile(
            id = "alpine",
            name = "My Alpine Remix",
            probeId = RootFsProbe.DistroId.ALPINE,
            source = RootFsProfileSource.EMBEDDED
        )
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                // Signals say Debian; the declaration says Alpine.
                markerTexts = mapOf("etc/os-release" to "ID=debian\n"),
                presentPaths = setOf("etc/debian_version"),
                embedded = embedded
            ),
            profiles
        )
        assertEquals(RootFsProfileSource.EMBEDDED, resolution.source)
        assertEquals("My Alpine Remix", resolution.identity.distroName)
        assertTrue(resolution.notes.any { it.contains("archive declaration") })
    }

    @Test
    fun osReleaseOutranksAMarkerFile() {
        // An Ubuntu image also carries `etc/debian_version`; os-release decides.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf("etc/os-release" to "ID=ubuntu\n"),
                presentPaths = setOf("etc/debian_version", "etc/os-release")
            ),
            profiles
        )
        assertEquals("ubuntu", resolution.profile.id)
        assertTrue(resolution.notes.any { it.contains("os-release ID") })
    }

    @Test
    fun theUsrLibSpellingOfOsReleaseIsUsedWhenTheEtcOneIsASymlink()  {
        // Fedora's and Alpine's real images both symlink `etc/os-release`.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf(ArchiveSignals.OS_RELEASE_USR to "ID=fedora\nVERSION_ID=42\n"),
                presentPaths = setOf("etc/os-release", "usr/lib/os-release")
            ),
            profiles
        )
        assertEquals("fedora", resolution.profile.id)
        assertEquals("42", resolution.identity.version)
    }

    // ── architecture precedence ──────────────────────────────────────────

    @Test
    fun theElfHeaderWinsOverAContradictingEntryNameHint() {
        // A cross-built or mislabelled image: the header reads what the CPU will
        // actually be asked to execute.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf("etc/os-release" to "ID=debian\n"),
                archHintLabels = setOf("x86_64"),
                elfArchitectures = mapOf("bin/bash" to RootFsProbe.RootFsArch.ARM64_V8A)
            ),
            profiles
        )
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, resolution.identity.arch)
    }

    @Test
    fun anEntryNameHintIsUsedWhenNoElfHeaderWasRead() {
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf("etc/os-release" to "ID=debian\n"),
                archHintLabels = setOf("arm64")
            ),
            profiles
        )
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, resolution.identity.arch)
        assertTrue(resolution.notes.any { it.contains("entry-name hint") })
    }

    @Test
    fun anUnpublishedArchitectureIsNotedButNeverRejected() {
        // A self-built rootfs for an unlisted architecture is legitimate; the
        // profile's list documents what the distribution publishes.
        val resolution = RootFsProfileResolver.resolve(
            ArchiveSignals(
                markerTexts = mapOf(ArchiveSignals.OS_RELEASE_USR to "ID=fedora\nVERSION_ID=42\n"),
                elfArchitectures = mapOf("usr/bin/bash" to RootFsProbe.RootFsArch.ARMEABI_V7A)
            ),
            profiles
        )
        assertEquals("fedora", resolution.profile.id)
        assertEquals(RootFsProbe.RootFsArch.ARMEABI_V7A, resolution.identity.arch)
        assertTrue(resolution.detected)
        assertTrue(resolution.notes.any { it.contains("published architectures") })
    }

    // ── watched-path derivation ──────────────────────────────────────────

    @Test
    fun watchedPathsAreDerivedFromTheProfilesRatherThanHardcoded() {
        val markers = RootFsProfileResolver.watchedMarkerPaths(profiles)
        assertTrue(ArchiveSignals.OS_RELEASE_ETC in markers)
        assertTrue(ArchiveSignals.OS_RELEASE_USR in markers)
        assertTrue("etc/alpine-release" in markers)
        assertTrue("etc/arch-release" in markers)

        val elf = RootFsProfileResolver.watchedElfPaths(profiles)
        assertTrue(elf.isNotEmpty())
        assertTrue(elf.any { it.contains("busybox") })
    }

    @Test
    fun archHintsMatchRegardlessOfPackingConventionOrCase() {
        val hints = RootFsProfileResolver.watchedArchHints(profiles)
        assertEquals(
            "arm64",
            RootFsProfileResolver.matchArchHint("./lib/aarch64-linux-gnu/libc.so.6", hints)
        )
        assertEquals(
            "arm64",
            RootFsProfileResolver.matchArchHint("usr/LIB/AARCH64-linux-gnu/libc.so.6", hints)
        )
        assertEquals(null, RootFsProfileResolver.matchArchHint("./etc/hostname", hints))
    }

    // ── os-release parsing ───────────────────────────────────────────────

    @Test
    fun osReleaseParsingFollowsTheSpecsQuotingAndComments() {
        val parsed = RootFsProfileResolver.parseOsRelease(
            """
            # a comment
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.24.1
            PRETTY_NAME="Alpine Linux v3.24"
            """.trimIndent()
        )
        assertEquals("alpine", parsed.id)
        assertEquals("3.24.1", parsed.versionId)
        assertEquals("Alpine Linux", parsed.name)
    }

    @Test
    fun aRollingReleaseWithNoVersionIdParsesWithoutOne() {
        val parsed = RootFsProfileResolver.parseOsRelease("ID=arch\nNAME=\"Arch Linux\"\n")
        assertEquals("arch", parsed.id)
        assertEquals(null, parsed.versionId)
    }

    private companion object {
        fun assetDir(): File {
            val relative = "src/main/assets/${AssetRootFsProfileCatalog.ASSET_DIR}"
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                File(dir, relative).takeIf { it.isDirectory }?.let { return it }
                File(dir, "app/$relative").takeIf { it.isDirectory }?.let { return it }
                dir = dir.parentFile
            }
            error("could not locate $relative")
        }
    }
}
