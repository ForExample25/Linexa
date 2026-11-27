package com.fem.linexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The handful of Material icons Linexa actually renders.
 *
 * The app used to depend on `androidx.compose.material:material-icons-extended`
 * — roughly 11,400 generated classes — for seven icons. The two icons that
 * exist in `material-icons-core` (`Close`, `Share`) keep coming from there;
 * the five below are defined locally from the upstream Material path data, so
 * the extended artifact can leave the dependency graph (audit F-005 / R-02).
 *
 * Geometry is copied verbatim from Google's `material-design-icons`
 * repository (24 dp grid, 24 dp viewport) and the vectors are tinted by
 * Compose's `Icon`, exactly like the generated ones they replace.
 */
object LinexaIcons {

    /** `navigation/chevron_right/materialicons/24px.svg`. */
    val ChevronRight: ImageVector by lazy {
        vector(
            name = "Linexa.ChevronRight",
            pathData = "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"
        )
    }

    /** `hardware/desktop_windows/materialicons/24px.svg`. */
    val DesktopWindows: ImageVector by lazy {
        vector(
            name = "Linexa.DesktopWindows",
            pathData = "M20 3H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h6v2H8v2h8v-2h-2v-2h6" +
                "c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2"
        )
    }

    /** `hardware/keyboard/materialicons/24px.svg`. */
    val Keyboard: ImageVector by lazy {
        vector(
            name = "Linexa.Keyboard",
            pathData = "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7" +
                "c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2z" +
                "m-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2z" +
                "m3 3h-2v-2h2v2zm0-3h-2V8h2v2z"
        )
    }

    /** `action/open_in_browser/materialicons/24px.svg`. */
    val OpenInBrowser: ImageVector by lazy {
        vector(
            name = "Linexa.OpenInBrowser",
            pathData = "M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h4v-2H5V8h14v10h-4v2h4" +
                "c1.1 0 2-.9 2-2V6c0-1.1-.89-2-2-2zm-7 6l-4 4h3v6h2v-6h3l-4-4z"
        )
    }

    /** `device/battery_saver/materialiconsoutlined/24px.svg`. */
    val BatterySaver: ImageVector by lazy {
        vector(
            name = "Linexa.BatterySaver",
            pathData = "M16 4h-2V2h-4v2H8C7.45 4 7 4.45 7 5v16c0 .55.45 1 1 1h8c.55 0 1-.45" +
                " 1-1V5c0-.55-.45-1-1-1zm-1 10h-2v2h-2v-2H9v-2h2v-2h2v2h2v2z"
        )
    }

    private fun vector(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black)
        ).build()
}
