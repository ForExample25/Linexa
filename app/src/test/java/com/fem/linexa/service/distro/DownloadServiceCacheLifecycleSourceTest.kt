package com.fem.linexa.service.distro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cache-lifecycle contract for the download service.
 *
 * A previous gap: no DownloadService test
 * harness exists in this codebase, so the
 * poisoned-cache deletion (checksum mismatch → cache deleted → Retry from
 * zero) was covered only by code review. DownloadService is an Android
 * `Service` with eight `@Inject lateinit var` fields, so it cannot be
 * constructed in a pure JVM test on this toolchain; the contract is therefore
 * pinned as a SOURCE property, mirroring
 * [com.fem.linexa.service.runtime.RuntimeServiceTeardownTest]:
 *
 * 1. an ANALYZE failure (checksum mismatch / archive-shape failure) must
 *    delete the poisoned download cache, then mark the workspace `Broken`
 *    with that reason, then emit `DownloadEvent.Failed` — in that order;
 * 2. a DOWNLOAD failure must NOT touch the cache: the partial stays so the
 *    `Range`/`If-Range` resume can continue
 *    ([HttpDistroDownloaderResumeTest][com.fem.linexa.service.distro.HttpDistroDownloaderResumeTest]
 *    pins the protocol side of that contract);
 * 3. the cache is deleted ONLY on success — DownloadService's install-success
 *    branch and InstallCoordinator's verified-success branch, and nowhere else.
 */
class DownloadServiceCacheLifecycleSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/service/distro/DownloadService.kt"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun sourceOf(relative: String): String =
        File(moduleRoot, "src/main/java/$relative").readText()

    private val serviceSource: String
        get() = sourceOf("com/fem/linexa/service/distro/DownloadService.kt")

    private val coordinatorSource: String
        get() = sourceOf("com/fem/linexa/service/installer/InstallCoordinator.kt")

    /** Lines of [text] with full-line `//` comments removed. */
    private fun code(text: String): String = text
        .lineSequence()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    /**
     * The `AnalyzeResult.Failure` branch body, comments removed. The branch
     * carries the Phase 06 policy rationale as a comment block; the
     * assertions must judge the code, not the prose.
     */
    private val analyzeFailureBranch: String
        get() = code(
            serviceSource
                .substringAfter("when (analysis) {")
                .substringAfter("is AnalyzeResult.Failure -> {")
                .substringBefore("is AnalyzeResult.Success -> {")
        )

    /** The `DownloadResult.Failure` branch body, comments removed. */
    private val downloadFailureBranch: String
        get() = code(
            serviceSource
                .substringAfter("when (downloadResult) {")
                .substringAfter("is DownloadResult.Failure -> {")
                .substringBefore("is DownloadResult.Success -> {")
        )

    private fun countOccurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    @Test
    fun analyzeFailureDeletesThePoisonedCacheBeforeMarkingBrokenAndEmittingFailed() {
        val delete = "fileManager.deleteDownloadCache(workspaceId)"
        val broken = "WorkspaceStatus.Broken(analysis.reason)"
        val failed = "DownloadEvent.Failed(workspaceId, analysis.reason)"
        assertTrue(
            "the analyze-failure branch must delete the download cache (Phase 06 OPT-REL-004: a " +
                "checksum-mismatched or invalid partial can never succeed against the SAME bytes " +
                "on Retry, so Retry must start from zero)\n$analyzeFailureBranch",
            analyzeFailureBranch.contains(delete)
        )
        val deleteIndex = analyzeFailureBranch.indexOf(delete)
        val brokenIndex = analyzeFailureBranch.indexOf(broken)
        val failedIndex = analyzeFailureBranch.indexOf(failed)
        assertTrue(
            "the workspace must be marked Broken with the analyze reason\n$analyzeFailureBranch",
            brokenIndex >= 0
        )
        assertTrue(
            "the Failed event must carry the analyze reason\n$analyzeFailureBranch",
            failedIndex >= 0
        )
        assertTrue(
            "the order must be delete -> Broken -> Failed: a Broken record with the cache still " +
                "on disk would let Retry re-analyze the same poisoned partial\n$analyzeFailureBranch",
            deleteIndex < brokenIndex && brokenIndex < failedIndex
        )
    }

    @Test
    fun downloadFailureKeepsThePartialForTheRangeResume() {
        assertTrue(
            "the download-failure branch must mark the workspace Broken with the download reason",
            downloadFailureBranch.contains("WorkspaceStatus.Broken(downloadResult.reason)")
        )
        assertTrue(
            "the download-failure branch must NOT delete the download cache — the partial is the " +
                "resume input (transient network failures keep it; HttpDistroDownloaderResumeTest " +
                "pins the Range/If-Range protocol that consumes it)\n$downloadFailureBranch",
            !downloadFailureBranch.contains("deleteDownloadCache")
        )
    }

    @Test
    fun theDownloadCacheIsDeletedOnlyOnTheInstallSuccessPath() {
        assertEquals(
            "DownloadService must contain exactly two cache-delete call sites: the " +
                "analyze-failure cleanup and the install-success cleanup. Any THIRD site — in " +
                "particular one on a failure path — must be classified before it lands.",
            2,
            countOccurrences(serviceSource, "fileManager.deleteDownloadCache")
        )
        val installSuccessBranch = serviceSource
            .substringAfter("when (installResult) {")
            .substringAfter("is InstallResult.Success -> {")
        assertTrue(
            "the install-success branch must delete the download cache (the verified artifact " +
                "no longer needs it — the policy's other half)",
            installSuccessBranch.contains("fileManager.deleteDownloadCache(workspaceId)")
        )
    }

    @Test
    fun installCoordinatorDeletesTheCacheOnlyAfterVerifiedSuccess() {
        assertEquals(
            "InstallCoordinator must contain exactly one cache-delete call site",
            1,
            countOccurrences(coordinatorSource, "fileManager.deleteDownloadCache")
        )
        val verifiedIndex = coordinatorSource.indexOf("updateWorkspaceAfterSuccess(p, result.sha256)")
        val deleteIndex = coordinatorSource.indexOf("fileManager.deleteDownloadCache(p.workspaceId)")
        val completedIndex = coordinatorSource.indexOf("InstallFlowState.Completed(p.workspaceId)")
        assertTrue(
            "the delete must sit on the verified-success path: after the metadata persistence " +
                "check succeeds and before the Completed state",
            verifiedIndex >= 0 && verifiedIndex < deleteIndex && deleteIndex < completedIndex
        )
        val failureBranch = code(
            coordinatorSource
                .substringAfter("is InstallResult.Failure -> {")
                .substringBefore("catch (e: CancellationException)")
        )
        assertTrue(
            "the install-failure branch must not delete the download cache — nothing is poisoned " +
                "there (extraction never started, the artifact still passes its checksum)",
            !failureBranch.contains("deleteDownloadCache")
        )
    }
}
