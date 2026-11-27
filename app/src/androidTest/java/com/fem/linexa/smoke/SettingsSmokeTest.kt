package com.fem.linexa.smoke

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fem.linexa.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 10B (OPT-TEST-002) smoke row 3: Settings opens from the top-level tabs and
 * renders a stable row. The stable anchors are the Appearance section header
 * (`global_settings_appearance_group`) and the "Keyboard toolbar" row
 * (`terminal_settings_toolbar_title`) from the 2026-11 settings pass — both
 * unconditional content, not state-dependent.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsShowsItsStableRows() {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Appearance").assertExists()
        rule.onNodeWithText("Keyboard toolbar").assertExists()
    }
}
