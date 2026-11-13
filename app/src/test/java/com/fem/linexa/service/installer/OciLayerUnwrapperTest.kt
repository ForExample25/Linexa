package com.fem.linexa.service.installer

import com.fem.linexa.core.security.RealArchiveValidator
import com.fem.linexa.service.rootfs.profile.OciImageLayout
import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.146 — [OciLayerUnwrapper.unwrap] over genuine OCI-shaped archive bytes.
 *
 * ## Why this class exists
 *
 * The Fedora/Arch install work closed
 * every JVM-reachable gap in the install pipeline except this one: the OCI
 * branch. [StagedRootFsInstallerScanTest] mocks the unwrapper and asserts
 * `ociLayerPath == null`, so it pins the *plain tarball* path and says nothing
 * about what happens to a `.oci.tar.xz`. [OciImageLayoutTest] proves which blob
 * path gets resolved, but stops at the path — nothing exercised the code that
 * turns that path into a file on disk. That leaves the one step between "we know
 * which blob is the rootfs" and "tar has a rootfs to extract" untested.
 *
 * This class closes it at the JVM level. What it cannot close, and what no JVM
 * test can, is whether the unwrapped layer then extracts correctly under the real
 * PRoot runtime (audit §20.3 items 1-3).
 *
 * ## The property that matters most
 *
 * The extracted layer must be **byte-identical** to the blob inside the image,
 * still compressed. [OciLayerUnwrapper] deliberately writes an extensionless file
 * and lets GNU tar sniff the magic bytes (see [OciLayerUnwrapper.LAYER_FILE_NAME]'s
 * kdoc), so a unwrapper that helpfully decompressed the blob, or truncated it,
 * would produce a file that fails much later inside `proot tar -xf` as an
 * "Unexpected EOF in archive" — pointing the user at the extractor instead of at
 * this step. `theExtractedLayerIsByteIdenticalToTheBlobInTheImage` is therefore
 * the load-bearing assertion here, not the happy-path `is Layer` check.
 *
 * ## Fixture shape
 *
 * Digests and layout mirror the verified contents of
 * `Fedora-Container-Base-Generic-42-1.1.aarch64.oci.tar.xz` as documented on
 * [OciImageLayout]: `oci-layout`, `index.json`, and three blobs under
 * `blobs/sha256/` (manifest, rootfs layer, config). The outer container is XZ in
 * most tests because that is what Fedora actually publishes.
 */
class OciLayerUnwrapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * The real validator, not a stub: the §1.148 validate pass is only
     * meaningful if the rules it applies are the production ones. This is the
     * same instance type Hilt binds for [OciLayerUnwrapper]'s constructor
     * parameter and the same one [StagedRootFsInstaller] receives.
     */
    private val unwrapper = OciLayerUnwrapper(RealArchiveValidator())

    private val manifestDigest = "sha256:" + "cf9e82".padEnd(64, '0')
    private val layerDigest = "sha256:" + "cfc0be".padEnd(64, '0')
    private val configDigest = "sha256:" + "f8c61c".padEnd(64, '0')

    /** Resolved the same way production does — never hand-spelled. */
    private val layerPath = OciImageLayout.blobPathFor(layerDigest)!!
    private val manifestPath = OciImageLayout.blobPathFor(manifestDigest)!!
    private val configPath = OciImageLayout.blobPathFor(configDigest)!!

    private lateinit var workDir: File

    /**
     * A real gzipped tar standing in for Fedora's rootfs layer
     * (`application/vnd.oci.image.layer.v1.tar+gzip`). Large enough that
     * `copyTo` must loop rather than land in one buffer.
     */
    private val layerBlob: ByteArray by lazy {
        tar(
            compress = ::gzip,
            members = arrayOf(
                dir("./etc/"),
                dir("./usr/"),
                file("./etc/os-release", "NAME=\"Fedora Linux\"\nID=fedora\nVERSION_ID=44\n"),
                file("./usr/bin/filler", "x".repeat(300 * 1024))
            )
        )
    }

    private fun setUp() {
        workDir = tmp.newFolder("import-cache")
    }

    private fun target() = File(workDir, OciLayerUnwrapper.LAYER_FILE_NAME)

    // ── the happy path: Fedora's published shape ──────────────────────────

    @Test
    fun unwrapsTheRootfsLayerOutOfAnXzOciImage() {
        setUp()
        val image = ociImage(compress = ::xz)

        val result = unwrapper.unwrap(image, workDir, layerPath)

        val layer = result as? OciLayerUnwrapper.Result.Layer
            ?: fail("the layer must be unwrapped", result)
        assertEquals(target().absolutePath, layer.file.absolutePath)
        assertTrue("the unwrapped layer must exist on disk", layer.file.isFile)
    }

    @Test
    fun theExtractedLayerIsByteIdenticalToTheBlobInTheImage() {
        setUp()
        val image = ociImage(compress = ::xz)

        val layer = unwrapper.unwrap(image, workDir, layerPath) as OciLayerUnwrapper.Result.Layer

        // The whole contract: the blob comes out exactly as it went in, still
        // compressed. GNU tar auto-detects its format from these bytes, so any
        // transformation here is a bug that only surfaces under proot.
        assertArrayEquals(layerBlob, layer.file.readBytes())
        assertEquals(layerBlob.size.toLong(), layer.file.length())
    }

    @Test
    fun theUnwrappedLayerKeepsItsOwnCompressionMagicRatherThanBeingDecompressed() {
        setUp()
        val layer = unwrapper.unwrap(ociImage(compress = ::xz), workDir, layerPath)
            as OciLayerUnwrapper.Result.Layer

        // Fedora's layer is gzip inside an xz outer archive. Decompressing the
        // inner blob (an easy mistake, since the outer one must be decompressed)
        // would leave a bare tar here and lose that distinction.
        val head = layer.file.readBytes().take(2)
        assertEquals(
            "the layer must still be gzip, not a decompressed tar",
            listOf(0x1F.toByte(), 0x8B.toByte()),
            head
        )
    }

    @Test
    fun theLayerIsWrittenUnderTheExtensionlessNameInsideTheGivenWorkDir() {
        setUp()
        unwrapper.unwrap(ociImage(compress = ::xz), workDir, layerPath)

        // The import cache is deleted wholesale on success or failure, so the
        // layer must land inside it and nowhere else.
        assertEquals(
            listOf(OciLayerUnwrapper.LAYER_FILE_NAME),
            workDir.listFiles()!!.map { it.name }.sorted()
        )
        assertFalse(
            "the name must stay extensionless — an extension would be a claim about the layer's format",
            OciLayerUnwrapper.LAYER_FILE_NAME.contains('.')
        )
    }

    @Test
    fun aGzipOuterContainerWorksToo() {
        setUp()
        // Not every registry export is xz; the unwrapper delegates container
        // sniffing to CompressedArchiveStream and must not care which it got.
        val result = unwrapper.unwrap(ociImage(compress = ::gzip), workDir, layerPath)

        val layer = result as? OciLayerUnwrapper.Result.Layer
            ?: fail("a gzipped OCI image must unwrap", result)
        assertArrayEquals(layerBlob, layer.file.readBytes())
    }

    @Test
    fun aLayerPackedWithoutTheDotSlashPrefixIsStillFound() {
        setUp()
        // Entry names are a property of how the image was packed, not of the
        // image (ArchiveEntryPath's kdoc). Matching must go through normalization,
        // so both spellings of the same blob resolve.
        val image = writeArchive(
            "image.tar.gz",
            tar(
                compress = ::gzip,
                members = arrayOf(
                    file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                    file(OciImageLayout.INDEX_JSON, indexJson()),
                    file("./$manifestPath", manifestJson()),
                    Member("./$layerPath", layerBlob)
                )
            )
        )

        val result = unwrapper.unwrap(image, workDir, layerPath)

        val layer = result as? OciLayerUnwrapper.Result.Layer
            ?: fail("a './'-packed layer must still be found", result)
        assertArrayEquals(layerBlob, layer.file.readBytes())
    }

    @Test
    fun theLayerIsFoundWhenItIsTheLastEntryInTheImage() {
        setUp()
        // Blob order inside an OCI tar is not specified. A walk that stopped
        // early, or that relied on the layer preceding the config, would pass the
        // default fixture and fail here.
        val image = writeArchive(
            "trailing.tar.xz",
            tar(
                compress = ::xz,
                members = arrayOf(
                    file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                    file(OciImageLayout.INDEX_JSON, indexJson()),
                    file(configPath, """{"architecture":"arm64","os":"linux"}"""),
                    file(manifestPath, manifestJson()),
                    Member(layerPath, layerBlob)
                )
            )
        )

        val result = unwrapper.unwrap(image, workDir, layerPath)
        assertTrue("expected a layer, got $result", result is OciLayerUnwrapper.Result.Layer)
    }

    // ── malformed images produce a named failure, not a broken install ────

    @Test
    fun aLayerNamedByIndexJsonButAbsentFromTheArchiveIsReported() {
        setUp()
        // index.json is the authority on which blob is the rootfs, so a missing
        // blob means the image itself is inconsistent. Saying so beats letting
        // tar fail on a file that was never written.
        val image = writeArchive(
            "missing-layer.tar.xz",
            tar(
                compress = ::xz,
                members = arrayOf(
                    file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                    file(OciImageLayout.INDEX_JSON, indexJson()),
                    file(manifestPath, manifestJson())
                )
            )
        )

        val failure = unwrapper.unwrap(image, workDir, layerPath) as? OciLayerUnwrapper.Result.Failure
            ?: fail("an absent layer must fail", OciLayerUnwrapper.Result.Layer(target()))
        assertEquals(
            "OCI image layer '$layerPath' was named by index.json but is not present in the archive",
            failure.reason
        )
        assertFalse("no partial layer may be left behind", target().exists())
    }

    @Test
    fun aZeroByteLayerIsRejectedRatherThanHandedToTar() {
        setUp()
        // A zero-byte file is a valid file and an invalid archive. Left to tar it
        // becomes "Unexpected EOF in archive" plus a proot exit code, which reads
        // as an extractor bug rather than a malformed image.
        val image = writeArchive(
            "empty-layer.tar.xz",
            tar(
                compress = ::xz,
                members = arrayOf(
                    file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                    file(OciImageLayout.INDEX_JSON, indexJson()),
                    file(manifestPath, manifestJson()),
                    Member(layerPath, ByteArray(0))
                )
            )
        )

        val failure = unwrapper.unwrap(image, workDir, layerPath) as? OciLayerUnwrapper.Result.Failure
            ?: fail("an empty layer must fail", OciLayerUnwrapper.Result.Layer(target()))
        assertEquals("OCI image layer '$layerPath' is empty", failure.reason)
        assertFalse("the empty file must be deleted, not left for tar", target().exists())
    }

    @Test
    fun aDirectoryEntryMatchingTheLayerPathIsNotMistakenForTheLayer() {
        setUp()
        // `blobs/sha256/<hex>/` as a directory would otherwise match the
        // normalized path (normalize() strips the trailing slash) and produce a
        // zero-byte "layer".
        val image = writeArchive(
            "dir-collision.tar.xz",
            tar(
                compress = ::xz,
                members = arrayOf(
                    file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                    file(OciImageLayout.INDEX_JSON, indexJson()),
                    file(manifestPath, manifestJson()),
                    dir("$layerPath/"),
                    Member(layerPath, layerBlob)
                )
            )
        )

        val result = unwrapper.unwrap(image, workDir, layerPath)

        val layer = result as? OciLayerUnwrapper.Result.Layer
            ?: fail("the file entry must win over the directory entry", result)
        assertArrayEquals(layerBlob, layer.file.readBytes())
    }

    @Test
    fun anUncompressedOuterArchiveIsUnwrappedLikeACompressedOne() {
        setUp()
        // §1.147: CompressedArchiveStream now recognizes a bare tar from its
        // ustar marker/header checksum. An OCI image exported without outer
        // compression is therefore a valid container, not an "unsupported
        // format" — the same bytes tar accepts must also unwrap here.
        val bare = writeArchive("bare.tar", tar(compress = { it }, members = arrayOf(Member(layerPath, layerBlob))))

        val result = unwrapper.unwrap(bare, workDir, layerPath)

        val layer = result as? OciLayerUnwrapper.Result.Layer
            ?: fail("an uncompressed outer tar must unwrap successfully", result)
        assertArrayEquals(layerBlob, layer.file.readBytes())
    }

    @Test
    fun aTruncatedImageFailsCleanlyInsteadOfThrowing() {
        setUp()
        val whole = ociImage(compress = ::gzip).readBytes()
        val truncated = writeArchive("truncated.tar.gz", whole.copyOf(whole.size / 3))

        val result = unwrapper.unwrap(truncated, workDir, layerPath)

        assertTrue("expected a Failure, got $result", result is OciLayerUnwrapper.Result.Failure)
        assertFalse("a partial layer must not survive a truncated image", target().exists())
    }

    @Test
    fun animageTruncatedPartwayThroughTheLayerLeavesNoPartialFile() {
        setUp()
        // The case above cuts before the layer is reached, so nothing is ever
        // written and the cleanup in the catch block is not exercised. This one
        // cuts *inside* the blob: the copy starts, writes real bytes, then the
        // inflater throws. Without the delete on the exception path a partial
        // archive of plausible size would be left for tar to choke on. The blob
        // is incompressible (seeded random) and last in the archive so the cut
        // lands mid-copy rather than mid-header.
        val random = java.util.Random(SEED)
        val incompressible = ByteArray(400 * 1024).also { random.nextBytes(it) }
        val whole = tar(
            compress = ::gzip,
            members = arrayOf(
                file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                file(OciImageLayout.INDEX_JSON, indexJson()),
                file(manifestPath, manifestJson()),
                Member(layerPath, incompressible)
            )
        )
        val truncated = writeArchive("mid-layer.tar.gz", whole.copyOf(whole.size * 95 / 100))

        val result = unwrapper.unwrap(truncated, workDir, layerPath)

        assertTrue("expected a Failure, got $result", result is OciLayerUnwrapper.Result.Failure)
        assertFalse(
            "a layer half-written before the stream died must be deleted, not handed to tar",
            target().exists()
        )
    }

    @Test
    fun aNonexistentOuterArchiveIsAFailureNotAnException() {
        setUp()
        val absent = File(workDir, "never-written.tar.xz")

        val result = unwrapper.unwrap(absent, workDir, layerPath)

        assertTrue("expected a Failure, got $result", result is OciLayerUnwrapper.Result.Failure)
    }

    // ── retry safety ──────────────────────────────────────────────────────

    @Test
    fun aLeftoverLayerFromAnInterruptedAttemptIsReplacedNotAppendedTo() {
        setUp()
        // InstallRecoveryManager clears the cache at startup, but an install()
        // retried inside one session would find the previous attempt's truncated
        // file here. Appending to it would produce a corrupt archive whose size
        // looks plausible.
        target().writeBytes(ByteArray(4096) { 0x7F })

        val layer = unwrapper.unwrap(ociImage(compress = ::xz), workDir, layerPath)
            as OciLayerUnwrapper.Result.Layer

        assertArrayEquals(layerBlob, layer.file.readBytes())
        assertEquals(layerBlob.size.toLong(), layer.file.length())
    }

    @Test
    fun aFailedUnwrapDeletesTheLeftoverFromAnEarlierSuccess() {
        setUp()
        // Otherwise a retry that fails would leave the *previous* layer in place,
        // and the caller — which only checks the Result — would be handed a stale
        // rootfs by a later attempt that read the directory instead.
        val good = unwrapper.unwrap(ociImage(compress = ::xz), workDir, layerPath)
        assertTrue(good is OciLayerUnwrapper.Result.Layer)

        val broken = writeArchive(
            "second-attempt.tar.xz",
            tar(
                compress = ::xz,
                members = arrayOf(file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""))
            )
        )
        val result = unwrapper.unwrap(broken, workDir, layerPath)

        assertTrue("expected a Failure, got $result", result is OciLayerUnwrapper.Result.Failure)
        assertFalse("the previous run's layer must not survive a failed retry", target().exists())
    }

    @Test
    fun unwrappingTwiceIsIdempotent() {
        setUp()
        val image = ociImage(compress = ::xz)

        val first = unwrapper.unwrap(image, workDir, layerPath) as OciLayerUnwrapper.Result.Layer
        val firstBytes = first.file.readBytes()
        val second = unwrapper.unwrap(image, workDir, layerPath) as OciLayerUnwrapper.Result.Layer

        assertArrayEquals(firstBytes, second.file.readBytes())
        assertEquals(first.file.absolutePath, second.file.absolutePath)
    }

    // ── §1.148: the layer's OWN entries go through ArchiveValidator ───────

    @Test
    fun aLayerWhoseEntryEscapesTheRootfsIsRejected() {
        setUp()
        // The gap §1.148 closes. Before it, `unwrap` looked only at the OUTER
        // image's entry names (oci-layout, index.json, blobs/sha256/<hex>) and
        // copied the blob out unread, so a traversal path packed INSIDE the
        // layer reached disk with no JVM-side check at all — GNU tar's own
        // protections were the only thing standing between it and the staging
        // rootfs. The same path in a plain tarball is rejected by
        // StagedRootFsInstaller.scanEntries.
        val result = unwrapper.unwrap(
            imageWithLayer(hostileLayer("../../../../etc/passwd")),
            workDir,
            layerPath
        )

        val failure = result as? OciLayerUnwrapper.Result.Failure
            ?: fail("a traversal entry inside the layer must be rejected", result)
        assertTrue(
            "the reason must name the layer and the offending entry, got: ${failure.reason}",
            failure.reason.startsWith("Unsafe entry in the OCI image's rootfs layer") &&
                failure.reason.contains("../../../../etc/passwd")
        )
    }

    @Test
    fun aRejectedLayerIsDeletedRatherThanLeftHalfValidated() {
        setUp()
        // The caller only inspects the Result, so a rejected layer left on disk
        // is what a later attempt reading the directory would pick up — the same
        // reasoning as aFailedUnwrapDeletesTheLeftoverFromAnEarlierSuccess.
        val result = unwrapper.unwrap(
            imageWithLayer(hostileLayer("../../../../etc/passwd")),
            workDir,
            layerPath
        )

        assertTrue("expected a Failure, got $result", result is OciLayerUnwrapper.Result.Failure)
        assertFalse("a layer that failed validation must not be left for tar", target().exists())
        assertEquals(
            "nothing at all may survive in the import cache",
            emptyList<String>(),
            workDir.listFiles()!!.map { it.name }
        )
    }

    @Test
    fun aLayerWithAnAbsoluteEntryPathIsRejected() {
        setUp()
        // A second validator rule, to show the pass delegates rather than
        // pattern-matching for '..' itself: an absolute name is rejected by
        // checkEntry's own absolute-path branch.
        val result = unwrapper.unwrap(imageWithLayer(hostileLayer("/etc/shadow")), workDir, layerPath)

        val failure = result as? OciLayerUnwrapper.Result.Failure
            ?: fail("an absolute entry inside the layer must be rejected", result)
        assertTrue(
            "expected the validator's absolute-path reason, got: ${failure.reason}",
            failure.reason.contains("absolute path")
        )
    }

    @Test
    fun aLayerWithASymlinkEscapingTheRootfsIsRejected() {
        setUp()
        // Symlink escape is a distinct checkEntry rule and needs the entry's
        // link target, so this proves the pass passes isSymbolicLink/linkName
        // through rather than validating names alone.
        val hostile = tar(
            compress = ::gzip,
            members = arrayOf(
                dir("./etc/"),
                symlink("./etc/shadow", "../../../../etc/shadow")
            )
        )

        val result = unwrapper.unwrap(imageWithLayer(hostile), workDir, layerPath)

        val failure = result as? OciLayerUnwrapper.Result.Failure
            ?: fail("a symlink escaping the rootfs must be rejected", result)
        assertTrue(
            "expected the symlink-escape reason, got: ${failure.reason}",
            failure.reason.contains("Symlink escapes extraction rootfs boundary")
        )
        assertFalse(target().exists())
    }

    @Test
    fun aLayerWithLegitimateAbsoluteSymlinksIsStillAccepted() {
        setUp()
        // The validate pass must not be stricter than the plain-tarball path.
        // Absolute symlink targets anchor at the rootfs root and are normal
        // (Alpine's busybox links, /bin -> usr/bin) — rejecting them is exactly
        // the pre-§1.35 regression that broke Alpine and Debian imports.
        val realistic = tar(
            compress = ::gzip,
            members = arrayOf(
                dir("./usr/"),
                dir("./usr/bin/"),
                file("./usr/bin/busybox", "ELF"),
                symlink("./bin", "usr/bin"),
                symlink("./usr/bin/sh", "/usr/bin/busybox"),
                // §1.147: a systemd unit name with \xNN escapes — a single
                // segment holding two literal backslashes. This is the entry
                // that aborted real Fedora/Arch installs, and it must pass.
                file("./usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice", "[Slice]\n")
            )
        )

        val result = unwrapper.unwrap(imageWithLayer(realistic), workDir, layerPath)

        val layer = result as? OciLayerUnwrapper.Result.Layer
            ?: fail("a realistic rootfs layer must pass validation", result)
        assertArrayEquals(realistic, layer.file.readBytes())
    }

    @Test
    fun anUncompressedLayerBlobIsStillValidatedRatherThanRefused() {
        setUp()
        // `application/vnd.oci.image.layer.v1.tar` (no `+gzip`) is a legal media
        // type and GNU tar's -xf accepts a bare tar, so the validate pass must
        // read it rather than choke on the missing compression container — and
        // must still reject a traversal inside it.
        val bareHostile = tar(compress = { it }, members = arrayOf(Member("../../etc/passwd", "x".toByteArray())))

        val result = unwrapper.unwrap(imageWithLayer(bareHostile), workDir, layerPath)

        val failure = result as? OciLayerUnwrapper.Result.Failure
            ?: fail("an uncompressed hostile layer must still be rejected", result)
        assertTrue(
            "expected the layer-entry rejection, got: ${failure.reason}",
            failure.reason.startsWith("Unsafe entry in the OCI image's rootfs layer")
        )
    }

    @Test
    fun anUncompressedButSafeLayerBlobIsAccepted() {
        setUp()
        // The other half of the case above: falling back to reading the blob
        // uncompressed must not turn a valid image into a failure.
        val bareSafe = tar(
            compress = { it },
            members = arrayOf(dir("./etc/"), file("./etc/os-release", "ID=fedora\n"))
        )

        val result = unwrapper.unwrap(imageWithLayer(bareSafe), workDir, layerPath)

        val layer = result as? OciLayerUnwrapper.Result.Layer
            ?: fail("an uncompressed safe layer must be accepted", result)
        assertArrayEquals(bareSafe, layer.file.readBytes())
    }

    @Test
    fun aLayerThatIsNotATarAtAllIsReportedRatherThanPassedToTar() {
        setUp()
        // Validation now reads the blob, so a blob that is not an archive fails
        // here instead of surfacing later as a proot/tar exit code.
        val notATar = ByteArray(8192) { 0x41 }

        val result = unwrapper.unwrap(imageWithLayer(notATar), workDir, layerPath)

        assertTrue("expected a Failure, got $result", result is OciLayerUnwrapper.Result.Failure)
        assertFalse("nothing unvalidated may be left behind", target().exists())
    }

    // ── fixture plumbing ─────────────────────────────────────────────────

    private class Member(
        val name: String,
        val bytes: ByteArray? = null,
        val isDirectory: Boolean = false,
        val linkTarget: String? = null
    )

    private fun dir(name: String) = Member(name, isDirectory = true)

    private fun file(name: String, text: String) = Member(name, text.toByteArray())

    /** §1.148: a symlink entry, so [RealArchiveValidator]'s escape rule applies. */
    private fun symlink(name: String, target: String) = Member(name, linkTarget = target)

    /**
     * §1.148: a layer blob carrying one entry the validator must reject.
     * `..`-traversal and absolute names are the same shapes
     * `RealArchiveValidatorTest` uses for an unsafe entry.
     */
    private fun hostileLayer(entryName: String): ByteArray = tar(
        compress = ::gzip,
        members = arrayOf(
            dir("./etc/"),
            file("./etc/os-release", "ID=fedora\n"),
            Member(entryName, "root:x:0:0:".toByteArray())
        )
    )

    /** The verified Fedora layout, with [blob] substituted for the rootfs layer. */
    private fun imageWithLayer(blob: ByteArray): File = writeArchive(
        "image-custom-layer.tar.xz",
        tar(
            compress = ::xz,
            members = arrayOf(
                file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                file(OciImageLayout.INDEX_JSON, indexJson()),
                file(manifestPath, manifestJson()),
                Member(layerPath, blob),
                file(configPath, """{"architecture":"arm64","os":"linux"}""")
            )
        )
    )

    private fun indexJson() = """
        {"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json",
        "digest":"$manifestDigest","size":491}]}
    """.trimIndent()

    private fun manifestJson() = """
        {"schemaVersion":2,
         "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":2000},
         "layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip",
         "digest":"$layerDigest","size":${layerBlob.size}}]}
    """.trimIndent()

    /** The verified Fedora layout: marker, index, manifest blob, layer blob, config blob. */
    private fun ociImage(compress: (ByteArrayOutputStream) -> java.io.OutputStream): File = writeArchive(
        "image",
        tar(
            compress = compress,
            members = arrayOf(
                file(OciImageLayout.LAYOUT_MARKER, """{"imageLayoutVersion":"1.0.0"}"""),
                file(OciImageLayout.INDEX_JSON, indexJson()),
                dir("blobs/"),
                dir("blobs/sha256/"),
                file(manifestPath, manifestJson()),
                Member(layerPath, layerBlob),
                file(configPath, """{"architecture":"arm64","os":"linux"}""")
            )
        )
    )

    private fun gzip(out: ByteArrayOutputStream): java.io.OutputStream = GzipCompressorOutputStream(out)

    // XZ preset 1 (1 MiB dictionary) instead of the default preset 6 (8 MiB):
    // the LZMA2 encoder holds roughly 10x the dictionary in memory per stream,
    // and at the default setting this fixture was the suite-only
    // OutOfMemoryError when the whole JVM suite ran in one worker (10A triage
    // 2026-11-13). The bytes are still a genuine XZ container — the unwrapper
    // sniffs and decompresses it identically.
    private fun xz(out: ByteArrayOutputStream): java.io.OutputStream = XZCompressorOutputStream(out, 1)

    /** Builds real archive bytes, so the unwrapper sniffs and walks a genuine stream. */
    private fun tar(
        compress: (ByteArrayOutputStream) -> java.io.OutputStream,
        members: Array<Member>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        compress(out).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                members.forEach { member ->
                    val entry = when {
                        member.isDirectory -> TarArchiveEntry(member.name, TarArchiveEntry.LF_DIR)
                        member.linkTarget != null ->
                            TarArchiveEntry(member.name, TarArchiveEntry.LF_SYMLINK)
                                .apply { linkName = member.linkTarget }
                        // commons-compress strips a leading '/' from an entry
                        // name unless asked not to, which would silently turn an
                        // absolute-path fixture into a harmless relative one.
                        member.name.startsWith("/") -> TarArchiveEntry(member.name, true)
                        else -> TarArchiveEntry(member.name)
                    }
                    val payload = member.bytes ?: ByteArray(0)
                    entry.size = payload.size.toLong()
                    tar.putArchiveEntry(entry)
                    if (payload.isNotEmpty()) tar.write(payload)
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun writeArchive(name: String, bytes: ByteArray): File =
        File(tmp.newFolder(), name).apply { writeBytes(bytes) }

    private fun fail(message: String, result: OciLayerUnwrapper.Result): Nothing {
        val detail = (result as? OciLayerUnwrapper.Result.Failure)?.reason ?: result.toString()
        throw AssertionError("$message, but was: $detail")
    }

    private companion object {
        /** Fixed so the mid-layer truncation point is reproducible run to run. */
        const val SEED = 42L
    }
}
