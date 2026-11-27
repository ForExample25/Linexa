package com.fem.linexa.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural regression guard for the Persian text-fitting pass.
 *
 * The failure this guards against is not a compile error: it is a localized
 * label that quietly regains `maxLines = 1` + `TextOverflow.Ellipsis` (or a
 * fixed-height container) and truncates a longer Persian translation on a
 * device. Compose-Robolectric cannot run in this project's CI/terminal
 * environment, so these tests read the shared component sources and assert the
 * *layout contract* the screens rely on:
 *
 *  - page headers and top bars wrap their localized titles;
 *  - list rows, buttons, segmented controls, chips, pills and menus grow
 *    vertically instead of clipping their labels;
 *  - dialogs scroll a long body while keeping their action row pinned.
 *
 * Technical content (paths, IDs, commands, terminal output) is intentionally
 * still allowed to ellipsize; that is asserted at the style level by
 * [com.fem.linexa.core.i18n.LocalizationSourceTest].
 */
class AdaptiveLayoutSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/res/values/strings.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        error("Could not locate the app module from ${File(".").absolutePath}")
    }

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/$relative").readText()

    /**
     * The complete body of a top-level `fun name(...)`, braces included.
     *
     * The opening brace is searched for only after the parameter list closes,
     * so a default lambda parameter (`content: … = {}`) is not mistaken for
     * the function body.
     */
    private fun functionBody(source: String, name: String): String {
        val signature = "fun $name("
        val start = source.indexOf(signature)
        assertTrue("$name must exist in the source", start >= 0)
        val openParen = source.indexOf('(', start)
        var depthParens = 0
        var closeParen = -1
        for (index in openParen until source.length) {
            when (source[index]) {
                '(' -> depthParens++
                ')' -> {
                    depthParens--
                    if (depthParens == 0) {
                        closeParen = index
                        break
                    }
                }
            }
        }
        assertTrue("$name must have a closed parameter list", closeParen > openParen)
        val open = source.indexOf('{', closeParen)
        assertTrue("$name must have a block body", open > closeParen)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("$name has an unbalanced body")
    }

    @Test
    fun `page headers wrap localized titles instead of ellipsizing them`() {
        val chrome = source("java/com/fem/linexa/ui/components/haven/HavenChrome.kt")

        val pageHeader = functionBody(chrome, "HPageHeader")
        assertFalse(
            "A page title is localized: it must wrap, not be capped to one line",
            pageHeader.contains("maxLines = 1")
        )
        assertFalse(
            "A page title must never be ellipsized",
            pageHeader.contains("TextOverflow.Ellipsis")
        )
        assertTrue(
            "The title keeps the flexible width so a trailing count stays visible",
            pageHeader.contains("weight(1f, fill = false)")
        )

        val topBar = functionBody(chrome, "HTopBar")
        assertTrue(
            "A prose top-bar subtitle wraps in full; only mono paths keep one line",
            topBar.contains("maxLines = if (subtitleMono) 1 else Int.MAX_VALUE")
        )

        val legacy = functionBody(
            source("java/com/fem/linexa/ui/components/LinexaTopBar.kt"),
            "LinexaTopBar"
        )
        assertFalse(
            "The shared app bar is used by pushed screens and must wrap its title",
            legacy.contains("maxLines = 1")
        )
    }

    @Test
    fun `rows menus and controls grow vertically for longer translations`() {
        val surfaces = source("java/com/fem/linexa/ui/components/haven/HavenSurfaces.kt")

        val row = functionBody(surfaces, "HRow")
        assertFalse("List-row titles must wrap", row.contains("maxLines = 1"))
        assertTrue(
            "A mono description keeps two lines; prose descriptions show in full",
            row.contains("maxLines = if (descriptionMono) 2 else Int.MAX_VALUE")
        )

        val menu = functionBody(surfaces, "HMenu")
        assertTrue(
            "The dropdown menu must be able to widen for a longer label",
            menu.contains("widthIn(min = 172.dp, max = 280.dp)")
        )
        assertTrue("Menu labels wrap to two lines", menu.contains("maxLines = 2"))

        val controls = source("java/com/fem/linexa/ui/components/haven/HavenControls.kt")
        val button = functionBody(controls, "HButton")
        assertTrue(
            "A button height is a minimum so its label can wrap and grow the button",
            button.contains("heightIn(min = height)")
        )
        assertFalse(
            "A fixed button height clips a two-line Persian label",
            button.contains(".height(height)")
        )

        val segmented = functionBody(controls, "HSegmented")
        assertTrue(
            "Segment cells keep a minimum height that a wrapped label can grow",
            segmented.contains("heightIn(min = if (mini) HavenDim.segMiniH else HavenDim.segH)")
        )
        assertTrue("Segment labels wrap to two lines", segmented.contains("maxLines = 2"))

        val data = source("java/com/fem/linexa/ui/components/haven/HavenData.kt")
        for ((name, minimum) in listOf(
            "HChip" to "heightIn(min = HavenDim.chipH)",
            "HPill" to "heightIn(min = HavenDim.pillH)"
        )) {
            val body = functionBody(data, name)
            assertTrue("$name must keep a minimum height, not a cap", body.contains(minimum))
            assertFalse("$name labels must wrap", body.contains("maxLines = 1"))
        }
    }

    @Test
    fun `dialogs scroll a long body while pinning the action row`() {
        val surfaces = source("java/com/fem/linexa/ui/components/haven/HavenSurfaces.kt")
        val dialog = functionBody(surfaces, "HDialog")
        assertTrue(
            "The dialog body must scroll when the localized copy is tall",
            dialog.contains("verticalScroll(rememberScrollState())")
        )
        assertTrue(
            "The scroll area takes only the space left by the actions, never their space",
            dialog.contains("weight(1f, fill = false)")
        )

        val material = source("java/com/fem/linexa/ui/components/LinexaAlertDialog.kt")
        assertTrue(
            "Alert-dialog messages scroll when they outgrow the dialog",
            material.contains("private fun ScrollableDialogMessage(")
        )
        assertTrue(
            "The message cap is measured, not a hard-coded dp value",
            material.contains("heightIn(max = cap)")
        )
    }
}
