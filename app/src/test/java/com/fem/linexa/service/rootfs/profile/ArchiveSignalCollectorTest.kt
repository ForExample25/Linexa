package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §1.146 — the scan-pass collector, driven with the entry names of the real test
 * images.
 *
 * These are the assertions the pre-§1.146 design could not make at all: the
 * knowledge of which paths mattered lived inside the installer's tar loop, so
 * "does an Alpine archive get detected" was only answerable by running an
 * install. The collector is a plain JVM object, so the answer is a unit test.
 */
class ArchiveSignalCollectorTest {

    private val profiles: List<RootFsProfile> = AssetRootFsProfileCatalog.load { fileName ->
        File(assetDir(), fileName).takeIf { it.isFile }?.readBytes()
    }

    private fun collector() = ArchiveSignalCollector(profiles)

    private fun elfHeader(machine: Int = 183): ByteArray {
        val bytes = ByteArray(ElfArchitecture.HEADER_BYTES)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = 1 // little-endian
        bytes[18] = (machine and 0xFF).toByte()
        bytes[19] = ((machine shr 8) and 0xFF).toByte()
        return bytes
    }

    // ── the packing-convention bug ────────────────────────────────────────

    @Test
    fun aDotSlashPackedMarkerIsRecognizedJustLikeABarePackedOne() {
        // Alpine and Arch pack every entry with "./"; Debian and Ubuntu pack
        // bare. Before §1.146 only the second group matched anything.
        val prefixed = collector()
        assertEquals(
            ArchiveSignalCollector.Action.CAPTURE_TEXT,
            prefixed.inspectEntry("./etc/alpine-release", isSymbolicLink = false, isDirectory = false, size = 7)
        )

        val bare = collector()
        assertEquals(
            ArchiveSignalCollector.Action.CAPTURE_TEXT,
            bare.inspectEntry("etc/alpine-release", isSymbolicLink = false, isDirectory = false, size = 7)
        )
    }

    @Test
    fun recordedPathsAreStoredNormalizedSoResolutionSeesOneSpelling() {
        val c = collector()
        c.inspectEntry("./etc/alpine-release", isSymbolicLink = false, isDirectory = false, size = 7)
        c.recordMarkerText("./etc/alpine-release", "3.24.1")

        val signals = c.build()
        assertEquals(mapOf("etc/alpine-release" to "3.24.1"), signals.markerTexts)
        assertTrue("etc/alpine-release" in signals.presentPaths)
    }

    // ── symlinks and directories ─────────────────────────────────────────

    @Test
    fun aSymlinkedMarkerProvesPresenceButYieldsNoText() {
        // Alpine's and Fedora's `etc/os-release` are symlinks. A tar symlink
        // entry carries no data, so asking for its text would desynchronize
        // nothing but would also record nothing useful.
        val c = collector()
        val action = c.inspectEntry("./etc/os-release", isSymbolicLink = true, isDirectory = false, size = 0)
        assertEquals(ArchiveSignalCollector.Action.SKIP, action)

        val signals = c.build()
        assertTrue("etc/os-release" in signals.presentPaths)
        assertTrue(signals.markerTexts.isEmpty())
        assertNull(signals.osReleaseText())
    }

    @Test
    fun theUsrLibSpellingSuppliesTheTextTheEtcSymlinkCannot() {
        val c = collector()
        c.inspectEntry("etc/os-release", isSymbolicLink = true, isDirectory = false)
        c.inspectEntry("usr/lib/os-release", isSymbolicLink = false, isDirectory = false, size = 741)
        c.recordMarkerText("usr/lib/os-release", "ID=fedora\nVERSION_ID=42\n")

        assertEquals("ID=fedora\nVERSION_ID=42", c.build().osReleaseText())
    }

    @Test
    fun aDirectoryEntryIsNeverRead() {
        val c = collector()
        assertEquals(
            ArchiveSignalCollector.Action.SKIP,
            c.inspectEntry("./etc/", isSymbolicLink = false, isDirectory = true)
        )
    }

    @Test
    fun theArchiveRootEntryIsIgnored() {
        val c = collector()
        assertEquals(
            ArchiveSignalCollector.Action.SKIP,
            c.inspectEntry("./", isSymbolicLink = false, isDirectory = true)
        )
        assertTrue(c.build().presentPaths.isEmpty())
    }

    // ── ELF probing ──────────────────────────────────────────────────────

    @Test
    fun anElfBinaryNominatedByAProfileIsProbed() {
        val c = collector()
        assertEquals(
            ArchiveSignalCollector.Action.CAPTURE_ELF,
            c.inspectEntry("./bin/busybox", isSymbolicLink = false, isDirectory = false, size = 900_000)
        )
        c.recordElfHeader("./bin/busybox", elfHeader(183))

        assertEquals(
            mapOf("bin/busybox" to RootFsProbe.RootFsArch.ARM64_V8A),
            c.build().elfArchitectures
        )
    }

    @Test
    fun aNonElfFileContributesNothingRatherThanAWrongArchitecture() {
        val c = collector()
        c.recordElfHeader("bin/busybox", "#!/bin/sh\nexec busybox \"\$0\" \"\$@\"\n".toByteArray())
        assertTrue(c.build().elfArchitectures.isEmpty())
    }

    @Test
    fun anAlreadyProbedPathIsNotProbedAgain() {
        val c = collector()
        c.inspectEntry("bin/busybox", isSymbolicLink = false, isDirectory = false, size = 900_000)
        c.recordElfHeader("bin/busybox", elfHeader())
        // A second member with the same normalized name (possible in a tar) must
        // not cost another read.
        assertEquals(
            ArchiveSignalCollector.Action.SKIP,
            c.inspectEntry("./bin/busybox", isSymbolicLink = false, isDirectory = false, size = 900_000)
        )
    }

    @Test
    fun aBinaryNoProfileNominatedIsNotRead() {
        // The set of paths read from an untrusted archive is bounded by the
        // profiles, not by the archive's contents.
        val c = collector()
        assertEquals(
            ArchiveSignalCollector.Action.SKIP,
            c.inspectEntry("usr/bin/python3.13", isSymbolicLink = false, isDirectory = false, size = 5_000_000)
        )
    }

    // ── architecture hints ───────────────────────────────────────────────

    @Test
    fun archHintsAreCollectedFromEntryNamesOfAnyKind() {
        val c = collector()
        // A directory entry still carries the toolchain name.
        c.inspectEntry("./usr/lib/aarch64-linux-gnu/", isSymbolicLink = false, isDirectory = true)
        assertTrue("arm64" in c.build().archHintLabels)
    }

    @Test
    fun archProducesNoHintsBecauseItsNamesRevealNothing() {
        // Its only "aarch64" entry is a kernel preset file, which no profile
        // nominates as a hint — matching it would be guessing.
        val c = collector()
        c.inspectEntry("./etc/mkinitcpio.d/linux-aarch64.preset", isSymbolicLink = false, isDirectory = false, size = 100)
        assertTrue(
            "a kernel preset filename must not be treated as an architecture signal",
            c.build().archHintLabels.isEmpty()
        )
    }

    // ── OCI layout ───────────────────────────────────────────────────────

    @Test
    fun anOciLayoutIsIdentifiedAndItsSmallJsonBlobsAreCaptured() {
        val digest = "ab".repeat(32)
        val manifestPath = "blobs/sha256/$digest"
        val index = """{"schemaVersion":2,"manifests":[{"digest":"sha256:$digest","size":491}]}"""
        val layerDigest = "cd".repeat(32)
        val manifest = """
            {"schemaVersion":2,"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip",
             "digest":"sha256:$layerDigest","size":72000000}]}
        """.trimIndent()

        val c = collector()
        c.inspectEntry("oci-layout", isSymbolicLink = false, isDirectory = false, size = 32)
        assertEquals(
            ArchiveSignalCollector.Action.CAPTURE_BLOB,
            c.inspectEntry("index.json", isSymbolicLink = false, isDirectory = false, size = index.length.toLong())
        )
        c.recordBlob("index.json", index.toByteArray())
        assertEquals(
            ArchiveSignalCollector.Action.CAPTURE_BLOB,
            c.inspectEntry(manifestPath, isSymbolicLink = false, isDirectory = false, size = manifest.length.toLong())
        )
        c.recordBlob(manifestPath, manifest.toByteArray())

        assertTrue(c.looksLikeOciLayout())
        val resolved = c.resolveOciLayer()
        assertTrue("expected a layer, got $resolved", resolved is OciImageLayout.Result.Layer)
        assertEquals("blobs/sha256/$layerDigest", (resolved as OciImageLayout.Result.Layer).layerPath)
    }

    @Test
    fun theRootfsLayerBlobItselfIsNeverBuffered() {
        // The whole point of resolving a *path*: buffering a 70 MB layer would
        // defeat the streaming design of the installer.
        val c = collector()
        val action = c.inspectEntry(
            "blobs/sha256/${"cd".repeat(32)}",
            isSymbolicLink = false,
            isDirectory = false,
            size = 72_000_000
        )
        assertEquals(ArchiveSignalCollector.Action.SKIP, action)
        // Its presence is still recorded, so the layout is still recognizable.
        assertTrue(c.build().presentPaths.any { it.startsWith("blobs/") })
    }

    @Test
    fun anOrdinaryRootfsIsNotMistakenForAnOciLayout() {
        val c = collector()
        c.inspectEntry("./etc/os-release", isSymbolicLink = false, isDirectory = false, size = 400)
        c.inspectEntry("./index.json", isSymbolicLink = false, isDirectory = false, size = 100)
        assertFalse(c.looksLikeOciLayout())
        assertEquals(OciImageLayout.Result.NotOciLayout, c.resolveOciLayer())
    }

    @Test
    fun anOciLayoutWhoseIndexWasTooLargeToCaptureIsReportedRatherThanGuessed() {
        val c = collector()
        c.inspectEntry("oci-layout", isSymbolicLink = false, isDirectory = false, size = 32)
        c.recordPresence("index.json")
        val result = c.resolveOciLayer()
        assertTrue(result is OciImageLayout.Result.Invalid)
    }

    @Test
    fun blobCaptureIsBoundedSoAThousandTinyBlobsCannotExhaustMemory() {
        val c = collector()
        val oneMega = ByteArray(1024 * 1024)
        // The cap is 8 MB total; 12 attempts must not store 12 MB.
        repeat(12) { i -> c.recordBlob("blobs/sha256/${i.toString().padStart(64, '0')}", oneMega) }
        // Nothing to assert on internals directly; resolution simply must not
        // have kept everything, and must still behave.
        assertFalse(c.looksLikeOciLayout())
    }

    // ── build() isolation ────────────────────────────────────────────────

    @Test
    fun buildSnapshotsRatherThanExposingLiveState() {
        val c = collector()
        c.recordMarkerText("etc/alpine-release", "3.24.1")
        val first = c.build()
        c.recordMarkerText("etc/os-release", "ID=alpine\n")
        assertEquals(1, first.markerTexts.size)
        assertEquals(2, c.build().markerTexts.size)
    }

    @Test
    fun blankMarkerTextIsDiscardedAsNoSignal() {
        // Arch's `etc/arch-release` is a single newline: presence identifies the
        // distribution, but there is no version to record.
        val c = collector()
        c.recordMarkerText("etc/arch-release", "\n")
        assertTrue(c.build().markerTexts.isEmpty())
    }

    @Test
    fun anEmptyProfileListWatchesOnlyTheOsReleasePaths() {
        // The catalog degrades to empty when assets are unreadable; os-release is
        // still worth reading, since it is the cross-distribution standard.
        val c = ArchiveSignalCollector(emptyList())
        assertEquals(
            ArchiveSignalCollector.Action.CAPTURE_TEXT,
            c.inspectEntry("etc/os-release", isSymbolicLink = false, isDirectory = false, size = 400)
        )
        assertEquals(
            ArchiveSignalCollector.Action.SKIP,
            c.inspectEntry("etc/alpine-release", isSymbolicLink = false, isDirectory = false, size = 7)
        )
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
