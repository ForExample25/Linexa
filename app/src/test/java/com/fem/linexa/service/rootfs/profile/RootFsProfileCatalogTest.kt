package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §1.146 — the shipped profile assets are parsed by the real parser here.
 *
 * The point of this test is that it reads `app/src/main/assets/rootfs/profiles`
 * **from disk**, not a fixture copy. [AssetRootFsProfileCatalog.load] on device
 * logs-and-skips a malformed profile so a bad app update degrades to the generic
 * path instead of breaking every import; that leniency means nothing else would
 * ever notice a broken asset. This test is where a broken asset is fatal.
 */
class RootFsProfileCatalogTest {

    private val assetDir: File =
        resolveModulePath("src/main/assets/${AssetRootFsProfileCatalog.ASSET_DIR}")

    private val jniLibsDir: File = resolveModulePath("src/main/jniLibs")

    private val source = ProfileByteSource { fileName ->
        File(assetDir, fileName).takeIf { it.isFile }?.readBytes()
    }

    private val profiles: List<RootFsProfile> by lazy { AssetRootFsProfileCatalog.load(source) }

    @Test
    fun everyShippedProfileParses() {
        assertEquals(
            "one or more shipped profiles failed to parse — see the logged reason",
            AssetRootFsProfileCatalog.BUILT_IN_FILES.size,
            profiles.size
        )
    }

    @Test
    fun everyProfileFileIsListedInTheCatalog() {
        // A profile that is shipped but not listed would never activate, and
        // nothing at runtime would report it. Catch it here instead.
        val onDisk = assetDir.listFiles { f -> f.name.endsWith(".yaml") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        assertEquals(AssetRootFsProfileCatalog.BUILT_IN_FILES.sorted(), onDisk)
    }

    @Test
    fun profileIdsAreUniqueAndMatchTheirFileNames() {
        val ids = profiles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        profiles.forEach { profile ->
            assertTrue(
                "profile '${profile.id}' should be shipped as '${profile.id}.yaml'",
                "${profile.id}.yaml" in AssetRootFsProfileCatalog.BUILT_IN_FILES
            )
        }
    }

    @Test
    fun everyProfileDeclaresSomethingItCanBeRecognizedBy() {
        profiles.forEach { profile ->
            assertTrue(
                "profile '${profile.id}' declares neither os-release IDs nor marker files, " +
                    "so no archive could ever resolve to it",
                profile.detection.osReleaseIds.isNotEmpty() || profile.detection.markerFiles.isNotEmpty()
            )
        }
    }

    @Test
    fun everyProfileCanReportAnArchitecture() {
        // A profile with neither hints nor ELF probe paths can be identified but
        // never fully detected, because RootFsProfileResolver requires an
        // architecture before it calls detection successful. That is exactly the
        // Arch Linux ARM failure §1.146 exists to fix, so it must not recur.
        profiles.forEach { profile ->
            assertTrue(
                "profile '${profile.id}' has no way to determine architecture",
                profile.detection.architectureHints.isNotEmpty() ||
                    profile.detection.elfProbePaths.isNotEmpty()
            )
        }
    }

    @Test
    fun everyProfileShipsAnInstallPlanAndAShellList() {
        profiles.forEach { profile ->
            assertTrue("profile '${profile.id}' has no install steps", profile.install.isNotEmpty())
            assertTrue(
                "profile '${profile.id}' has no shell candidates",
                profile.layout.shellCandidates.isNotEmpty()
            )
        }
    }

    @Test
    fun alpineIsShShapedRatherThanBashShaped() {
        // Verified against alpine-minirootfs-3.24.1-aarch64: the image ships no
        // bash at all. A bash-first shell list would report "no shell" for a
        // perfectly good rootfs, so this ordering is a real requirement.
        val alpine = profiles.first { it.id == "alpine" }
        assertTrue(alpine.layout.shellCandidates.first().endsWith("/sh"))
        assertTrue(alpine.layout.shellCandidates.none { it.endsWith("bash") })
    }

    @Test
    fun archDeclaresElfProbesBecauseItsNamesRevealNothing() {
        // Arch Linux ARM ships no multiarch directory and no musl loader, so the
        // ELF header is its only architecture signal.
        val arch = profiles.first { it.id == "arch" }
        assertTrue(arch.detection.elfProbePaths.isNotEmpty())
        assertTrue(
            "arch resolves 'archarm' from os-release",
            "archarm" in arch.detection.osReleaseIds || "archarm" in arch.allIds
        )
    }

    @Test
    fun fedoraIsDeclaredAsAnOciImageLayout() {
        // What a user downloading "the Fedora rootfs" actually gets is
        // Fedora-Container-Base-Generic-42-1.1.aarch64.oci.tar.xz.
        val fedora = profiles.first { it.id == "fedora" }
        assertEquals(ArchiveKind.OCI_IMAGE_LAYOUT, fedora.layout.archiveKind)
        assertTrue(InstallStep.UNWRAP_OCI_LAYER in fedora.install)
    }

    @Test
    fun fedoraWatchesTheUsrLibOsReleaseSpellingToo() {
        // In the real image `etc/os-release` is a symlink, which carries no data
        // in a tar. Listing only that spelling would capture nothing.
        val fedora = profiles.first { it.id == "fedora" }
        assertTrue(ArchiveSignals.OS_RELEASE_USR in fedora.detection.markerFiles)
    }

    @Test
    fun noProfileDeclaresAPathThatEscapesTheRootfs() {
        val allPaths = profiles.flatMap { profile ->
            profile.detection.markerFiles +
                profile.detection.elfProbePaths +
                profile.layout.requiredDirectories +
                profile.layout.shellCandidates +
                listOfNotNull(profile.detection.versionFrom)
        }
        allPaths.forEach { path ->
            assertTrue("'$path' is absolute", !path.startsWith("/"))
            assertTrue("'$path' contains a traversal segment", ".." !in path.split('/'))
        }
    }

    @Test
    fun aMissingProfileIsSkippedRatherThanThrowing() {
        val loaded = AssetRootFsProfileCatalog.load { null }
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun versionFromAlwaysNamesAFileTheProfileAlsoWatches() {
        // Otherwise the scan would never capture it and the version would
        // silently be "unknown".
        profiles.forEach { profile ->
            val from = profile.detection.versionFrom ?: return@forEach
            assertTrue(
                "profile '${profile.id}' reads its version from '$from' but does not list it as a marker",
                from in profile.detection.markerFiles
            )
        }
    }

    @Test
    fun theGenericProfileRequiresOnlyWhatEveryRootfsHas() {
        // Deliberately the intersection, not Debian's list: `bin`, `lib` and
        // `var` are not universally top-level in a usr-merged tree.
        val generic = RootFsProfileResolver.GENERIC_PROFILE
        assertEquals(setOf("etc", "usr"), generic.layout.requiredDirectories)
        assertNull(generic.environment.loginShell)
        assertTrue(generic.install.isNotEmpty())
        assertEquals(RootFsProfileSource.GENERIC, generic.source)
    }

    @Test
    fun theRealAssetDirectoryWasFound() {
        assertNotNull(assetDir)
        assertTrue("asset dir does not exist: $assetDir", assetDir.isDirectory)
    }

    @Test
    fun everyAdvertisedArchitectureHasAPackagedProot() {
        // `supported_architectures` now promises that *this build* can run the
        // rootfs. That promise only holds when the matching PRoot binary is in
        // the APK; otherwise the import is accepted and the workspace fails at
        // launch. The `third_party/proot/` directory holds build inputs, not
        // packaged ABIs, so only directories carrying a
        // `libproot.so` count.
        val packagedAbis = jniLibsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "libproot.so").isFile }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
        assertTrue("no packaged libproot.so found in $jniLibsDir", packagedAbis.isNotEmpty())
        profiles.forEach { profile ->
            profile.supportedArchitectures.forEach { arch ->
                val rootfsArch = RootFsProbe.RootFsArch.entries.firstOrNull { it.label == arch }
                assertNotNull(
                    "profile '${profile.id}' advertises '$arch', which no RootFsArch maps to an ABI",
                    rootfsArch
                )
                assertTrue(
                    "profile '${profile.id}' advertises '$arch' (${rootfsArch!!.abi}) but " +
                        "jniLibs/${rootfsArch.abi}/libproot.so is not packaged " +
                        "(packaged ABIs: $packagedAbis)",
                    rootfsArch.abi in packagedAbis
                )
            }
        }
    }

    private companion object {
        /**
         * Walks up from the working directory to find a module-relative path.
         *
         * Gradle runs unit tests with the working directory set to the module,
         * but that is a convention rather than a guarantee (IDE runners differ),
         * so the search is done rather than assumed.
         */
        fun resolveModulePath(relative: String): File {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                val candidate = File(dir, relative)
                if (candidate.exists()) return candidate
                val nested = File(dir, "app/$relative")
                if (nested.exists()) return nested
                dir = dir.parentFile
            }
            error("could not locate $relative from ${File(".").absolutePath}")
        }
    }
}
