package com.fem.linexa.ui.theme.haven

import androidx.compose.runtime.Composable

/**
 * Haven design-system accessor.
 *
 * `HavenTheme.colors` is the ONE way Haven screens and components read
 * palette values; [HavenDim] and [HavenType] are static objects (they carry
 * no theme dependence). This keeps the chain
 *
 *   theme definition → tokens → components → screens
 *
 * intact: an external theme in the future only has to publish a
 * [HavenColors] into [LocalHavenColors] and every surface follows.
 */
object HavenTheme {
    val colors: HavenColors
        @Composable get() = LocalHavenColors.current
}
