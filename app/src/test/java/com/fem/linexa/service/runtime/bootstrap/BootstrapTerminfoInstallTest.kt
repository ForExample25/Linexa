package com.fem.linexa.service.runtime.bootstrap

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.test.support.TerminfoTestFixture
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * §1.75 — host-side terminfo for the bundled tmux. JVM tests driving the
 * REAL [BootstrapManager.setup()] against a mock [Application] only (like
 * the other bootstrap tests), fed through the test seam
 * ([BootstrapManager.TerminfoTestSeam]) since `Application.getAssets()` is final and cannot
 * be stubbed.
 *
 * Contract under test:
 *  - a valid install extracts every [BootstrapManager.TERMINFO_ENTRIES] into
 *    `filesDir/terminfo`, validates ncurses magic, and exposes it as
 *    `processEnv()["TERMINFO"]` — with `isReady` still true and no
 *    `lastError` (binary ACCESS validation remains the launch gate),
 *  - a stale/corrupt installed entry is self-healed on the next `setup()`,
 *  - garbage vendored bytes are rejected (not installed, no crash),
 *  - no assets available (fresh JVM mock, no seam) fails open: no
 *    `TERMINFO`, `isReady` untouched, gap surfaced via `lastError`.
 *
 * The `x/xterm-256color` fixture is the REAL compiled ncurses artifact,
 * base64-pinned in [TerminfoTestFixture] — magic 0x1E 0x02.
 */
class BootstrapTerminfoInstallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nativeLibDir: File
    private lateinit var filesDir: File
    private lateinit var manager: BootstrapManager

    private val managedFiles = listOf(
        "lib__bin__busybox__.so", "lib__bin__tar__.so", "lib__bin__zstd__.so",
        "lib__bin__tmux__.so", "lib__bin__loader__.so", "libproot.so",
        "lib__lib__libbusybox.so.1.38.0__.so", "lib__lib__libacl.so__.so",
        "lib__lib__libandroid-glob.so__.so", "lib__lib__libandroid-selinux.so__.so",
        "lib__lib__libandroid-support.so__.so", "lib__lib__libiconv.so__.so",
        "lib__lib__libpcre2-8.so__.so", "lib__lib__libzstd.so.1__.so",
        "lib__lib__libz.so.1__.so", "lib__lib__liblzma.so.5__.so",
        "lib__lib__libncursesw.so.6__.so", "lib__lib__libevent_core-2.1.so__.so"
    )

    @Before
    fun setUp() {
        BootstrapManager.TerminfoTestSeam.overrideBytes = null
        nativeLibDir = tmp.newFolder("native-lib")
        filesDir = tmp.newFolder("files")
        tmp.newFolder("cache")
        for (name in managedFiles) {
            File(nativeLibDir, name).writeText("inert bootstrap stand-in for JVM tests: $name\n")
        }
        manager = BootstrapManager(app())
    }

    @After
    fun tearDown() {
        BootstrapManager.TerminfoTestSeam.overrideBytes = null
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

    private fun terminfoEntry(entry: String): File = File(File(filesDir, "terminfo"), entry)

    @Test
    fun setupExtractsVendoredEntriesValidatesMagicAndExposesTerminfoEnv() {
        BootstrapManager.TerminfoTestSeam.overrideBytes = TerminfoTestFixture.validEntries

        manager.setup()

        BootstrapManager.TERMINFO_ENTRIES.forEach { entry ->
            val installed = terminfoEntry(entry)
            assertTrue("every vendored entry must be extracted: ${entry}@${installed.absolutePath}", installed.isFile)
        }
        // The pinned real xterm-256color artifact keeps its ncurses extended magic.
        val keyBytes = terminfoEntry("x/xterm-256color").readBytes()
        assertTrue("real vendored artifact must keep ncurses magic (1E 02)", BootstrapManager.isTerminfoMagic(keyBytes))
        assertTrue("pinned fixture must decode to the real artifact", keyBytes.contentEquals(TerminfoTestFixture.realXterm256color))

        assertEquals("TERMINFO must point at filesDir/terminfo", File(filesDir, "terminfo").absolutePath, manager.processEnv()["TERMINFO"])
        assertTrue("binary ACCESS validation is untouched by the terminfo step", manager.isReady)
        assertNull("a clean install must not set lastError", manager.lastError)
    }

    @Test
    fun setupSelfHealsCorruptInstalledEntry() {
        TerminfoTestFixture.validEntries.let { BootstrapManager.TerminfoTestSeam.overrideBytes = it }
        val corrupt = terminfoEntry("x/xterm-256color")
        corrupt.parentFile?.mkdirs()
        corrupt.writeBytes(byteArrayOf(0x00, 0x00, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))

        manager.setup()

        val healed = terminfoEntry("x/xterm-256color").readBytes()
        assertTrue("setup() must overwrite a corrupt entry with the vendored copy", isMagic(healed))
        assertTrue(healed.contentEquals(TerminfoTestFixture.realXterm256color))
    }

    @Test
    fun garbageVendoredEntryIsRejectedNotInstalled() {
        BootstrapManager.TerminfoTestSeam.overrideBytes = BootstrapManager.TERMINFO_ENTRIES.associateWith {
            if (it == "x/xterm-256color") byteArrayOf(0x00, 0x00, 0x00, 0x00) else byteArrayOf(0x1E, 0x02, 0x00)
        }

        manager.setup()

        val key = terminfoEntry("x/xterm-256color")
        assertTrue("a bad-magic entry must never be trusted", !key.isFile)
        assertNull("bad-magic must fail open (no TERMINFO)", manager.processEnv()["TERMINFO"])
        assertTrue("binary ACCESS validation remains the launch gate", manager.isReady)
        assertTrue("the rejection must be loud via lastError", manager.lastError?.contains("bad magic") == true)
    }

    @Test
    fun unavailableAssetsFailOpenWithoutTerminfoEnv() {
        // No seam → Mockito's getAssets(): null → open() throws → issue logged.
        manager.setup()

        assertNull("no usable DB → TERMINFO must be omitted (ncurses falls back)", manager.processEnv()["TERMINFO"])
        assertTrue("isReady is untouched by a terminfo gap", manager.isReady)
        assertTrue(
            "the gap must be loud via lastError (key entry named)",
            manager.lastError?.contains("x/xterm-256color") == true
        )
    }

    private fun isMagic(bytes: ByteArray): Boolean =
        BootstrapManager.isTerminfoMagic(bytes)
}