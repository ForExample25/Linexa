package com.fem.linexa.service.exporter

import com.fem.linexa.core.security.ArchiveValidator
import com.fem.linexa.core.security.RealArchiveValidator
import com.fem.linexa.service.rootfs.CompressedArchiveStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * § export — the backup writer's behavioural suite.
 *
 * Everything here runs against the real [TarRootFsExporter] with the real
 * [RealArchiveValidator], packing a real directory tree into memory and
 * reading the result back through the *import* pipeline's own entry points
 * ([CompressedArchiveStream.open], [TarArchiveInputStream]) — so every
 * assertion doubles as a check that the export is re-consumable by the
 * system that installs RootFS archives.
 */
class TarRootFsExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var exporter: TarRootFsExporter
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        exporter = TarRootFsExporter(RealArchiveValidator())
        rootfs = tmp.newFolder("rootfs")
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** A realistic small tree: nested dirs, files of several sizes, modes, links. */
    private fun populateTypicalTree() {
        write("etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 12\"\n", "rw-r--r--")
        write("etc/hostname", "linexa\n", "rw-r--r--")
        write("usr/bin/tool", "#!/bin/sh\necho hi\n", "rwxr-xr-x")
        write("var/lib/misc/data.bin", "x".repeat(200 * 1024), "rw-r-----") // > 64KiB copy buffer
        write("top.txt", "root level file\n", "rw-------")
        symlink("bin", "usr/bin") // usr-merge style relative link
        symlink("etc/mtab", "/proc/mounts") // absolute target, allowed by the validator
        Files.createDirectories(Path.of(rootfs.absolutePath, "usr", "lib"))
    }

    private fun write(relative: String, content: String, mode: String): File {
        val file = File(rootfs, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
        return file
    }

    private fun symlink(relative: String, target: String) {
        val link = File(rootfs, relative)
        link.parentFile.mkdirs()
        Files.createSymbolicLink(link.toPath(), Path.of(target))
    }

    private fun export(
        rootfsDir: File = rootfs,
        embeddedConfig: ByteArray? = null,
        expectedBytes: Long? = null,
        verifyInput: (() -> InputStream?)? = null,
        credentialPolicy: ExportCredentialPolicy = ExportCredentialPolicy.REMOVE_CREDENTIALS,
        onProgress: suspend (ExportProgress) -> Unit = {},
    ): Pair<ExportResult, ByteArray> {
        val output = ByteArrayOutputStream()
        val result = runBlocking {
            exporter.export(
                ExportRequest(
                    rootfs = rootfsDir,
                    output = output,
                    verifyInput = verifyInput,
                    embeddedConfig = embeddedConfig,
                    expectedBytes = expectedBytes,
                    credentialPolicy = credentialPolicy,
                ),
                onProgress
            )
        }
        return result to output.toByteArray()
    }

    /** Reads the produced archive back with the importer's own stream classes. */
    private fun readBack(bytes: ByteArray): List<TarArchiveEntry> {
        val entries = mutableListOf<TarArchiveEntry>()
        val opened = CompressedArchiveStream.open(ByteArrayInputStream(bytes))
        TarArchiveInputStream(opened.stream).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                // Drain each entry so the next header is parsed, exactly like
                // the installer's scan does.
                while (tar.read(ByteArray(8192)) >= 0) { /* drain */ }
                entries.add(entry)
                entry = tar.nextTarEntry
            }
        }
        return entries
    }

    private fun entryContent(bytes: ByteArray, name: String): ByteArray {
        val opened = CompressedArchiveStream.open(ByteArrayInputStream(bytes))
        TarArchiveInputStream(opened.stream).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                if (entry.name.trimEnd('/') == name.trimEnd('/')) {
                    return tar.readBytes()
                }
                while (tar.read(ByteArray(8192)) >= 0) { /* drain and move on */ }
                entry = tar.nextTarEntry
            }
        }
        fail("entry not found in archive: $name")
        throw IllegalStateException()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── round-trip: structure, content, links, modes ────────────────────────

    @Test
    fun aTypicalTreeExportsSuccessfullyAndVerifies() {
        populateTypicalTree()
        val (result, bytes) = export()

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        result as ExportResult.Success
        assertTrue("exported archive should not be empty", bytes.isNotEmpty())
        assertEquals("sha256 must describe the written bytes", sha256(bytes), result.sha256)
        assertEquals("outputBytes must equal the written size", bytes.size.toLong(), result.outputBytes)
        assertTrue("no entries should be skipped: ${result.skippedEntries}", result.skippedEntries.isEmpty())
    }

    @Test
    fun everyEntryNameAndSymlinkTargetPassesTheReImportValidator() {
        populateTypicalTree()
        val (result, bytes) = export()
        assertTrue(result is ExportResult.Success)

        val validator = RealArchiveValidator()
        for (entry in readBack(bytes)) {
            val verdict = validator.checkEntry(entry.name, entry.isSymbolicLink, entry.linkName)
            assertFalse(
                "entry ${entry.name} must not be rejected on re-import: $verdict",
                verdict is ArchiveValidator.ValidationResult.Invalid
            )
        }
    }

    @Test
    fun fileAndDirectoryEntriesCarryNamesSizesAndContentUnchanged() {
        populateTypicalTree()
        val (result, bytes) = export()
        assertTrue(result is ExportResult.Success)

        val entries = readBack(bytes)
        val names = entries.map { it.name }
        assertTrue("directory entry with trailing slash expected", "etc/" in names)
        assertTrue("usr/bin/tool missing: $names", "usr/bin/tool" in names)

        assertEquals(
            "etc/os-release content must survive the round trip",
            "PRETTY_NAME=\"Debian GNU/Linux 12\"\n",
            String(entryContent(bytes, "etc/os-release"))
        )
        // A file larger than the 64 KiB copy buffer proves chunked copying.
        val big = entryContent(bytes, "var/lib/misc/data.bin")
        assertEquals(200 * 1024, big.size)
        assertTrue(big.all { it == 'x'.code.toByte() })
    }

    @Test
    fun symlinksArePackedAsSymlinksAndNeverFollowed() {
        populateTypicalTree()
        val (result, bytes) = export()
        assertTrue(result is ExportResult.Success)

        val entries = readBack(bytes)
        val bin = entries.first { it.name == "bin" }
        assertTrue("bin must be a symlink entry", bin.isSymbolicLink)
        assertEquals("usr/bin", bin.linkName)
        assertEquals(
            "usr-merge link must stay one link, not a duplicated subtree",
            1,
            entries.count { it.name == "bin" }
        )
        val mtab = entries.first { it.name == "etc/mtab" }
        assertTrue(mtab.isSymbolicLink)
        assertEquals("/proc/mounts", mtab.linkName)
    }

    @Test
    fun permissionBitsSurviveTheRoundTrip() {
        populateTypicalTree()
        val (result, bytes) = export()
        assertTrue(result is ExportResult.Success)

        val entries = readBack(bytes)
        assertEquals(0b111_101_101, entries.first { it.name == "usr/bin/tool" }.mode)
        assertEquals(0b110_100_100, entries.first { it.name == "etc/os-release" }.mode)
        assertEquals(0b110_100_000, entries.first { it.name == "var/lib/misc/data.bin" }.mode)
        assertEquals(0b110_000_000, entries.first { it.name == "top.txt" }.mode)
    }

    @Test
    fun modificationTimesArePreserved() {
        val file = write("etc/stamp", "when?\n", "rw-r--r--")
        val expectedSeconds = Files.getLastModifiedTime(file.toPath()).toMillis() / 1000L
        val (result, bytes) = export()
        assertTrue(result is ExportResult.Success)
        val entry = readBack(bytes).first { it.name == "etc/stamp" }
        assertEquals("mtime must be carried on the entry (1s tar granularity)", expectedSeconds, entry.modTime.time / 1000L)
    }

    @Test
    fun theArchiveIsSniffedAsGzipByTheImportPipeline() {
        populateTypicalTree()
        val (result, bytes) = export()
        assertTrue(result is ExportResult.Success)
        // gzip magic 0x1F 0x8B
        assertEquals(0x1F, bytes[0].toInt() and 0xFF)
        assertEquals(0x8B, bytes[1].toInt() and 0xFF)
        val opened = CompressedArchiveStream.open(ByteArrayInputStream(bytes))
        assertEquals(com.fem.linexa.service.rootfs.RootFsArchiveFormat.TAR_GZ, opened.format)
    }

    // ── embedded declaration ────────────────────────────────────────────────

    @Test
    fun anEmbeddedConfigIsPackedAsRootLevelLinexaYamlWhenTheTreeHasNone() {
        populateTypicalTree() // no .linexa.yaml in the tree
        val config = "linexa:\n  schema_version: 1\n".toByteArray()
        val (result, bytes) = export(embeddedConfig = config)
        assertTrue(result is ExportResult.Success)

        val entries = readBack(bytes)
        val embedded = entries.firstOrNull { it.name == ".linexa.yaml" }
        assertNotNull("embedded declaration missing from archive", embedded)
        assertEquals(config.size.toLong(), embedded!!.size)
        assertTrue(config.contentEquals(entryContent(bytes, ".linexa.yaml")))
    }

    @Test
    fun noDuplicateDeclarationIsEmbeddedWhenTheTreeAlreadyCarriesOne() {
        write(".linexa.yaml", "linexa:\n  schema_version: 1\n", "rw-r--r--")
        write("etc/os-release", "x=1\n", "rw-r--r--")
        val config = "linexa:\n  schema_version: 1\n".toByteArray()
        val (result, bytes) = export(embeddedConfig = config)
        assertTrue(result is ExportResult.Success)

        val declarations = readBack(bytes).filter { it.name == ".linexa.yaml" }
        assertEquals("the in-tree declaration must be packed exactly once", 1, declarations.size)
    }

    @Test
    fun noConfigIsEmbeddedWhenNoneWasSupplied() {
        populateTypicalTree()
        val (result, bytes) = export(embeddedConfig = null)
        assertTrue(result is ExportResult.Success)
        assertTrue(readBack(bytes).none { it.name == ".linexa.yaml" })
    }

    // ── verification pass ───────────────────────────────────────────────────

    @Test
    fun aTruncatedStoredArchiveFailsVerification() {
        populateTypicalTree()
        // Pack once for real, then pretend the provider stored only half.
        val full = ByteArrayOutputStream()
        val real = runBlocking {
            exporter.export(ExportRequest(rootfs = rootfs, output = full))
        }
        assertTrue(real is ExportResult.Success)
        val truncated = full.toByteArray().copyOfRange(0, full.size() / 2)

        val (result, _) = export(verifyInput = { ByteArrayInputStream(truncated) })
        assertTrue("truncated archive must fail: $result", result is ExportResult.Failure)
        val reason = (result as ExportResult.Failure).reason
        assertTrue(
            "failure must name the truncation (checksum mismatch or unreadable): $reason",
            reason.contains("checksum", ignoreCase = true) || reason.contains("re-read", ignoreCase = true)
        )
    }

    @Test
    fun anArchiveThatCannotBeReOpenedForVerificationFails() {
        populateTypicalTree()
        val (result, _) = export(verifyInput = { null })
        assertTrue(result is ExportResult.Failure)
        assertTrue((result as ExportResult.Failure).reason.contains("re-open"))
    }

    @Test
    fun aVerifyOpenerThatThrowsReadsAsFailureNotACrash() {
        populateTypicalTree()
        val (result, _) = export(verifyInput = { throw IOException("provider exploded") })
        assertTrue(result is ExportResult.Failure)
    }

    @Test
    fun aHealthyVerifyPassCompletesTheExport() {
        populateTypicalTree()
        // Two passes against in-memory bytes: the first run proves Success with
        // a verify opener that re-reads what was written; the second confirms
        // the digest recorded in the result matches the stored bytes.
        val output = ByteArrayOutputStream()
        val result = runBlocking {
            exporter.export(
                ExportRequest(
                    rootfs = rootfs,
                    output = output,
                    verifyInput = { ByteArrayInputStream(output.toByteArray()) },
                )
            )
        }
        assertTrue(result is ExportResult.Success)
        result as ExportResult.Success
        assertEquals(sha256(output.toByteArray()), result.sha256)
    }

    // ── failure paths ───────────────────────────────────────────────────────

    @Test
    fun aDestinationWriteFailureIsAFailureNamingTheIoError() {
        write("etc/one", "1\n", "rw-r--r--")
        write("etc/two", "2\n", "rw-r--r--")
        val failing = object : OutputStream() {
            private var written = 0
            override fun write(b: Int) {
                if (++written > 128) throw IOException("simulated ENOSPC")
            }
        }
        val result = runBlocking {
            exporter.export(ExportRequest(rootfs = rootfs, output = failing, verifyInput = null))
        }
        assertTrue("expected Failure, got $result", result is ExportResult.Failure)
        assertTrue(
            (result as ExportResult.Failure).reason.contains("I/O", ignoreCase = true)
        )
    }

    @Test
    fun anUnreadableFileIsSkippedAndReportedAndTheArchiveStaysClean() {
        Assume.assumeFalse("permission tests need a non-root uid", isRoot())
        populateTypicalTree()
        val secret = write("etc/secret", "top secret\n", "rw-r--r--")
        Files.setPosixFilePermissions(secret.toPath(), PosixFilePermissions.fromString("---------"))
        try {
            val output = ByteArrayOutputStream()
            val result = runBlocking {
                exporter.export(
                    ExportRequest(
                        rootfs = rootfs,
                        output = output,
                        // Verification ON, against the in-memory bytes — the
                        // exact shape the coordinator runs. This is the
                        // regression: a skipped file used to be counted as
                        // packed, so verification saw fewer entries than were
                        // "packed" and failed an otherwise perfect archive
                        // (the field report: 7332 stored vs 7335 packed).
                        verifyInput = { ByteArrayInputStream(output.toByteArray()) },
                    )
                )
            }
            assertTrue("export should succeed minus the unreadable file: $result", result is ExportResult.Success)
            result as ExportResult.Success
            assertTrue(
                "the skipped file must be reported: ${result.skippedEntries}",
                result.skippedEntries.any { it.startsWith("etc/secret") }
            )
            val physical = readBack(output.toByteArray())
            assertTrue(
                "the skipped file must not be in the archive",
                physical.none { it.name == "etc/secret" }
            )
            assertEquals(
                "the packed count must equal the entries physically in the archive — " +
                    "otherwise verification would (wrongly) fail the export",
                physical.size.toLong(),
                result.entryCount
            )
        } finally {
            // Restore so TemporaryFolder cleanup can delete the tree.
            Files.setPosixFilePermissions(secret.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun severalUnreadableFilesStillVerifyBecauseOnlyWrittenEntriesAreCounted() {
        // Three unreadable files — the exact delta from the field report
        // (7335 counted vs 7332 stored). With the count fixed, the export
        // verifies and succeeds, reporting all three as skipped.
        Assume.assumeFalse("permission tests need a non-root uid", isRoot())
        populateTypicalTree()
        val locked = listOf(
            write("var/tmp/lock-a", "a\n", "rw-r--r--"),
            write("var/tmp/lock-b", "b\n", "rw-r--r--"),
            write("etc/private/lock-c", "c\n", "rw-r--r--")
        )
        locked.forEach {
            Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("---------"))
        }
        try {
            val output = ByteArrayOutputStream()
            val result = runBlocking {
                exporter.export(
                    ExportRequest(
                        rootfs = rootfs,
                        output = output,
                        verifyInput = { ByteArrayInputStream(output.toByteArray()) },
                    )
                )
            }
            assertTrue("expected Success, got $result", result is ExportResult.Success)
            result as ExportResult.Success
            assertEquals(3, result.skippedEntries.size)
            val physical = readBack(output.toByteArray())
            assertEquals(physical.size.toLong(), result.entryCount)
            assertTrue(physical.none { it.name in locked.map { f -> f.relativeTo(rootfs).path } })
        } finally {
            locked.forEach {
                Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
            }
        }
    }

    @Test
    fun aMissingRootfsIsRefusedWithAReadableReason() {
        val (result, _) = export(rootfsDir = File(tmp.root, "does-not-exist"))
        assertTrue(result is ExportResult.Failure)
        assertTrue((result as ExportResult.Failure).reason.contains("cannot be read"))
    }

    // ── cancellation ────────────────────────────────────────────────────────

    @Test
    fun cancellingMidPackStopsTheExportAndPropagatesCancellationException() {
        // 200 × 64 KiB files = 12.8 MiB: the first PACKING progress emission
        // (512 KiB cadence) arrives early, and cancel() from inside onProgress
        // must stop the walk at the next ensureActive() checkpoint.
        repeat(200) { i ->
            write("bulk/f$i.bin", "y".repeat(64 * 1024), "rw-r--r--")
        }

        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        var caught: Throwable? = null
        runBlocking {
            scope.launch {
                try {
                    exporter.export(
                        ExportRequest(rootfs = rootfs, output = ByteArrayOutputStream(), verifyInput = null)
                    ) { progress ->
                        if (progress.phase == ExportPhase.PACKING) {
                            job.cancel()
                        }
                    }
                } catch (t: Throwable) {
                    caught = t
                }
            }.join()
        }

        assertNotNull("the export coroutine must finish", caught)
        assertTrue(
            "cancellation must surface as CancellationException, got $caught",
            caught is CancellationException
        )
    }

    // ── progress reporting ──────────────────────────────────────────────────

    @Test
    fun progressWalksPreparingPackingFinalizingVerifying() {
        populateTypicalTree()
        val events = mutableListOf<ExportProgress>()
        val output = ByteArrayOutputStream()
        val result = runBlocking {
            exporter.export(
                ExportRequest(
                    rootfs = rootfs,
                    output = output,
                    verifyInput = { ByteArrayInputStream(output.toByteArray()) },
                    expectedBytes = 1L,
                )
            ) { progress ->
                events.add(progress)
            }
        }
        assertTrue(result is ExportResult.Success)

        assertEquals("first event must be PREPARING", ExportPhase.PREPARING, events.first().phase)
        val phases = events.map { it.phase }
        assertTrue("PACKING events expected", phases.contains(ExportPhase.PACKING))
        assertTrue(
            "FINALIZING must follow PACKING",
            phases.indexOf(ExportPhase.FINALIZING) > phases.indexOf(ExportPhase.PACKING)
        )
        assertEquals("the last event must be VERIFYING", ExportPhase.VERIFYING, events.last().phase)
        val verifying = events.last { it.phase == ExportPhase.VERIFYING }
        assertEquals(
            "verify progress must report the archive size as denominator",
            output.size().toLong(),
            verifying.totalBytes
        )
    }

    @Test
    fun packingProgressNamesTheCurrentEntry() {
        populateTypicalTree()
        var sawEntryName = false
        val (result, _) = export { progress ->
            if (progress.phase == ExportPhase.PACKING && progress.currentEntryName != null) {
                sawEntryName = true
            }
        }
        assertTrue(result is ExportResult.Success)
        assertTrue("PACKING events should carry the entry being written", sawEntryName)
    }

    // ── determinism ─────────────────────────────────────────────────────────

    @Test
    fun theSameTreePacksToByteIdenticalArchives() {
        populateTypicalTree()
        val (first, bytesA) = export()
        val (second, bytesB) = export()
        assertTrue(first is ExportResult.Success)
        assertTrue(second is ExportResult.Success)
        assertTrue(
            "packing must be deterministic for an unchanged tree",
            bytesA.contentEquals(bytesB)
        )
    }

    @Test
    fun anEmptyRootfsExportsAValidEmptyArchive() {
        val (result, bytes) = export()
        assertTrue(result is ExportResult.Success)
        result as ExportResult.Success
        assertEquals(0L, result.entryCount)
        assertTrue("even an empty archive has a gzip container", bytes.size > 20)
        assertTrue(readBack(bytes).isEmpty())
    }

    // ── credential policy (finding 022) ─────────────────────────────────────

    /** A guest tree carrying every credential shape and its near-misses. */
    private fun populateCredentialTree() {
        write("home/linexa/.vnc/linexa-vnc-passwd", "vnc-secret\n", "rw-------")
        write("home/linexa/.vnc/config", "geometry=1280x720\n", "rw-r--r--")
        write("home/linexa/.vnc/notes.txt", "keep\n", "rw-r--r--")
        write("home/linexa/.ssh/id_ed25519", "PRIVATE KEY\n", "rw-------")
        write("home/linexa/.ssh/id_ed25519.pub", "ssh-ed25519 AAAA\n", "rw-r--r--")
        write("home/linexa/.ssh/known_hosts", "host key\n", "rw-r--r--")
        write("home/linexa/.gnupg/pubring.kbx", "keyring\n", "rw-r--r--")
        write("home/linexa/.gnupg/private-keys-v1.d/key.key", "private\n", "rw-------")
        write("home/linexa/notes/vnc-passwd.txt", "not a credential\n", "rw-r--r--")
    }

    @Test
    fun removeCredentialsPolicyOmitsVncSshPrivateKeysAndGnupg() {
        populateCredentialTree()
        val (result, bytes) = export(credentialPolicy = ExportCredentialPolicy.REMOVE_CREDENTIALS)

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        result as ExportResult.Success
        val names = readBack(bytes).map { it.name }.toSet()

        assertFalse(
            "the VNC password must not be in the archive",
            "home/linexa/.vnc/linexa-vnc-passwd" in names
        )
        assertFalse(
            "the SSH private key must not be in the archive",
            "home/linexa/.ssh/id_ed25519" in names
        )
        assertFalse(
            "the GnuPG home directory must not be in the archive",
            "home/linexa/.gnupg/" in names
        )
        assertFalse(
            "no descendant of the GnuPG home may be in the archive",
            names.any { it.startsWith("home/linexa/.gnupg/") }
        )

        // Near-misses survive: remove mode is not a blanket name filter.
        assertTrue("home/linexa/.vnc/config" in names)
        assertTrue("home/linexa/.vnc/notes.txt" in names)
        assertTrue("the SSH public key is not a secret", "home/linexa/.ssh/id_ed25519.pub" in names)
        assertTrue("home/linexa/.ssh/known_hosts" in names)
        assertTrue("a file whose name merely contains passwd stays", "home/linexa/notes/vnc-passwd.txt" in names)

        assertEquals(
            setOf(
                "home/linexa/.vnc/linexa-vnc-passwd",
                "home/linexa/.ssh/id_ed25519",
                "home/linexa/.gnupg",
            ),
            result.removedCredentials.toSet()
        )
        assertTrue(
            "intentional omissions are not failures: ${result.skippedEntries}",
            result.skippedEntries.isEmpty()
        )
    }

    @Test
    fun includeCredentialsPolicyKeepsEveryCredentialFile() {
        populateCredentialTree()
        val (result, bytes) = export(credentialPolicy = ExportCredentialPolicy.INCLUDE_CREDENTIALS)

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        result as ExportResult.Success
        val names = readBack(bytes).map { it.name }.toSet()

        assertTrue("home/linexa/.vnc/linexa-vnc-passwd" in names)
        assertTrue("home/linexa/.ssh/id_ed25519" in names)
        assertTrue("home/linexa/.ssh/id_ed25519.pub" in names)
        assertTrue("home/linexa/.gnupg/" in names)
        assertTrue("home/linexa/.gnupg/private-keys-v1.d/key.key" in names)
        assertTrue(
            "as-is mode removes nothing",
            result.removedCredentials.isEmpty()
        )
    }

    @Test
    fun anExportRequestWithNoPolicyRemovesCredentialsByDefault() {
        // The UI always decides explicitly, but a future non-UI caller that
        // forgets must still fail safe rather than leak guest secrets.
        populateCredentialTree()
        val output = ByteArrayOutputStream()
        val result = runBlocking {
            exporter.export(ExportRequest(rootfs = rootfs, output = output))
        }

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        val names = readBack(output.toByteArray()).map { it.name }.toSet()
        assertFalse("home/linexa/.vnc/linexa-vnc-passwd" in names)
        assertFalse("home/linexa/.ssh/id_ed25519" in names)
        assertFalse(names.any { it.startsWith("home/linexa/.gnupg/") })
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun isRoot(): Boolean =
        System.getProperty("user.name") == "root" || try {
            ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim() == "0"
        } catch (e: Exception) {
            false
        }
}
