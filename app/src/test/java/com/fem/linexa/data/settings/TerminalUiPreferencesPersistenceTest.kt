package com.fem.linexa.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.settings.TerminalShortcut
import com.fem.linexa.domain.settings.TerminalToolbarPreset
import com.fem.linexa.domain.settings.TerminalToolbarRowMode
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Persistence for the two terminal UI preferences, without Robolectric.
 *
 * The repository only speaks [SharedPreferences], so an in-memory implementation
 * proves exactly what matters: every value is read back by stable key, and a
 * second repository instance (the next process) reconstructs the same state.
 * Keys are asserted as literals because they are an on-disk contract — renaming
 * one silently resets the user's toolbar.
 */
@RunWith(JUnit4::class)
class TerminalUiPreferencesPersistenceTest {

    private val stored = mutableMapOf<String, Any?>()
    private val prefs = InMemoryPreferences(stored)

    private fun repository() = SettingsRepositoryImpl(prefs.context)

    @Test
    fun freshInstallUsesTheProductDefaults() = runBlocking {
        val repository = repository()
        assertEquals(
            RecentSessionsLayout.VERTICAL,
            repository.observeRecentSessionsLayout().first()
        )
        assertEquals(
            TerminalToolbarSettings.DEFAULT,
            repository.observeTerminalToolbar().first()
        )
    }

    @Test
    fun chosenLayoutSurvivesANewRepositoryInstance() = runBlocking {
        repository().setRecentSessionsLayout(RecentSessionsLayout.HORIZONTAL)

        assertEquals(
            RecentSessionsLayout.HORIZONTAL,
            repository().observeRecentSessionsLayout().first()
        )
        assertEquals("horizontal", stored[KEY_LAYOUT])
    }

    @Test
    fun customToolbarSurvivesANewRepositoryInstanceExactly() = runBlocking {
        val saved = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            rowMode = TerminalToolbarRowMode.DOUBLE,
            customRow1 = listOf(
                TerminalShortcut.ESCAPE,
                TerminalShortcut.CTRL,
                TerminalShortcut.CTRL_C
            ),
            customRow2 = listOf(
                TerminalShortcut.ENTER,
                TerminalShortcut.PAGE_DOWN
            )
        )
        repository().setTerminalToolbar(saved)

        val restored = repository().observeTerminalToolbar().first()
        assertEquals(saved, restored)
        assertEquals(2, restored.config.rows.size)
        assertEquals(saved.customRow1, restored.config.row1)
        assertEquals(saved.customRow2, restored.config.row2)
    }

    @Test
    fun writingRepairsAnEmptyCustomToolbarBeforePersisting() = runBlocking {
        val empty = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = emptyList(),
            customRow2 = emptyList()
        )
        repository().setTerminalToolbar(empty)

        val restored = repository().observeTerminalToolbar().first()
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_1, restored.customRow1)
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_2, restored.customRow2)
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_1, restored.config.row1)
    }

    @Test
    fun theStableKeysAreTheOnDiskContract() = runBlocking {
        val repository = repository()
        repository.setRecentSessionsLayout(RecentSessionsLayout.HORIZONTAL)
        repository.setTerminalToolbar(
            TerminalToolbarSettings(
                preset = TerminalToolbarPreset.NAVIGATION,
                rowMode = TerminalToolbarRowMode.DOUBLE,
                customRow1 = TerminalToolbarSettings.NAVIGATION_ROW_1,
                customRow2 = TerminalToolbarSettings.NAVIGATION_ROW_2
            ).sanitized()
        )

        assertEquals("horizontal", stored[KEY_LAYOUT])
        assertEquals("navigation", stored[KEY_PRESET])
        assertEquals("double", stored[KEY_ROW_MODE])
        assertEquals(
            TerminalToolbarSettings.encodeRow(TerminalToolbarSettings.NAVIGATION_ROW_1),
            stored[KEY_CUSTOM_ROW_1]
        )
        assertEquals(
            TerminalToolbarSettings.encodeRow(TerminalToolbarSettings.NAVIGATION_ROW_2),
            stored[KEY_CUSTOM_ROW_2]
        )
    }

    @Test
    fun anUnknownPersistedLayoutFallsBackToTheDefault() = runBlocking {
        stored[KEY_LAYOUT] = "diagonal"
        assertEquals(
            RecentSessionsLayout.VERTICAL,
            repository().observeRecentSessionsLayout().first()
        )
    }

    private companion object {
        const val KEY_LAYOUT = "recent_sessions_layout"
        const val KEY_PRESET = "terminal_toolbar_preset"
        const val KEY_ROW_MODE = "terminal_toolbar_row_mode"
        const val KEY_CUSTOM_ROW_1 = "terminal_toolbar_custom_row_1"
        const val KEY_CUSTOM_ROW_2 = "terminal_toolbar_custom_row_2"
    }
}

/**
 * Minimal in-memory [SharedPreferences] plus the one [Context] call the
 * repository makes. Mocking the interfaces keeps this a plain JVM test (the
 * project's Robolectric baseline cannot load conscrypt on this host).
 */
private class InMemoryPreferences(
    private val values: MutableMap<String, Any?>
) {
    private val editor: SharedPreferences.Editor =
        mock(SharedPreferences.Editor::class.java).also { editor ->
            `when`(editor.putString(anyString(), nullable(String::class.java)))
                .thenAnswer { invocation ->
                    values[invocation.arguments[0] as String] = invocation.arguments[1] as String?
                    editor
                }
            `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer { invocation ->
                values[invocation.arguments[0] as String] = invocation.arguments[1] as Boolean
                editor
            }
            `when`(editor.putInt(anyString(), anyInt())).thenAnswer { invocation ->
                values[invocation.arguments[0] as String] = invocation.arguments[1] as Int
                editor
            }
            doNothing().`when`(editor).apply()
        }

    private val prefs: SharedPreferences =
        mock(SharedPreferences::class.java).also { prefs ->
            `when`(prefs.getString(anyString(), nullable(String::class.java)))
                .thenAnswer { invocation ->
                    values[invocation.arguments[0] as String] as String?
                        ?: invocation.arguments[1] as String?
                }
            `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { invocation ->
                values[invocation.arguments[0] as String] as Boolean?
                    ?: invocation.arguments[1] as Boolean
            }
            `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { invocation ->
                values[invocation.arguments[0] as String] as Int?
                    ?: invocation.arguments[1] as Int
            }
            `when`(prefs.edit()).thenReturn(editor)
        }

    val context: Context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
    }
}
