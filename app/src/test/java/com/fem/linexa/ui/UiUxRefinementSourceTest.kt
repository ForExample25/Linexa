package com.fem.linexa.ui

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.fem.linexa.ui.theme.haven.HavenScript
import com.fem.linexa.ui.theme.haven.HavenType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for the UI/UX consistency pass.
 *
 * Compose UI tests cannot run in this project's terminal/CI environment, so the
 * visual rules are pinned the same way the other `*SourceTest` classes pin
 * theirs: by reading the sources and asserting the wiring that produces the
 * behaviour. The script-profile maths ([HavenScript] / [HavenType]) is pure
 * Kotlin and is asserted behaviourally.
 *
 * Nothing here substitutes for an on-device look at Persian text; see
 * `UI_UX_IMPLEMENTATION_REPORT.md` for what was and was not verified.
 */
class UiUxRefinementSourceTest {

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
        File(moduleRoot, "src/main/java/com/fem/linexa/$relative").readText()

    // ── Persian / Arabic-script typography ──────────────────────────────────

    @Test
    fun `arabic script removes tracking and fixes the line box of tracked prose styles`() {
        try {
            HavenScript.publish(false)
            val latinEyebrow = HavenType.eyebrow
            assertTrue("Latin eyebrow keeps its designed tracking", latinEyebrow.letterSpacing.value > 0f)
            assertEquals(
                "Latin h1 keeps its designed negative tracking",
                TextUnitType.Em,
                HavenType.h1.letterSpacing.type
            )
            assertEquals(
                "Latin body has no explicit line height (unchanged English metrics)",
                TextUnit.Unspecified,
                HavenType.body.lineHeight
            )

            HavenScript.publish(true)
            for (style in listOf(
                HavenType.h1, HavenType.h2, HavenType.h3, HavenType.section,
                HavenType.eyebrow, HavenType.brand, HavenType.navLabel, HavenType.tileLabel
            )) {
                assertEquals("tracking breaks joined letters", 0f, style.letterSpacing.value, 0f)
            }
            assertEquals(
                "an undeclared line height becomes the fixed proportional box",
                TextUnitType.Em,
                HavenType.body.lineHeight.type
            )
            assertEquals(HavenScript.ARABIC_LINE_HEIGHT_EM, HavenType.body.lineHeight.value, 0f)
            assertEquals(
                "an explicit line height is never overridden",
                18.sp,
                HavenType.desc.lineHeight
            )
        } finally {
            HavenScript.publish(false)
        }
    }

    @Test
    fun `technical mono styles are identical in both scripts`() {
        try {
            HavenScript.publish(false)
            val latin = HavenType.descMono
            HavenScript.publish(true)
            assertEquals("mono/technical styles must not be script-calibrated", latin, HavenType.descMono)
        } finally {
            HavenScript.publish(false)
        }
    }

    @Test
    fun `prose styles are served by script-aware getters from one latin source`() {
        val colors = source("ui/theme/haven/HavenColors.kt")
        for (style in listOf(
            "h1", "h2", "h3", "title", "titleLg", "rowTitle", "body", "desc",
            "small", "section", "eyebrow", "brand", "navLabel", "tileLabel"
        )) {
            assertTrue(
                "$style must go through the script calibration",
                colors.contains("val $style: TextStyle get() = HavenLatinType.$style.forScript()")
            )
        }
    }

    @Test
    fun `no screen or component hard-codes letter spacing on prose`() {
        val offenders = File(moduleRoot, "src/main/java/com/fem/linexa/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.replace('\\', '/').endsWith("ui/theme/haven/HavenColors.kt") }
            .filter { Regex("""letterSpacing\s*=\s*[^,)\n]*\bem\(""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue(
            "tracking must come from HavenType.tracking(...) so Persian stays joined; offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `theme publishes the script profile from the localized configuration`() {
        val theme = source("ui/theme/Theme.kt")
        assertTrue(theme.contains("HavenScript.publish("))
        assertTrue(
            "the profile must come from the (localized) LocalConfiguration, not a hard-coded language",
            theme.contains("LocalConfiguration.current.locales")
        )
        assertFalse(
            "the layout policy stays fixed-LTR: no direction is derived from the language",
            theme.contains("LocalLayoutDirection")
        )
    }

    @Test
    fun `window bar mutations run inside a side effect`() {
        val theme = source("ui/theme/Theme.kt")
        val sideEffect = theme.substringAfter("SideEffect {", "")
        assertTrue("Theme.kt must wrap window mutations in SideEffect", sideEffect.isNotEmpty())
        val beforeSideEffect = theme.substringBefore("SideEffect {")
        assertFalse(
            "isAppearanceLightStatusBars must not run in the composable body",
            beforeSideEffect.contains("isAppearanceLightStatusBars")
        )
        assertTrue(sideEffect.contains("isAppearanceLightStatusBars"))
    }

    // ── Spacing consistency ─────────────────────────────────────────────────

    @Test
    fun `header action circles share one gap token everywhere`() {
        val chrome = source("ui/components/haven/HavenChrome.kt")
        assertEquals(
            "HPageHeader and HTopBar-style trailing rows both use the token",
            2,
            Regex("""Arrangement\.spacedBy\(HavenDim\.headerActionGap""").findAll(chrome).count()
        )
        assertTrue(source("ui/components/LinexaTopBar.kt").contains("spacedBy(HavenDim.headerActionGap)"))
        assertTrue(source("ui/screens/device/DeviceInfoScreen.kt").contains("spacedBy(HavenDim.headerActionGap)"))
    }

    @Test
    fun `workspace terminal and desktop circles are no longer edge to edge`() {
        val workspace = source("ui/screens/workspace/WorkspaceScreen.kt")
        val bar = workspace.substringAfter("R.string.workspace_back_to_home")
            .substringBefore("AnimatedContent(")
        assertTrue(
            "the Terminal and Desktop circles must sit in a Row that applies the header gap",
            bar.contains("Arrangement.spacedBy(HavenDim.headerActionGap)")
        )
        assertTrue(bar.indexOf("spacedBy(HavenDim.headerActionGap)") < bar.indexOf("ic_haven_terminal"))
        assertTrue(bar.indexOf("ic_haven_terminal") < bar.indexOf("ic_haven_monitor"))
    }

    // ── Bottom navigation ───────────────────────────────────────────────────

    @Test
    fun `bottom navigation exposes tab semantics and keeps its sliding indicator`() {
        val chrome = source("ui/components/haven/HavenChrome.kt")
        val nav = chrome.substringAfter("fun HBottomNav(").substringBefore("private fun Modifier.navSelectionPill")
        assertTrue("items must be selectable tabs", nav.contains(".selectable(") && nav.contains("Role.Tab"))
        assertTrue("the selected state must be reported", nav.contains("selected = isOn"))
        assertTrue("the indicator animation is preserved", nav.contains("nav-indicator"))
        assertFalse(
            "the icon must be decorative; the label already names the tab",
            nav.contains("contentDescription = label")
        )
    }

    // ── Application update card ─────────────────────────────────────────────

    @Test
    fun `update card transitions between state kinds without restarting on progress ticks`() {
        val update = source("ui/screens/settings/AppUpdateSection.kt")
        assertTrue(update.contains("AnimatedContent("))
        assertTrue(
            "ticks inside one state kind must share a content key",
            update.contains("contentKey = { it::class }")
        )
        assertTrue("size changes use the shared container token", update.contains("motions.containerSpec"))
        assertTrue("fade uses the shared enter/exit tokens", update.contains("motions.enter") && update.contains("motions.exit"))
        assertFalse(
            "the update card must not literalise a duration",
            Regex("""\btween\(\s*\d""").containsMatchIn(update)
        )
    }

    @Test
    fun `update card keeps localized prose out of the LTR-pinned mono style`() {
        val update = source("ui/screens/settings/AppUpdateSection.kt")
        val lastChecked = update.substringAfter("text = lastCheckedLabel(state.lastCheckAtMs),")
            .substringBefore("modifier = Modifier.weight(1f)")
        assertTrue("the last-checked sentence is localized prose", lastChecked.contains("HavenType.small"))
        assertFalse(lastChecked.contains("descMono"))
        assertTrue(
            "physical right edge in both languages (End flips with the paragraph direction)",
            lastChecked.contains("TextAlign.Right")
        )
    }

    @Test
    fun `update card meta labels wrap instead of overflowing`() {
        val update = source("ui/screens/settings/AppUpdateSection.kt")
        assertTrue(update.contains("FlowRow("))
        assertTrue(update.contains("@OptIn(ExperimentalLayoutApi::class)"))
    }

    @Test
    fun `update card paddings come from card tokens`() {
        val update = source("ui/screens/settings/AppUpdateSection.kt")
        assertFalse(
            "row paddings must use HavenDim.cardRow* / cardBlockV, not 20/14/12/16 literals",
            Regex("""horizontal = 20\.dp, vertical = (12|14|16)\.dp""").containsMatchIn(update)
        )
    }
}
