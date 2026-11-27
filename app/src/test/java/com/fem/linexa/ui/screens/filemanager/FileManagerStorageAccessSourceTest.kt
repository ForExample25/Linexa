package com.fem.linexa.ui.screens.filemanager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * File Manager stabilization — the API-30 storage gate.
 *
 * `Environment.isExternalStorageManager()` is a method added in Android R.
 * The app's `minSdk` is 28, so an unguarded call throws `NoSuchMethodError`
 * and kills the File Manager's Android-storage paths on Android 9/10 — devices
 * the app still supports. The lint `NewApi` findings on the three former call
 * sites were the confirmation; this test is the regression guard.
 *
 * The check lives beside the call in `hasAllFilesAccess()` because lint's
 * `NewApi` detector cannot see through a separate predicate (which is why the
 * already-guarded `AccessSettingsGroup` is still flagged). Only an Android
 * runtime can execute the real call, so the repo's established source-guard
 * idiom pins the structure: exactly one direct call, inside the guarded
 * helper, and every storage entry point goes through it.
 */
class FileManagerStorageAccessSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/ui/screens/filemanager/FileManagerViewModel.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private val viewModel: String
        get() = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/ui/screens/filemanager/FileManagerViewModel.kt"
        ).readText()

    @Test
    fun theApi30GrantIsCheckedBehindAVersionGuard() {
        val helperStart = viewModel.indexOf("private fun hasAllFilesAccess(): Boolean")
        assertTrue("hasAllFilesAccess() must exist", helperStart >= 0)
        val helper = viewModel.substring(helperStart).substringBefore("\n    }")

        assertTrue(
            "the helper must reject pre-R before touching the API: $helper",
            helper.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.R")
        )
        assertTrue(
            "the helper must perform the actual grant check: $helper",
            helper.contains("Environment.isExternalStorageManager()")
        )
    }

    @Test
    fun thereIsExactlyOneDirectCallAndItIsInsideTheGuard() {
        val calls = Regex("Environment\\.isExternalStorageManager\\(\\)").findAll(viewModel).toList()
        assertEquals(
            "a new direct call site would bypass the API-30 gate",
            1,
            calls.size
        )

        val callIndex = calls.single().range.first
        val helperStart = viewModel.indexOf("private fun hasAllFilesAccess(): Boolean")
        val helperEnd = viewModel.indexOf("\n    }", helperStart)
        assertTrue(
            "the only direct call must be inside hasAllFilesAccess()",
            callIndex in helperStart..helperEnd
        )
    }

    @Test
    fun everyAndroidStorageEntryPointUsesTheGuard() {
        assertTrue(
            "androidStorageRoot() must gate before reading shared storage",
            viewModel.contains("if (!hasAllFilesAccess()) return null")
        )
        assertTrue(
            "refreshAndroidStorage() must report not-granted instead of crashing",
            viewModel.contains(
                "if (!hasAllFilesAccess()) return@withContext AndroidStorage(null, null, false)"
            )
        )
        assertTrue(
            "androidPlaces() must return no places instead of crashing",
            viewModel.contains("if (!hasAllFilesAccess()) return@withContext emptyList()")
        )
        assertFalse(
            "no storage entry point may call the API directly",
            viewModel.contains("if (!Environment.isExternalStorageManager())")
        )
    }
}
