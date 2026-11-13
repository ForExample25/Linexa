package com.fem.linexa.domain.settings

import android.view.KeyEvent

/**
 * App-scoped terminal UI preferences: how Recent Sessions is arranged and which
 * shortcuts sit above the keyboard.
 *
 * Both settings are global, not per workspace: a user who prefers a
 * single-card recents view prefers it for every workspace, and the shortcut
 * bar is app chrome, not guest configuration. Persistence lives in
 * [SettingsRepository] / `linexa_settings` like every other app-scoped value.
 *
 * The types in this file are deliberately Android-keycode-bearing but otherwise
 * free of Compose: the terminal adapter is the only component allowed to turn a
 * [TerminalShortcut] into a `KeyEvent`, while the settings screen and the
 * toolbar render the same enum. One source of truth, so a shortcut cannot be
 * shown in the picker and then fail to dispatch.
 */
enum class RecentSessionsLayout(
    val key: String,
    val displayName: String
) {
    /**
     * Launcher-style two-column grid on phones. Cards are sized from width
     * only; vertical scrolling reveals more rows.
     */
    VERTICAL("vertical", "Vertically"),

    /**
     * One large session card per page, paged horizontally. The final page is
     * the New Session action.
     */
    HORIZONTAL("horizontal", "Horizontally");

    companion object {
        val DEFAULT: RecentSessionsLayout = VERTICAL

        /** Parses a persisted key; unknown/absent values fall back to [DEFAULT]. */
        fun fromKey(value: String?): RecentSessionsLayout =
            entries.firstOrNull { it.key == value } ?: DEFAULT
    }
}

/** Which of the shortcut toolbar presets is active. */
enum class TerminalToolbarPreset(
    val key: String,
    val displayName: String
) {
    COMPACT("compact", "Compact"),
    NAVIGATION("navigation", "Navigation"),
    DEVELOPER("developer", "Developer"),
    SHELL("shell", "Shell"),

    /**
     * The user's own configuration. Editing any built-in preset converts the
     * active toolbar to Custom while leaving the built-in presets canonical.
     */
    CUSTOM("custom", "Custom");

    companion object {
        val DEFAULT: TerminalToolbarPreset = COMPACT

        fun fromKey(value: String?): TerminalToolbarPreset =
            entries.firstOrNull { it.key == value } ?: DEFAULT
    }
}

/** One or two shortcut rows above the keyboard. */
enum class TerminalToolbarRowMode(
    val key: String,
    val displayName: String
) {
    SINGLE("single", "Single Row"),
    DOUBLE("double", "Double Row");

    companion object {
        val DEFAULT: TerminalToolbarRowMode = SINGLE

        fun fromKey(value: String?): TerminalToolbarRowMode =
            entries.firstOrNull { it.key == value } ?: DEFAULT
    }
}

/** The sticky modifier a shortcut arms rather than dispatches. */
enum class TerminalShortcutModifier {
    CONTROL,
    ALT,
    SHIFT
}

/**
 * Every shortcut the terminal can actually dispatch.
 *
 * The set is intentionally closed: it is exactly the input surface
 * [com.fem.linexa.ui.screens.terminal.TerminalHandle] supports. Exposing an
 * unsupported key in Settings would create a button that looks real and does
 * nothing, so new entries must be added here together with their dispatch.
 *
 * [metaCtrl] shortcuts are sent as a real Android `KeyEvent` carrying
 * `META_CTRL_ON`; the vendored terminal turns that into the corresponding
 * control byte (`^C`, `^D`, `^Z`). They are not literal text macros.
 */
enum class TerminalShortcut(
    val key: String,
    val label: String,
    val description: String,
    val keyCode: Int?,
    val modifier: TerminalShortcutModifier? = null,
    val metaCtrl: Boolean = false
) {
    ESCAPE("escape", "Esc", "Escape", KeyEvent.KEYCODE_ESCAPE),
    TAB("tab", "Tab", "Tab", KeyEvent.KEYCODE_TAB),
    CTRL("ctrl", "Ctrl", "Control", keyCode = null, modifier = TerminalShortcutModifier.CONTROL),
    ALT("alt", "Alt", "Alt", keyCode = null, modifier = TerminalShortcutModifier.ALT),
    SHIFT("shift", "Shift", "Shift", keyCode = null, modifier = TerminalShortcutModifier.SHIFT),
    ENTER("enter", "Enter", "Enter", KeyEvent.KEYCODE_ENTER),
    ARROW_UP("arrow_up", "↑", "Arrow up", KeyEvent.KEYCODE_DPAD_UP),
    ARROW_DOWN("arrow_down", "↓", "Arrow down", KeyEvent.KEYCODE_DPAD_DOWN),
    ARROW_LEFT("arrow_left", "←", "Arrow left", KeyEvent.KEYCODE_DPAD_LEFT),
    ARROW_RIGHT("arrow_right", "→", "Arrow right", KeyEvent.KEYCODE_DPAD_RIGHT),
    HOME("home", "Home", "Home", KeyEvent.KEYCODE_MOVE_HOME),
    END("end", "End", "End", KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("page_up", "PgUp", "Page up", KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("page_down", "PgDn", "Page down", KeyEvent.KEYCODE_PAGE_DOWN),
    CTRL_C("ctrl_c", "^C", "Control C", KeyEvent.KEYCODE_C, metaCtrl = true),
    CTRL_D("ctrl_d", "^D", "Control D", KeyEvent.KEYCODE_D, metaCtrl = true),
    CTRL_Z("ctrl_z", "^Z", "Control Z", KeyEvent.KEYCODE_Z, metaCtrl = true);

    val isModifier: Boolean get() = modifier != null

    companion object {
        fun fromKey(value: String?): TerminalShortcut? =
            entries.firstOrNull { it.key == value }
    }
}

/**
 * A resolved toolbar: the preset/row mode plus the concrete rows to paint.
 * This is what the real bar and every preview render, so a preview cannot
 * diverge from the bar.
 */
data class TerminalToolbarConfig(
    val preset: TerminalToolbarPreset,
    val rowMode: TerminalToolbarRowMode,
    val row1: List<TerminalShortcut>,
    val row2: List<TerminalShortcut>
) {
    /** The rows the bar actually paints (row 2 only in double-row mode). */
    val rows: List<List<TerminalShortcut>>
        get() = if (rowMode == TerminalToolbarRowMode.SINGLE) listOf(row1) else listOf(row1, row2)

    val isDoubleRow: Boolean get() = rowMode == TerminalToolbarRowMode.DOUBLE
}

/**
 * The full persisted toolbar state.
 *
 * Built-in preset content is derived, never copied into the saved custom rows
 * until the user edits it. That is what keeps "Compact" meaning compact after a
 * user has customized their own bar, while `customRow1/2` still preserve the
 * edit when they switch back to Custom.
 */
data class TerminalToolbarSettings(
    val preset: TerminalToolbarPreset = TerminalToolbarPreset.DEFAULT,
    val rowMode: TerminalToolbarRowMode = TerminalToolbarRowMode.DEFAULT,
    val customRow1: List<TerminalShortcut> = COMPACT_ROW_1,
    val customRow2: List<TerminalShortcut> = COMPACT_ROW_2
) {

    /** The active bar configuration. */
    val config: TerminalToolbarConfig
        get() = configFor(preset)

    /** The configuration that preset would apply, without changing this state. */
    fun configFor(preset: TerminalToolbarPreset): TerminalToolbarConfig {
        val (row1, row2) = rowsFor(preset)
        return TerminalToolbarConfig(
            preset = preset,
            rowMode = rowMode,
            row1 = sanitizeRow(row1),
            row2 = sanitizeRow(row2)
        )
    }

    private fun rowsFor(preset: TerminalToolbarPreset): Pair<List<TerminalShortcut>, List<TerminalShortcut>> =
        when (preset) {
            TerminalToolbarPreset.CUSTOM -> {
                val first = sanitizeRow(customRow1)
                val second = sanitizeRow(customRow2)
                // A user can empty one row; an entirely empty bar is never a
                // useful state, so fall back to the compact defaults.
                if (first.isEmpty() && second.isEmpty()) COMPACT_ROW_1 to COMPACT_ROW_2
                else first to second
            }
            else -> presetRows(preset)
        }

    /**
     * Repairs a decoded/persisted state: de-duplicates each row, caps its
     * length, and guarantees an entirely empty custom bar falls back to the
     * compact defaults.
     */
    fun sanitized(): TerminalToolbarSettings {
        val first = sanitizeRow(customRow1)
        val second = sanitizeRow(customRow2)
        val repaired = if (first.isEmpty() && second.isEmpty()) {
            copy(customRow1 = COMPACT_ROW_1, customRow2 = COMPACT_ROW_2)
        } else {
            copy(customRow1 = first, customRow2 = second)
        }
        return repaired
    }

    companion object {
        /** Practical cap: more than this is unusable even with horizontal scrolling. */
        const val MAX_ROW_SIZE = 12

        val COMPACT_ROW_1: List<TerminalShortcut> = listOf(
            TerminalShortcut.ESCAPE,
            TerminalShortcut.TAB,
            TerminalShortcut.CTRL,
            TerminalShortcut.ARROW_LEFT,
            TerminalShortcut.ARROW_UP,
            TerminalShortcut.ARROW_DOWN,
            TerminalShortcut.ARROW_RIGHT
        )

        val COMPACT_ROW_2: List<TerminalShortcut> = listOf(
            TerminalShortcut.CTRL_C,
            TerminalShortcut.CTRL_D,
            TerminalShortcut.ENTER,
            TerminalShortcut.HOME,
            TerminalShortcut.END
        )

        val NAVIGATION_ROW_1: List<TerminalShortcut> = listOf(
            TerminalShortcut.ARROW_LEFT,
            TerminalShortcut.ARROW_UP,
            TerminalShortcut.ARROW_DOWN,
            TerminalShortcut.ARROW_RIGHT,
            TerminalShortcut.HOME,
            TerminalShortcut.END,
            TerminalShortcut.PAGE_UP,
            TerminalShortcut.PAGE_DOWN
        )

        val NAVIGATION_ROW_2: List<TerminalShortcut> = listOf(
            TerminalShortcut.ESCAPE,
            TerminalShortcut.TAB,
            TerminalShortcut.CTRL,
            TerminalShortcut.ENTER
        )

        val DEVELOPER_ROW_1: List<TerminalShortcut> = listOf(
            TerminalShortcut.ESCAPE,
            TerminalShortcut.TAB,
            TerminalShortcut.CTRL,
            TerminalShortcut.ALT,
            TerminalShortcut.CTRL_C,
            TerminalShortcut.CTRL_D,
            TerminalShortcut.CTRL_Z
        )

        val DEVELOPER_ROW_2: List<TerminalShortcut> = listOf(
            TerminalShortcut.ARROW_LEFT,
            TerminalShortcut.ARROW_RIGHT,
            TerminalShortcut.HOME,
            TerminalShortcut.END,
            TerminalShortcut.PAGE_UP,
            TerminalShortcut.PAGE_DOWN,
            TerminalShortcut.ENTER
        )

        val SHELL_ROW_1: List<TerminalShortcut> = listOf(
            TerminalShortcut.ESCAPE,
            TerminalShortcut.TAB,
            TerminalShortcut.CTRL_C,
            TerminalShortcut.CTRL_D,
            TerminalShortcut.CTRL_Z,
            TerminalShortcut.ENTER
        )

        val SHELL_ROW_2: List<TerminalShortcut> = listOf(
            TerminalShortcut.CTRL,
            TerminalShortcut.ALT,
            TerminalShortcut.SHIFT,
            TerminalShortcut.HOME,
            TerminalShortcut.END
        )

        val DEFAULT: TerminalToolbarSettings = TerminalToolbarSettings()

        fun presetRows(preset: TerminalToolbarPreset): Pair<List<TerminalShortcut>, List<TerminalShortcut>> =
            when (preset) {
                TerminalToolbarPreset.COMPACT -> COMPACT_ROW_1 to COMPACT_ROW_2
                TerminalToolbarPreset.NAVIGATION -> NAVIGATION_ROW_1 to NAVIGATION_ROW_2
                TerminalToolbarPreset.DEVELOPER -> DEVELOPER_ROW_1 to DEVELOPER_ROW_2
                TerminalToolbarPreset.SHELL -> SHELL_ROW_1 to SHELL_ROW_2
                TerminalToolbarPreset.CUSTOM -> COMPACT_ROW_1 to COMPACT_ROW_2
            }

        fun sanitizeRow(row: List<TerminalShortcut>): List<TerminalShortcut> =
            row.distinct().take(MAX_ROW_SIZE)

        /** Stable comma-separated persistence encoding. Unknown keys are dropped. */
        fun encodeRow(row: List<TerminalShortcut>): String =
            sanitizeRow(row).joinToString(",") { it.key }

        /** Decodes a persisted row; absent/garbage values produce an empty row. */
        fun decodeRow(value: String?): List<TerminalShortcut> {
            if (value.isNullOrBlank()) return emptyList()
            return sanitizeRow(
                value.split(',')
                    .mapNotNull { TerminalShortcut.fromKey(it.trim()) }
            )
        }

        /** Decodes the four persisted values into a repaired settings snapshot. */
        fun decode(
            presetKey: String?,
            rowModeKey: String?,
            customRow1: String?,
            customRow2: String?
        ): TerminalToolbarSettings = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.fromKey(presetKey),
            rowMode = TerminalToolbarRowMode.fromKey(rowModeKey),
            customRow1 = decodeRow(customRow1),
            customRow2 = decodeRow(customRow2)
        ).sanitized()
    }
}

/**
 * Pure edit operations for the Custom toolbar. Every mutation converts the
 * active configuration to [TerminalToolbarPreset.CUSTOM] while seeding the
 * custom rows from the preset being edited — the "edit a built-in → becomes
 * Custom, built-ins stay canonical" model.
 */
object TerminalToolbarEditing {

    fun selectPreset(
        settings: TerminalToolbarSettings,
        preset: TerminalToolbarPreset
    ): TerminalToolbarSettings = settings.copy(preset = preset)

    fun setRowMode(
        settings: TerminalToolbarSettings,
        mode: TerminalToolbarRowMode
    ): TerminalToolbarSettings = settings.copy(rowMode = mode)

    /** Adds [shortcut] to [rowIndex] unless it already exists in either row. */
    fun add(
        settings: TerminalToolbarSettings,
        rowIndex: Int,
        shortcut: TerminalShortcut
    ): TerminalToolbarSettings {
        val editable = editable(settings)
        if (rowIndex !in 0..1) return editable
        if (shortcut in editable.customRow1 || shortcut in editable.customRow2) return editable
        val target = if (rowIndex == 0) editable.customRow1 else editable.customRow2
        if (target.size >= TerminalToolbarSettings.MAX_ROW_SIZE) return editable
        return editable.copy(
            customRow1 = if (rowIndex == 0) target + shortcut else editable.customRow1,
            customRow2 = if (rowIndex == 1) target + shortcut else editable.customRow2
        )
    }

    fun remove(
        settings: TerminalToolbarSettings,
        rowIndex: Int,
        shortcut: TerminalShortcut
    ): TerminalToolbarSettings {
        val editable = editable(settings)
        return when (rowIndex) {
            0 -> editable.copy(customRow1 = editable.customRow1 - shortcut)
            1 -> editable.copy(customRow2 = editable.customRow2 - shortcut)
            else -> editable
        }
    }

    /** Moves an entry one position within its row. Edge moves are no-ops. */
    fun move(
        settings: TerminalToolbarSettings,
        rowIndex: Int,
        index: Int,
        delta: Int
    ): TerminalToolbarSettings {
        val editable = editable(settings)
        val row = if (rowIndex == 0) editable.customRow1 else editable.customRow2
        val target = index + delta
        if (rowIndex !in 0..1 || index !in row.indices || target !in row.indices) return editable
        val moved = row.toMutableList().apply {
            add(target, removeAt(index))
        }
        return if (rowIndex == 0) editable.copy(customRow1 = moved)
        else editable.copy(customRow2 = moved)
    }

    /**
     * Moves an entry to the end of the other row. If the other row already
     * contains it (which the add rule prevents) or is full, this is a no-op.
     */
    fun assignToOtherRow(
        settings: TerminalToolbarSettings,
        rowIndex: Int,
        shortcut: TerminalShortcut
    ): TerminalToolbarSettings {
        if (rowIndex !in 0..1) return settings
        val editable = editable(settings)
        val source = if (rowIndex == 0) editable.customRow1 else editable.customRow2
        if (shortcut !in source) return editable
        val otherRow = if (rowIndex == 0) editable.customRow2 else editable.customRow1
        if (shortcut in otherRow || otherRow.size >= TerminalToolbarSettings.MAX_ROW_SIZE) {
            return editable
        }
        return if (rowIndex == 0) {
            editable.copy(
                customRow1 = editable.customRow1 - shortcut,
                customRow2 = editable.customRow2 + shortcut
            )
        } else {
            editable.copy(
                customRow1 = editable.customRow1 + shortcut,
                customRow2 = editable.customRow2 - shortcut
            )
        }
    }

    fun restoreDefaults(): TerminalToolbarSettings = TerminalToolbarSettings.DEFAULT

    /**
     * Seeds the editable custom rows from the active preset the first time a
     * built-in is changed. Once the preset is CUSTOM the saved rows are used.
     */
    private fun editable(settings: TerminalToolbarSettings): TerminalToolbarSettings {
        if (settings.preset == TerminalToolbarPreset.CUSTOM) return settings.sanitized()
        val (row1, row2) = TerminalToolbarSettings.presetRows(settings.preset)
        return settings.copy(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = TerminalToolbarSettings.sanitizeRow(row1),
            customRow2 = TerminalToolbarSettings.sanitizeRow(row2)
        )
    }
}
