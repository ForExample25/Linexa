package com.fem.linexa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.rememberHavenMotions
import com.fem.linexa.ui.theme.haven.HavenColors
import com.fem.linexa.ui.theme.haven.LocalHavenColors

/**
 * HAVEN theme entry point (concept-2-haven.html rebuild).
 *
 * One call resolves the entire design system:
 *
 *  1. [HavenColors] for the (darkTheme, colorTheme) pair — the token layer
 *     every Haven component consumes via [LocalHavenColors];
 *  2. a Material 3 [androidx.compose.material3.ColorScheme] **projected from
 *     the same tokens**, so legacy M3 components (dialogs, sheets, text
 *     fields inside ViewModels' screens not yet migrated) inherit the Haven
 *     palette without per-screen edits.
 *
 * The token chain is: theme definition → Haven tokens → components → screens.
 * A future external theme format only has to produce a [HavenColors] to
 * re-skin the whole app.
 *
 * Motion is published here too — once, above `MaterialTheme`, through
 * [LocalHavenMotions]. Components read that CompositionLocal instead of
 * calling `rememberHavenMotions()` themselves, so the animator-scale resolver
 * read happens exactly once per scale change rather than once per animated
 * surface. The provider sits above `MaterialTheme` so a `MaterialTheme` content
 * lambda can observe the same tokens.
 *
 * [darkTheme] keeps defaulting to `isSystemInDarkTheme()` and [colorTheme]
 * to [ColorTheme.DEFAULT] so previews and non-Hilt entry points still work;
 * **`LinexaApp` must pass both explicitly** (pinned by
 * `SettingsWiringSourceTest` — the §1.19 defect class).
 */
@Composable
fun LinexaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorTheme: ColorTheme = ColorTheme.DEFAULT,
    content: @Composable () -> Unit
) {
    val haven = if (darkTheme) {
        HavenColors.darkPalette(HavenColors.accentFor(colorTheme, dark = true))
    } else {
        HavenColors.lightPalette(HavenColors.accentFor(colorTheme, dark = false))
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? android.app.Activity)?.window
        window?.let {
            it.statusBarColor = Color.Transparent.toArgb()
            it.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val motions = rememberHavenMotions()
    CompositionLocalProvider(
        LocalHavenColors provides haven,
        LocalHavenMotions provides motions
    ) {
        MaterialTheme(
            colorScheme = haven.toMaterialScheme(),
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

/**
 * Projects the Haven tokens onto the M3 slots used by legacy components.
 * Tone steps map 1:1: background=bg, surface=surface, surfaceVariant=surface2;
 * primary/tint/error keep Haven's soft-green/red identity.
 */
fun HavenColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = tint,
        onPrimaryContainer = primary,
        secondary = blue,
        onSecondary = Color(0xFF07130C),
        secondaryContainer = blueTint,
        onSecondaryContainer = blue,
        tertiary = amber,
        onTertiary = Color(0xFF07130C),
        tertiaryContainer = amberTint,
        onTertiaryContainer = amber,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface2,
        onSurfaceVariant = text2,
        surfaceTint = primary,
        inverseSurface = surface2,
        inverseOnSurface = text,
        error = red,
        onError = Color(0xFF07130C),
        errorContainer = redTint,
        onErrorContainer = red,
        outline = border,
        outlineVariant = border,
        scrim = Color.Black,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface2
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = tint,
        onPrimaryContainer = primary,
        secondary = blue,
        onSecondary = Color.White,
        secondaryContainer = blueTint,
        onSecondaryContainer = blue,
        tertiary = amber,
        onTertiary = Color.White,
        tertiaryContainer = amberTint,
        onTertiaryContainer = amber,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface2,
        onSurfaceVariant = text2,
        surfaceTint = primary,
        inverseSurface = surface2,
        inverseOnSurface = text,
        error = red,
        onError = Color.White,
        errorContainer = redTint,
        onErrorContainer = red,
        outline = border,
        outlineVariant = border,
        scrim = Color.Black,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface2
    )
}

/**
 * Haven corner language: 20 cards / 14 buttons / 24 sheets — carried here so
 * any remaining M3 component (ModalBottomSheet handles its own shape) lands
 * on the same visual rhythm. See [com.fem.linexa.ui.theme.haven.HavenDim].
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
