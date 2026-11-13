package com.fem.linexa.ui.screens.packagemanager

import com.fem.linexa.domain.packagemanager.InstalledPkg
import com.fem.linexa.domain.packagemanager.PkgManagerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.93 — Regression tests for the infinite-Loading bug.
 *
 * The pre-§1.93 `updateInstalledState` guard only wrote a new
 * [InstalledState] when the current state was already
 * [InstalledState.Loaded]. Because the initial load starts at
 * [InstalledState.Loading] (set by `detectAndLoad`), every terminal fetch
 * result — success AND failure — was silently dropped, so the Installed tab
 * rendered its spinner forever and no error ever surfaced.
 *
 * These tests exercise the exact guard + reducer the ViewModel now uses and
 * prove [InstalledState.Loading] can never remain after a terminal repository
 * result, and that a Failed state can recover on a later retry.
 */
class PackageManagerViewModelStateTest {

    private val bash = InstalledPkg(
        name = "bash",
        version = "5.2.21-2",
        installedSizeBytes = 1234L * 1024L,
        repository = "shells",
        description = "GNU Bourne-Again SHell"
    )

    private fun ready(installed: InstalledState) = PackageManagerUiState.Ready(
        manager = PkgManagerInfo(name = "apt", version = "2.7.14"),
        installed = installed,
        store = StoreState.Idle(""),
        operation = null
    )

    /** [applyInstalledUpdate] returns the whole [PackageManagerUiState]; unwrap its Ready.installed. */
    private fun installedOf(updated: PackageManagerUiState): InstalledState =
        (updated as PackageManagerUiState.Ready).installed

    @Test
    fun firstLoadSuccessLeavesLoadingReplacedWithLoaded() {
        val updated = applyInstalledUpdate(ready(InstalledState.Loading)) { installedAfterFetch(Result.success(listOf(bash))) }
        assertEquals(InstalledState.Loaded(packages = listOf(bash), refreshing = false), installedOf(updated))
    }

    @Test
    fun firstLoadFailureLeavesLoadingReplacedWithFailed() {
        val updated = applyInstalledUpdate(ready(InstalledState.Loading)) { installedAfterFetch(Result.failure(RuntimeException("dpkg broken"))) }
        assertTrue(installedOf(updated) is InstalledState.Failed)
        assertTrue((installedOf(updated) as InstalledState.Failed).message.contains("dpkg broken"))
    }

    @Test
    fun emptyInstalledListIsLoadedNotLoading() {
        val updated = applyInstalledUpdate(ready(InstalledState.Loading)) { installedAfterFetch(Result.success(emptyList())) }
        assertEquals(InstalledState.Loaded(packages = emptyList(), refreshing = false), installedOf(updated))
    }

    @Test
    fun refreshSuccessClearsRefreshingFlag() {
        val loaded = InstalledState.Loaded(packages = listOf(bash), refreshing = true)
        val updated = applyInstalledUpdate(ready(loaded)) { installedAfterFetch(Result.success(listOf(bash))) }
        assertEquals(InstalledState.Loaded(packages = listOf(bash), refreshing = false), installedOf(updated))
    }

    @Test
    fun refreshFailureSurfacesFailedState() {
        val loaded = InstalledState.Loaded(packages = listOf(bash), refreshing = true)
        val updated = applyInstalledUpdate(ready(loaded)) { installedAfterFetch(Result.failure(RuntimeException("offline"))) }
        assertTrue(installedOf(updated) is InstalledState.Failed)
    }

    @Test
    fun recoveryAfterFailureCanReachLoaded() {
        val failed = InstalledState.Failed("Failed to load installed packages: offline")
        val updated = applyInstalledUpdate(ready(failed)) { installedAfterFetch(Result.success(listOf(bash))) }
        assertEquals(InstalledState.Loaded(packages = listOf(bash), refreshing = false), installedOf(updated))
    }

    @Test
    fun nonReadyStateIsUntouched() {
        val updated = applyInstalledUpdate(PackageManagerUiState.Detecting) { installedAfterFetch(Result.success(listOf(bash))) }
        assertEquals(PackageManagerUiState.Detecting, updated)
    }

    @Test
    fun refreshGateOnlySetsRefreshingOnLoaded() {
        // Mirrors the ViewModel's loadInstalled() body: the refreshing flag is
        // only applied to an already-loaded list, never the initial Loading state.
        val gate = { state: InstalledState -> if (state is InstalledState.Loaded) state.copy(refreshing = true) else state }
        val onLoading = installedOf(applyInstalledUpdate(ready(InstalledState.Loading), gate))
        assertEquals(InstalledState.Loading, onLoading)
        val onLoaded = installedOf(applyInstalledUpdate(ready(InstalledState.Loaded(listOf(bash), false)), gate))
        assertEquals(InstalledState.Loaded(listOf(bash), refreshing = true), onLoaded)
    }

    // ── §1.147 installed-tab ordering ──────────────────────────────────────

    private fun pkg(
        name: String,
        sizeBytes: Long? = null,
        updateVersion: String? = null
    ) = InstalledPkg(
        name = name,
        version = "1.0",
        installedSizeBytes = sizeBytes,
        repository = null,
        description = null,
        updateVersion = updateVersion
    )

    /** Alphabetical must not be case-sensitive, or `Xorg` sorts before `apt`. */
    @Test
    fun nameSortIsCaseInsensitive() {
        val sorted = sortInstalled(listOf(pkg("Xorg"), pkg("apt"), pkg("Bash")), InstalledSort.NAME)
        assertEquals(listOf("apt", "Bash", "Xorg"), sorted.map { it.name })
    }

    /**
     * The updates sort exists to surface the upgradable packages, so they lead
     * — but it stays a total order, alphabetical inside each group, rather than
     * leaving the two partitions in arbitrary input order.
     */
    @Test
    fun updatesSortFloatsUpgradablePackagesAlphabeticallyWithinGroups() {
        val sorted = sortInstalled(
            listOf(
                pkg("zlib"),
                pkg("curl", updateVersion = "8.6.0"),
                pkg("apt"),
                pkg("bash", updateVersion = "5.2.21")
            ),
            InstalledSort.UPDATES
        )
        assertEquals(listOf("bash", "curl", "apt", "zlib"), sorted.map { it.name })
    }

    /**
     * Largest first, and a package whose manager reported no size sinks to the
     * bottom: an unknown size is not a size of zero, so it must not outrank a
     * package that really is 0 bytes.
     */
    @Test
    fun sizeSortIsDescendingAndSinksUnknownSizes() {
        val sorted = sortInstalled(
            listOf(
                pkg("unknown", sizeBytes = null),
                pkg("small", sizeBytes = 1_024L),
                pkg("empty", sizeBytes = 0L),
                pkg("large", sizeBytes = 9_000_000L)
            ),
            InstalledSort.SIZE
        )
        assertEquals(listOf("large", "small", "empty", "unknown"), sorted.map { it.name })
    }

    /** Sorting is pure: the caller's list is never reordered in place. */
    @Test
    fun sortDoesNotMutateTheInputList() {
        val input = listOf(pkg("zlib"), pkg("apt"))
        sortInstalled(input, InstalledSort.NAME)
        assertEquals(listOf("zlib", "apt"), input.map { it.name })
    }

    /**
     * §1.147 — the fetch reducer applies the ordering itself, so the state can
     * never hold a list in a different order than the one the UI renders.
     */
    @Test
    fun installedAfterFetchAppliesTheRequestedSort() {
        val state = installedAfterFetch(
            Result.success(listOf(pkg("zlib", sizeBytes = 1L), pkg("apt", sizeBytes = 99L))),
            InstalledSort.SIZE
        )
        assertEquals(listOf("apt", "zlib"), (state as InstalledState.Loaded).packages.map { it.name })
    }

    /** The default is alphabetical, matching the initially selected chip. */
    @Test
    fun installedAfterFetchDefaultsToNameOrder() {
        val state = installedAfterFetch(Result.success(listOf(pkg("zlib"), pkg("apt"))))
        assertEquals(listOf("apt", "zlib"), (state as InstalledState.Loaded).packages.map { it.name })
    }

    // ── Issue #1 (2026-09-03): load coalescing ─────────────────────────────

    /**
     * A refresh that arrives while a load is in flight must be absorbed by
     * the running fetch — cancelling it is what produced the
     * "Failed to load installed packages: StandaloneCoroutine was cancelled"
     * flash on every screen open (the nav lifecycle's ON_RESUME fires right
     * after the first composition, i.e. mid-fetch).
     */
    @Test
    fun coalescingAbsorbsTheResumeRefreshWhileALoadIsInFlight() {
        assertTrue(shouldCoalesceInstalledLoad(loadInFlight = true))
    }

    @Test
    fun coalescingStartsALoadWhenNothingIsRunning() {
        assertFalse(shouldCoalesceInstalledLoad(loadInFlight = false))
    }
}
