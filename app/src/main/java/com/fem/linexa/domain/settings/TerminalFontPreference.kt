package com.fem.linexa.domain.settings

import android.content.Context
import android.util.TypedValue

/**
 * The terminal's own font size — one set of rules the terminal itself obeys,
 * persisted **terminal-locally** (`linexa_terminal`/`font_size`, deliberately
 * not the app-settings file) and applied by the terminal adapter
 * (`RealTerminalView`), not by an application appearance layer.
 *
 * 2026-08-29 terminal migration: this store used to sit underneath a real
 * appearance layer — a `TerminalAppearanceViewModel` flow, a
 * `SettingsRepository` font API and a Global Settings row all mediated the
 * value. That layer is gone: the terminal owns its font (pinch gesture and
 * the Haven ⋯ sheet both land here through the terminal adapter), matching
 * how a real terminal emulator such as Termux keeps its font configuration
 * terminal-local. What remains is exactly the store + the clamp rules.
 *
 * The values are deliberately identical to the ones the view has always used
 * (default 18, range 8–96, step 2), so an existing install's stored size keeps
 * working and pinch behaviour is unchanged.
 *
 * ### Unit: stored dp, applied px
 * The stored unit is **dp**. `TerminalRenderer` feeds its size straight into
 * `Paint.setTextSize`, which is **pixels** (the vendored `TerminalView.setTextSize`
 * javadoc claiming "density-independent pixels" is wrong upstream — Termux's own
 * app stores `12dp * density` as a px int for the same reason). The adapter
 * converts through [toPx] / [fromPx] at apply time, so the stored value stays
 * density-independent and the rendered size matches the same nominal dp on any
 * device.
 */
object TerminalFontPreference {

    /** Default font size in dp. Matches the pre-migration `RealTerminalView` default. */
    const val DEFAULT_DP: Int = 18

    /** Smallest allowed size in dp. Below this the emulator is unreadable. */
    const val MIN_DP: Int = 8

    /**
     * Largest allowed size in dp.
     *
     * Generous on purpose (an accessibility need is a real reason to want a very
     * large terminal), and capped at all because `TerminalView` derives its column
     * count from the size — an unbounded value yields a one-column terminal that
     * no shell output can be read in.
     */
    const val MAX_DP: Int = 96

    /**
     * One step of change, in dp, shared by the pinch gesture and the ⋯ sheet
     * slider.
     *
     * 2dp rather than 1: `TerminalView` re-measures its grid on every change, and
     * a 1dp step makes a whole tap or pinch produce a change that is easy to miss.
     */
    const val STEP_DP: Int = 2

    /**
     * Coerce any stored or incoming value into the allowed range.
     *
     * Applied on read as well as on write, deliberately. A value already on disk
     * may predate the current bounds (or have been written by a different build),
     * and the read path is what the terminal actually renders with.
     */
    fun sanitize(sizeDp: Int): Int = sizeDp.coerceIn(MIN_DP, MAX_DP)

    /** One step larger, clamped. Returns the same value when already at [MAX_DP]. */
    fun increase(sizeDp: Int): Int = sanitize(sanitize(sizeDp) + STEP_DP)

    /** One step smaller, clamped. Returns the same value when already at [MIN_DP]. */
    fun decrease(sizeDp: Int): Int = sanitize(sanitize(sizeDp) - STEP_DP)

    /** Whether [increase] would change anything — drives the stepper's `+` enabled state. */
    fun canIncrease(sizeDp: Int): Boolean = sanitize(sizeDp) < MAX_DP

    /** Whether [decrease] would change anything — drives the stepper's `−` enabled state. */
    fun canDecrease(sizeDp: Int): Boolean = sanitize(sizeDp) > MIN_DP

    /** Stored dp → px for `TerminalView.setTextSize` (`Paint.setTextSize` is px). */
    fun toPx(sizeDp: Int, context: Context): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        sanitize(sizeDp).toFloat(),
        context.resources.displayMetrics
    ).toInt()

    /** `TerminalRenderer` px size (package-private upstream) → the dp a caller stored. */
    fun fromPx(sizePx: Int, context: Context): Int = sanitize(
        (sizePx / context.resources.displayMetrics.density).toInt()
    )

    /**
     * The prefs file the size lives in.
     *
     * Deliberately **not** the app-settings file (`linexa_settings`). The size was
     * already written here before the migration, and pointing the setting at a
     * different file would have silently reset every existing user's font size to
     * the default — a migration with no upside for a value the user chose by
     * pinching.
     */
    const val PREFS_FILE: String = "linexa_terminal"

    /** The key within [PREFS_FILE]. Same key the terminal adapter has always used. */
    const val PREFS_KEY: String = "font_size"

    /** Terminal-local read of the persisted size (falls back to [DEFAULT_DP]). */
    fun load(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        return sanitize(prefs.getInt(PREFS_KEY, DEFAULT_DP))
    }

    /** Terminal-local write of the size; the terminal adapter is the only writer. */
    fun save(context: Context, sizeDp: Int) {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREFS_KEY, sanitize(sizeDp)).apply()
    }
}
