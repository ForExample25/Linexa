package com.fem.linexa.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Legacy token catalog — HAVEN values.
 *
 * The Haven rebuild (concept-2-haven.html) moved the *authoritative* palette
 * to [com.fem.linexa.ui.theme.haven.HavenColors], which is theme-reactive
 * (dark AND light) and provided via CompositionLocal. This file remains for
 * the existing non-composable reference sites (ViewModel fallbacks, static
 * component defaults, the terminal preview palette) — every value now equals
 * its Haven dark-palette counterpart so legacy references and Haven screens
 * render the same design.
 *
 * New Haven code must NOT consume these statics: read
 * `HavenTheme.colors` / `LocalHavenColors.current` instead.
 */

// ── Backgrounds (Haven tone steps) ──────────────────────────────────────────
val Background = Color(0xFF101113)            // --bg
val BackgroundSecondary = Color(0xFF17181B)   // --surface
val BackgroundElevated = Color(0xFF1E2024)    // --surface2
val Surface = Color(0xFF17181B)               // --surface
val SurfaceHover = Color(0xFF1E2024)          // --surface2
val SurfacePressed = Color(0xFF26282D)        // pressed row fill
val Border = Color.White.copy(alpha = 0.06f)  // --border
val BorderStrong = Color.White.copy(alpha = 0.12f)
val Divider = Color.White.copy(alpha = 0.06f) // hairline

// ── Haven primary ───────────────────────────────────────────────────────────
val Primary = Color(0xFF2AD178)
val PrimaryMuted = Color(0xFF4AD98C)          // hover
val PrimaryDim = Color(0xFF1F9E63)            // pressed / de-emphasized
val PrimaryContainer = Color(0xFF2AD178).copy(alpha = 0.12f) // --tint
val OnPrimaryContainer = Color(0xFF2AD178)
val PrimaryInk = Color(0xFF07130C)            // --onp
val PrimaryOutline = Color(0xFF1F8A5C)

// ── Secondary / tertiary accents (Haven blue / amber) ──────────────────────
val AccentCyan = Color(0xFF5AB8F0)
val AccentCyanContainer = Color(0xFF5AB8F0).copy(alpha = 0.12f)
val AccentTeal = Color(0xFF5AB8F0)
val AccentTealContainer = Color(0xFF5AB8F0).copy(alpha = 0.12f)

// ── Reference accents (reserved) ────────────────────────────────────────────
val ReferenceProotGreen = Color(0xFF2AD178)
val ReferenceSlateCyan = Color(0xFF5AB8F0)
val ReferenceEmberRust = Color(0xFFF28B54)
val ReferenceTuxBlue = Color(0xFF3873B3)
val ReferenceTeal = Color(0xFF34A853)

// ── Terminal palette (always dark in both Haven themes) ────────────────────
val TerminalBackground = Color(0xFF0A0E0F)    // --term
val TerminalForeground = Color(0xFFC9CED4)
val TerminalBlack = Color(0xFF1E2024)
val TerminalRed = Color(0xFFF87171)
val TerminalGreen = Color(0xFF2AD178)
val TerminalDarkGreen = Color(0xFF1F9E63)
val TerminalYellow = Color(0xFFFBBF24)
val TerminalBlue = Color(0xFF5AB8F0)
val TerminalMagenta = Color(0xFFC17ECF)
val TerminalCyan = Color(0xFF5CDED4)
val TerminalWhite = Color(0xFFC9CED4)
val TerminalBrightBlack = Color(0xFF697077)   // --t-dim
val TerminalBrightRed = Color(0xFFFF8C87)
val TerminalBrightGreen = Color(0xFF7EE0A8)   // --tp prompt green
val TerminalBrightYellow = Color(0xFFFFD95C)
val TerminalBrightBlue = Color(0xFF6BD4FF)
val TerminalBrightMagenta = Color(0xFFD7A6DE)
val TerminalBrightCyan = Color(0xFF5CDED4)
val TerminalBrightWhite = Color(0xFFF2F3F5)
val TerminalCursor = Color(0xFFD7DBE0)
val TerminalSelection = Color(0xFF1F8A5C)

// ── Status palette (Haven semantic colors) ─────────────────────────────────
val StatusRunning = Color(0xFF2AD178)         // --primary
val StatusInstalling = Color(0xFFFBBF24)      // --amber
val StatusDownloading = Color(0xFF5AB8F0)     // --blue
val StatusStarting = Color(0xFF5AB8F0)        // --blue
val StatusStopping = Color(0xFF6C7178)        // --muted
val StatusStopped = Color(0xFF6C7178)         // --muted
val StatusNotInstalled = Color(0xFF6C7178)    // --muted
val StatusWarning = Color(0xFFFBBF24)         // --amber
val StatusError = Color(0xFFF87171)           // --red
val StatusDegraded = Color(0xFFFB923C)
val StatusDisconnected = Color(0xFF6C7178)
val StatusSuccess = Color(0xFF2AD178)

// ── Status containers (tints) ───────────────────────────────────────────────
val RunningContainer = Color(0xFF2AD178).copy(alpha = 0.12f)
val InstallingContainer = Color(0xFFFBBF24).copy(alpha = 0.12f)
val DownloadingContainer = Color(0xFF5AB8F0).copy(alpha = 0.12f)
val StartingContainer = Color(0xFF5AB8F0).copy(alpha = 0.12f)
val StoppedContainer = Color(0xFF1E2024)
val NotInstalledContainer = Color(0xFF1E2024)
val WarningContainer = Color(0xFFFBBF24).copy(alpha = 0.12f)
val ErrorContainer = Color(0xFFF87171).copy(alpha = 0.12f)
val OnErrorContainer = Color(0xFFF87171)
val DegradedContainer = Color(0xFFFB923C).copy(alpha = 0.12f)
val DisconnectedContainer = Color(0xFF1E2024)

// ── Text scale ──────────────────────────────────────────────────────────────
val TextPrimary = Color(0xFFF2F3F5)           // --text
val TextSecondary = Color(0xFFA8ADB4)         // --text2
val TextMuted = Color(0xFF6C7178)             // --muted
val TextDisabled = Color(0xFF4A5058)
val TextHint = Color(0xFF6C7178)

// ── Cards ───────────────────────────────────────────────────────────────────
val CardBackground = Color(0xFF17181B)        // --surface
val CardElevated = Color(0xFF1E2024)          // --surface2
val CardHover = Color(0xFF1E2024)
val CardBorder = Color.White.copy(alpha = 0.06f)
val CardActive = Color(0xFF2AD178)

// ── Buttons ─────────────────────────────────────────────────────────────────
val ButtonPrimary = Primary
val ButtonPrimaryHover = PrimaryMuted
val ButtonPrimaryPressed = PrimaryDim
val ButtonPrimaryInk = PrimaryInk
val ButtonSecondary = Color.Transparent       // transparent + hairline border
val ButtonBorder = Border
val ButtonDanger = StatusError
val ButtonDangerHover = Color(0xFFFF8C87)
val ButtonDangerInk = Color(0xFF1A0A0B)
val ButtonSuccess = StatusSuccess
val ButtonGhost = Color.Transparent

// ── Charts (Haven: CPU green / RAM blue / storage amber / net blue) ────────
val ChartCpu = Color(0xFF2AD178)
val ChartRam = Color(0xFF5AB8F0)
val ChartStorage = Color(0xFFFBBF24)
val ChartNetwork = Color(0xFF5AB8F0)
val ChartTemp = Color(0xFFFBBF24)
val ChartGrid = Color.White.copy(alpha = 0.06f)
val ChartAxis = Color(0xFF6C7178)

// ── Gradients (Haven is flat — single-tone lists kept for API compat) ──────
val PrimaryGradient = listOf(PrimaryDim, Primary, PrimaryMuted)
val StatusGradient = listOf(RunningContainer, Primary)
val HeroGradient = listOf(Background, Surface)
val AccentGradient = listOf(AccentTeal, AccentCyan)
val ErrorGradient = listOf(ErrorContainer, StatusError)

// ── Light theme ─────────────────────────────────────────────────────────────
val LightBackground = Color(0xFFF7F7F5)
val LightSurfaceCard = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0EFEC)
val LightOnBackground = Color(0xFF1A1C1E)
val LightOnSurface = Color(0xFF1A1C1E)
val LightOnSurfaceVariant = Color(0xFF5C6167)
val LightOutline = Color.Black.copy(alpha = 0.07f)
val LightOutlineVariant = Color.Black.copy(alpha = 0.05f)
val LightPrimaryContainer = Color(0xFF0E9F5D).copy(alpha = 0.10f)
val LightOnPrimaryContainer = Color(0xFF0E9F5D)
val LightSecondary = Color(0xFF3873B3)
val LightOnSecondary = Color.White
val LightTertiary = Color(0xFF3873B3)
val LightOnTertiary = Color(0xFF3873B3)
val LightErrorContainer = Color(0xFFD93025).copy(alpha = 0.09f)
val LightOnErrorContainer = Color(0xFFD93025)
val LightAccent = Color(0xFF0E9F5D)
// The terminal stays dark in the light theme (the concept's `.light` block
// deliberately does not override --term).
val LightTerminalBackground = Color(0xFF0A0E0F)
val LightTerminalForeground = Color(0xFFC9CED4)

// ── Distro identity (concept glyph colors) ─────────────────────────────────
val DistroOrange = Color(0xFFE95420)          // Ubuntu
val DistroBlue = Color(0xFF1793D1)            // Arch
val DistroRed = Color(0xFFA80030)             // Debian
val DistroPurple = Color(0xFF7B5FE0)
val DistroTeal = Color(0xFF3FB89C)

// ── Semantic design tokens ─────────────────────────────────────────────────
// Static defaults for non-composable contexts (component default parameters,
// ViewModel fallbacks). Composables must prefer HavenTheme colors or
// MaterialTheme.colorScheme — this object is NOT a parallel palette.
object LinexaColors {
    // Brand primary — equals HavenColors.darkPalette().primary (#2AD178).
    val Primary = com.fem.linexa.ui.theme.Primary

    // Status amber — no M3 error/success-equivalent slot.
    val Warning = StatusWarning

    // Text tones without an M3 slot / used as static defaults.
    val TextMuted = com.fem.linexa.ui.theme.TextMuted
    val TextSecondary = com.fem.linexa.ui.theme.TextSecondary

    // Raised-surface track default for progress rails.
    val Surface3 = SurfaceHover

    // Decorative accent used by resource tiles.
    val Accent = ReferenceProotGreen

    // Sparkline palette — CPU / RAM / Disk / Net (Haven mapping).
    val Cpu = ChartCpu
    val Ram = ChartRam
    val Disk = ChartStorage
    val Net = ChartNetwork

    // Gradients
    val PrimaryGradient = listOf(PrimaryDim, com.fem.linexa.ui.theme.Primary, PrimaryMuted)
    val AccentGradient = listOf(AccentTeal, AccentCyan)
}
