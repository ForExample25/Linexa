package com.fem.linexa.service.runtime.bootstrap

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.service.runtime.container.PRootLauncher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * §1.190 — the two env outputs [BootstrapManager] projects from ONE
 * computation, and the guarantee that each launch path consumes the right
 * one.
 *
 * Background (the leak this file exists to pin): `processEnv()` was the
 * single, undifferentiated env for BOTH host-side helper `ProcessBuilder`
 * calls AND [PRootLauncher]'s traced child. PRoot passes its environment
 * through to the guest verbatim, so the host bootstrap lib path landed in
 * every guest process's env with nothing naming that fact or testing it.
 * The value is still there deliberately — PRoot needs it to resolve its own
 * `libtalloc.so.2` — but it is now reached through a *named* guest-facing
 * output whose KDoc carries the constraint, so a future contributor cannot
 * change one consumer's env without seeing the other.
 *
 * Contract under test:
 *  - both outputs derive from one computation: identical key/value content,
 *    so adding a key cannot silently reach only one consumer;
 *  - the host output still carries `LD_LIBRARY_PATH` = the host bootstrap
 *    lib dir (unchanged — installer applets and tmux cannot link without
 *    it);
 *  - the guest-launch spec carries exactly that same value, and the KDoc'd
 *    reason is enforced by [BootstrapManager.HOST_ENV_KEYS] membership so
 *    guest-authored `config.yaml` can never redirect it;
 *  - `prootEnv()` is layered on the guest output and adds only the
 *    per-invocation `PROOT_TMP_DIR` (§1.48).
 *
 * JVM-only, mock [Application], no `setup()` required: every key under test
 * is derived from `filesDir`/`nativeLibraryDir` path getters. `PROOT_LOADER`
 * and `TERMINFO` are absent here (the loader artifact fails validation
 * against inert stand-ins, terminfo is not installed) — deliberately not
 * asserted, since this file is about WHICH output a consumer gets, not about
 * §1.47/§1.75 provisioning, which their own tests already cover.
 */
class BootstrapEnvOutputSplitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nativeLibDir: File
    private lateinit var filesDir: File
    private lateinit var manager: BootstrapManager
    private lateinit var launcher: PRootLauncher

    @Before
    fun setUp() {
        nativeLibDir = tmp.newFolder("native-lib")
        filesDir = tmp.newFolder("files")
        val app = app()
        manager = BootstrapManager(app)
        launcher = PRootLauncher(app, manager)
    }

    private fun app(): Application {
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = nativeLibDir.absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(File(filesDir, "cache"))
        Mockito.`when`(app.getExternalFilesDir(any())).thenReturn(File(filesDir, "shared"))
        return app
    }

    private val hostLibPath: String get() = File(filesDir, "bootstrap/lib").absolutePath

    private fun guestSpecEnv(): Map<String, String> = launcher.buildLaunchSpec(
        rootfsPath = File(filesDir, "workspaces/test/rootfs").absolutePath,
        prootTmpDir = File(filesDir, "bootstrap/tmp/test").absolutePath
    ).env

    /**
     * The single-source guarantee, stated as data. Two names, one
     * computation — if someone forks the bodies so the outputs can drift,
     * this fails regardless of which key they add.
     */
    @Test
    fun hostAndGuestOutputsAreProjectionsOfOneComputation() {
        assertEquals(
            "§1.190: processEnv() and guestLaunchEnv() must be projections of ONE computation — " +
                "differentiate a consumer by selecting from it, never by forking the builder",
            manager.processEnv(),
            manager.guestLaunchEnv()
        )
    }

    /**
     * The host half of the fix's acceptance criteria: host-side
     * `ProcessBuilder` consumers (RootfsExtractor, RootfsFixupService,
     * GuestRootfsInitializer, ManagementTerminalViewModel, tmux teardown)
     * are completely unaffected.
     */
    @Test
    fun hostProcessEnvStillCarriesHostBootstrapLibPath() {
        val env = manager.processEnv()
        assertEquals(
            "host-process env must keep LD_LIBRARY_PATH = bootstrap/lib — jniLibs ships PRoot's deps only " +
                "under mangled lib__lib__ names, so libDir's soname symlink farm is the ONLY resolver for " +
                "the libtalloc.so.2 SONAME; without it every bootstrap binary dies before main()",
            hostLibPath,
            env["LD_LIBRARY_PATH"]
        )
        assertEquals(
            "host-process env PATH must stay the bootstrap binDir",
            File(filesDir, "bootstrap/bin").absolutePath,
            env["PATH"]
        )
    }

    /**
     * The guest half. `LD_LIBRARY_PATH` is present ON PURPOSE — PRoot is the
     * process being exec'd and cannot link without it (verified on-device:
     * `env -u LD_LIBRARY_PATH proot …` → `CANNOT LINK EXECUTABLE`). This
     * asserts the value is the ONE authored by BootstrapManager and nothing
     * else, so a guest-side path can never be smuggled in.
     */
    @Test
    fun guestLaunchSpecCarriesOnlyTheBootstrapAuthoredLibPath() {
        val value = guestSpecEnv()["LD_LIBRARY_PATH"]
        assertEquals(
            "§1.190: the guest-launch env's LD_LIBRARY_PATH must be exactly the value guestLaunchEnv() " +
                "authored — PRoot needs it to resolve its own libtalloc.so.2 and offers no env-scrubbing " +
                "flag, so this is the single authority for the key",
            hostLibPath,
            value
        )
        assertFalse(
            "a guessed guest-side path (/lib:/usr/lib) must never be invented — the guest's own linker " +
                "already searches those plus the binary's RPATH/RUNPATH",
            value.orEmpty().split(':').any { it == "/lib" || it == "/usr/lib" }
        )
    }

    /**
     * The structural reason the leak cannot get worse: because the guest
     * sees this key verbatim, a guest-authored `config.yaml` must not be
     * able to redirect it. Enforced by data (§1.49), asserted here.
     */
    @Test
    fun guestVisibleLinkerKeysAreConfigProtected() {
        listOf("LD_LIBRARY_PATH", "PATH", "PROOT_LOADER").forEach { key ->
            assertTrue(
                "$key reaches the guest verbatim through the launch env, so config.yaml (linexa.env) must " +
                    "never override it — it must stay in HOST_ENV_KEYS",
                key in BootstrapManager.HOST_ENV_KEYS
            )
        }
    }

    /**
     * §1.48/§1.190: `prootEnv` is the guest output plus exactly one
     * per-invocation key. Pins that it is layered on `guestLaunchEnv()` and
     * that `PROOT_TMP_DIR` stays caller-scoped rather than leaking into the
     * shared computation.
     */
    @Test
    fun prootEnvIsGuestOutputPlusOnlyScopedTmpDir() {
        val scoped = File(filesDir, "bootstrap/tmp/session").absolutePath
        val prootEnv = manager.prootEnv(scoped)

        assertEquals("prootEnv must add PROOT_TMP_DIR", scoped, prootEnv["PROOT_TMP_DIR"])
        assertEquals(
            "prootEnv must be guestLaunchEnv() + PROOT_TMP_DIR and nothing else",
            manager.guestLaunchEnv() + ("PROOT_TMP_DIR" to scoped),
            prootEnv
        )
        assertFalse(
            "§1.48: PROOT_TMP_DIR is per-invocation — the shared computation must never carry it",
            "PROOT_TMP_DIR" in manager.guestLaunchEnv()
        )
    }

    /**
     * The launch spec must actually consume the guest output, not be
     * assembled from some other env source. Checks the guest-facing
     * overlays land on top of it (§1.49 layering) without displacing the
     * host-owned *linker* keys.
     *
     * `PATH` is the one deliberate exception and worth stating explicitly:
     * the shared computation sets it to the host `binDir`, and
     * `buildLaunchSpec` intentionally replaces it with the guest PATH
     * (`/usr/local/sbin:…:/bin`), because inside the rootfs the host binDir
     * is meaningless. `LD_LIBRARY_PATH` gets no such treatment — see
     * [BootstrapManager.guestLaunchEnv] for why it cannot be replaced.
     */
    @Test
    fun guestLaunchSpecLayersGuestDefaultsOverTheSharedComputation() {
        val env = guestSpecEnv()

        assertEquals("guest HOME must be the guest working directory", "/root", env["HOME"])
        assertEquals("guest USER must be root (-0 root emulation)", "root", env["USER"])
        assertNotNull("guest TERM must be set for the pty", env["TERM"])

        assertEquals(
            "guest PATH must be the GUEST search path, not the host binDir — the host path does not exist " +
                "inside the rootfs",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            env["PATH"]
        )

        (manager.guestLaunchEnv() - "PATH").forEach { (key, expected) ->
            assertEquals(
                "guest-facing overlays must not displace host-owned key $key from the shared computation",
                expected,
                env[key]
            )
        }
    }
}
