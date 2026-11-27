package com.fem.linexa.service.runtime.container

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * F-8 (2026-08-25 audit) regression tests for the host/guest environment
 * separation.
 *
 * The audit confirmed host `LD_LIBRARY_PATH` (plus `PROOT_TMP_DIR`,
 * `PROOT_LOADER`, `TERMINFO`) leaking into every guest process, because PRoot
 * passes its own environment to the tracee verbatim and the pre-patch build
 * had no env-scrubbing mechanism. The fix has two halves that must stay in
 * lockstep:
 *
 *  1. the vendored PRoot's `PROOT_GUEST_ENV_FILTER` scrub
 *     (third_party/proot/0004 — value-matched, so guest-authored values survive;
 *     verified end-to-end with the real binary on x86_64: the guest `env`
 *     shows exactly the unfiltered keys),
 *  2. [PRootLauncher] setting that variable on every guest launch — and ONLY
 *     there: the rootfs extractor deliberately keeps the host env for its
 *     bionic `tar` tracee (pinned by the extractor's own env assertions in
 *     the harness tests).
 *
 * These JVM tests pin the Kotlin half of the contract plus the lockstep
 * between the filter list and [BootstrapManager.HOST_ENV_KEYS].
 */
class PRootLauncherGuestEnvFilterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var launcher: PRootLauncher

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        Mockito.`when`(app.getExternalFilesDir(any())).thenReturn(File(filesDir, "shared"))
        launcher = PRootLauncher(app, BootstrapManager(app))
    }

    private fun buildEnv(): Map<String, String> = launcher.buildLaunchSpec(
        rootfsPath = tmp.newFolder("rootfs").absolutePath,
        prootTmpDir = tmp.newFolder("proot-tmp").absolutePath
    ).env

    @Test
    fun everyGuestLaunchSetsTheFilter() {
        val env = buildEnv()
        assertEquals(
            "the filter must name exactly the host-owned variables (F-8)",
            PRootLauncher.GUEST_ENV_FILTER,
            env["PROOT_GUEST_ENV_FILTER"]
        )
    }

    @Test
    fun filterCoversEveryHostEnvKeyThatCanReachTheGuest() {
        // The filter must keep in lockstep with HOST_ENV_KEYS: every
        // host-owned key that appears in prootEnv must be listed, or it
        // would leak. (Pre-release audit 2026-09-02: PROOT_NO_SECCOMP used
        // to be a deliberately-tolerated diagnostic knob — it is filtered
        // now, along with the loader-injection guard keys LD_PRELOAD/LD_AUDIT.)
        val filtered = PRootLauncher.GUEST_ENV_FILTER.split(",").toSet()
        val mustFilter = setOf(
            "LD_LIBRARY_PATH", "PROOT_TMP_DIR", "PROOT_LOADER", "TERMINFO",
            "PROOT_NO_SECCOMP", "LD_PRELOAD", "LD_AUDIT"
        )
        assertTrue(
            "every host-infrastructure key that reaches prootEnv must be scrubbed",
            mustFilter.all { it in filtered }
        )
        // and every filtered name must be recognized as host-owned
        assertTrue(
            "every filtered name must be a HOST_ENV_KEY (so config.yaml cannot un-filter it)",
            filtered.all { it in BootstrapManager.HOST_ENV_KEYS }
        )
    }

    @Test
    fun filterIsHostOwnedAndConfigCannotOverrideIt() {
        // PROOT_GUEST_ENV_FILTER joined the protected set — a guest-authored
        // config.yaml cannot clear the scrub and re-leak host paths.
        assertTrue(
            "PROOT_GUEST_ENV_FILTER must be in HOST_ENV_KEYS",
            "PROOT_GUEST_ENV_FILTER" in BootstrapManager.HOST_ENV_KEYS
        )
    }

    @Test
    fun guestPathIsStillTheGuestPath() {
        // The filter must not touch PATH: the guest layer already overrides
        // it with the guest search path, and that value must survive.
        val env = buildEnv()
        assertEquals(
            "the guest PATH is not a leak — it is the guest's own value and must be preserved",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            env["PATH"]
        )
        assertNotEquals(
            "the host bootstrap binDir PATH must not win over the guest PATH",
            env["PATH"]?.contains("bootstrap"),
            true
        )
    }

    @Test
    fun hostLoaderVariablesRemainInLaunchEnvForProotItself() {
        // The scrub happens INSIDE proot (post-linkage). The launch env must
        // still carry the host values proot needs: LD_LIBRARY_PATH for its
        // own dynamic linkage, PROOT_TMP_DIR for its scratch scoping.
        val prootTmp = tmp.newFolder("proot-tmp")
        val env = launcher.buildLaunchSpec(
            rootfsPath = tmp.newFolder("rootfs").absolutePath,
            prootTmpDir = prootTmp.absolutePath
        ).env
        assertTrue(
            "LD_LIBRARY_PATH must stay in the launch env for proot's own linkage (scrubbed later, in proot)",
            env["LD_LIBRARY_PATH"]?.contains("bootstrap") == true
        )
        assertEquals(
            "PROOT_TMP_DIR must stay in the launch env verbatim (proot reads it itself)",
            prootTmp.absolutePath,
            env["PROOT_TMP_DIR"]
        )
        // and the filter variable itself is present for proot to read —
        // the guest never sees it because it filters its own name too.
        assertTrue(env.containsKey("PROOT_GUEST_ENV_FILTER"))
    }

    @Test
    fun filterListIsSelfScrubbing() {
        // The control variable must filter its own name, or it would become
        // the one remaining leak.
        assertTrue(
            "PROOT_GUEST_ENV_FILTER must include its own name",
            "PROOT_GUEST_ENV_FILTER" in PRootLauncher.GUEST_ENV_FILTER.split(",")
        )
    }

    @Test
    fun filterValueIsWellFormed() {
        // No empty tokens, no whitespace, no duplicates — the C tokenizer
        // splits on ',' only.
        val names = PRootLauncher.GUEST_ENV_FILTER.split(",")
        assertTrue("no empty filter tokens", names.none { it.isBlank() })
        assertEquals("no whitespace in filter names", names, names.map { it.trim() })
        assertEquals("no duplicate filter names", names.size, names.toSet().size)
        assertFalse("names must be bare (no '=' form)", names.any { it.contains("=") })
    }
}
