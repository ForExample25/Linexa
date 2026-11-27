package com.fem.linexa.smoke

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fem.linexa.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 10B (OPT-TEST-002) smoke row 1: a cold `MainActivity` launch must reach the
 * home surface without crashing. A crash anywhere in composition, Hilt
 * injection, or the theme/view-model seeding surfaces either as a thrown
 * exception from [ActivityScenario.launch] or as an instrumentation process
 * crash — both fail this test, so the assertions stay deliberately thin: the
 * activity reached RESUMED and is not finishing. No compose test rule here on
 * purpose: launch smoke must not depend on the Compose idle synchronizer.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun aColdLaunchReachesTheHomeSurfaceWithoutACrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("a healthy home surface is not finishing", activity.isFinishing)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse("the home surface must still be alive after idle", activity.isFinishing)
            }
        }
    }
}
