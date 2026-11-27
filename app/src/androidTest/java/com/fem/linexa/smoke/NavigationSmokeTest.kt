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
 * 10B (OPT-TEST-002) smoke row 2: the three top-level Haven tabs (see
 * `HavenDestination` — WORKSPACES / ACTIVITY / SETTINGS) each render their own
 * surface and the navigation round-trips back to Workspaces.
 *
 * Tab clicks go through the bottom-nav icons' contentDescription (exactly the
 * label strings "Workspaces"/"Activity"/"Settings" from `navItems()`); arrival
 * is asserted through strings that are UNIQUE to each screen so a stale
 * start-destination frame cannot satisfy them: "Resources" only exists on the
 * Activity screen (`activity_section_resources`), "Appearance" only in the
 * Settings content (`global_settings_appearance_group`), and the "Add a
 * Workspace" icon button (`home_add_workspace`) only on Workspaces.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theThreeHavenTabsRenderAndRoundTrip() {
        rule.onNodeWithContentDescription("Add a Workspace").assertExists()

        rule.onNodeWithContentDescription("Activity").performClick()
        rule.onNodeWithText("Resources").assertExists()

        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Appearance").assertExists()

        rule.onNodeWithContentDescription("Workspaces").performClick()
        rule.onNodeWithContentDescription("Add a Workspace").assertExists()
    }
}
