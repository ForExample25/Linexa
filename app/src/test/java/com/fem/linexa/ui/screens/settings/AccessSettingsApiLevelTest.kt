package com.fem.linexa.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §1.105 / UI/UX Phase 4 (§1.132): pure unit tests for the API-gating predicates behind
 * the Access rows, which moved from the Workspace Settings tab to Global Settings along
 * with the rows themselves.
 *
 * These are the exact thresholds that decide which permission flow applies per device
 * API level:
 *
 *  - File Access uses MANAGE_EXTERNAL_STORAGE / "All files access" only on API 30+ (the
 *    mechanism does not exist before R).
 *  - Notifications uses the POST_NOTIFICATIONS runtime permission only on API 33+
 *    (pre-33 the platform auto-grants it, so requesting would be wrong and checking is
 *    unnecessary).
 *
 * Battery optimization (`PowerManager.isIgnoringBatteryOptimizations`, API 23) is below
 * the app's minSdk (28) and needs no gate, so it is not tested here.
 */
class AccessSettingsApiLevelTest {

    @Test
    fun fileAccessSupportedOnlyOnApi30Plus() {
        for (sdk in listOf(26, 27, 28, 29)) {
            assertEquals("fileAccessSupported($sdk)", false, fileAccessSupported(sdk))
        }
        for (sdk in listOf(30, 31, 32, 33, 34)) {
            assertEquals("fileAccessSupported($sdk)", true, fileAccessSupported(sdk))
        }
    }

    @Test
    fun notificationsPermissionRequiredOnlyOnApi33Plus() {
        for (sdk in listOf(26, 27, 28, 29, 30, 31, 32)) {
            assertEquals(
                "notificationsPermissionRequired($sdk)",
                false,
                notificationsPermissionRequired(sdk)
            )
        }
        for (sdk in listOf(33, 34)) {
            assertEquals(
                "notificationsPermissionRequired($sdk)",
                true,
                notificationsPermissionRequired(sdk)
            )
        }
    }

    @Test
    fun theAppsOwnMinSdkIsCoveredByBothPredicates() {
        // minSdk is 28: the file-access mechanism does not exist there and notifications
        // are auto-granted, so the *lowest supported device* must take the fallback path
        // in both cases. This is the combination a real minSdk device runs, and it is the
        // one a naive `>= 23`-style gate would get wrong.
        assertEquals(false, fileAccessSupported(28))
        assertEquals(false, notificationsPermissionRequired(28))
    }
}
