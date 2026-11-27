package com.fem.linexa.ui.screens.desktop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for the API 28–29 display-bounds fallback in
 * `DesktopViewModel.realDisplayBounds`.
 *
 * ## Why a source test
 *
 * The bug this pins is invisible to the compiler and to a JVM test: the code
 * asked `getSystemService(Context.WINDOW_SERVICE)` and cast the result with
 * `as? DisplayManager`. A `WindowManager` is never a `DisplayManager`, so the
 * safe cast silently produced `null` and the fallback returned `(0, 0)` on
 * every API 28–29 device. Lint's `ServiceCast` check found it. The function
 * needs a real `Application` and a real `Build.VERSION`, and this module has no
 * Robolectric harness for it, so a source assertion is the available net.
 *
 * Same technique as [DesktopGeometryTraceWiringSourceTest].
 */
class DesktopDisplayBoundsWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
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
            "src/main/java/com/fem/linexa/ui/screens/desktop/DesktopViewModel.kt"
        ).readText()

    private val displayBoundsBody: String
        get() = viewModel
            .substringAfter("private fun realDisplayBounds(")
            .substringBefore("private fun ")

    @Test
    fun theApi28FallbackAsksForTheDisplayService() {
        assertTrue(
            "the DisplayManager must come from Context.DISPLAY_SERVICE: $displayBoundsBody",
            displayBoundsBody.contains("Context.DISPLAY_SERVICE")
        )
    }

    @Test
    fun theApi28FallbackNeverCastsTheWindowServiceToADisplayManager() {
        assertFalse(
            "WINDOW_SERVICE returns a WindowManager; casting it to DisplayManager " +
                "always yields null: $displayBoundsBody",
            displayBoundsBody.contains(
                "WINDOW_SERVICE)\n                as? android.hardware.display.DisplayManager"
            )
        )
    }
}
