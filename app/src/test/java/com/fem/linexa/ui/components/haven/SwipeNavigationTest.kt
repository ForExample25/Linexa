package com.fem.linexa.ui.components.haven

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the single horizontal-swipe engine used by primary destinations
 * and nested tab groups.
 *
 * The pure threshold function is JVM-testable; the pointer loop's conflict
 * contract (child consumption, vertical dominance, edge guard, IME gating) is
 * pinned in source because Compose UI testing is not set up in this repo.
 */
class SwipeNavigationTest {

    private lateinit var moduleRoot: File

    @Before
    fun locate() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String) =
        File(moduleRoot, "src/main/java/com/fem/linexa/$relative").readText()

    private fun resolve(
        totalX: Float,
        totalY: Float,
        velocityX: Float = 0f,
        touchSlop: Float = 8f,
        distance: Float = 64f,
        velocity: Float = 650f
    ) = resolveSwipeDirection(
        totalX = totalX,
        totalY = totalY,
        velocityX = velocityX,
        touchSlop = touchSlop,
        distanceThreshold = distance,
        velocityThreshold = velocity
    )

    // ── direction ─────────────────────────────────────────────────────────

    @Test
    fun draggingLeftAsksForTheNextDestination() {
        assertEquals(SwipeDirection.LEFT, resolve(totalX = -120f, totalY = 10f))
    }

    @Test
    fun draggingRightAsksForThePreviousDestination() {
        assertEquals(SwipeDirection.RIGHT, resolve(totalX = 120f, totalY = 10f))
    }

    @Test
    fun aTapNeverNavigates() {
        assertNull(resolve(totalX = 0f, totalY = 0f))
        assertNull(resolve(totalX = 20f, totalY = 2f))
    }

    // ── vertical and diagonal protection ─────────────────────────────────

    @Test
    fun verticalScrollingAlwaysWins() {
        assertNull(
            "a lazy list drag has little horizontal travel",
            resolve(totalX = 50f, totalY = 400f)
        )
        assertNull(
            "even at the distance threshold, vertical dominance rejects",
            resolve(totalX = 100f, totalY = 120f)
        )
    }

    @Test
    fun horizontalMustClearlyDominateDiagonalTravel() {
        assertNull(
            "1:1 diagonal is not a page swipe",
            resolve(totalX = 100f, totalY = 100f)
        )
        assertNull(
            "1.2:1 is still too diagonal",
            resolve(totalX = 120f, totalY = 100f)
        )
        assertEquals(
            "1.25:1 crosses the dominance rule",
            SwipeDirection.RIGHT,
            resolve(totalX = 130f, totalY = 100f)
        )
    }

    // ── distance and flick thresholds ────────────────────────────────────

    @Test
    fun aShortSlowDragIsRejected() {
        assertNull(resolve(totalX = 40f, totalY = 2f, velocityX = 100f))
    }

    @Test
    fun aShortFastFlickIsAccepted() {
        assertEquals(
            SwipeDirection.LEFT,
            resolve(totalX = -40f, totalY = 2f, velocityX = -900f)
        )
        assertEquals(
            SwipeDirection.RIGHT,
            resolve(totalX = 40f, totalY = 2f, velocityX = 900f)
        )
    }

    @Test
    fun aFastFlickShorterThanDoubleTouchSlopIsIgnored() {
        assertNull(resolve(totalX = 12f, totalY = 1f, velocityX = 4000f))
    }

    @Test
    fun theDistanceThresholdIsInclusive() {
        assertEquals(SwipeDirection.LEFT, resolve(totalX = -64f, totalY = 1f))
        assertNull(resolve(totalX = -63.9f, totalY = 1f))
    }

    // ── pointer-loop conflict contract ───────────────────────────────────

    @Test
    fun theEngineLetsChildrenConsumeFirstAndRejectsMultiTouch() {
        val engine = source("ui/components/haven/SwipeNavigation.kt")
        assertTrue("one gesture loop", engine.contains("awaitEachGesture {"))
        assertTrue(
            "a consumed child drag cancels the page swipe",
            engine.contains("change.isConsumed")
        )
        assertTrue("multi-touch is rejected", engine.contains("multiTouch"))
        assertTrue(
            "system edge gestures are guarded",
            engine.contains("edgeGuard")
        )
        assertTrue(
            "vertical travel cancels once it dominates",
            engine.contains("HORIZONTAL_DOMINANCE")
        )
        assertTrue(
            "page swipes are disabled while the IME is visible",
            engine.contains("WindowInsets.ime.getBottom(density) > 0")
        )
    }

    @Test
    fun primaryDestinationsShareTheSameHelper() {
        for (screen in listOf(
            "ui/screens/home/WorkspacesScreen.kt",
            "ui/screens/activity/ActivityScreen.kt",
            "ui/screens/settings/SettingsScreen.kt"
        )) {
            val text = source(screen)
            assertTrue("$screen must use the shared primary helper", text.contains(".havenTabSwipe("))
        }
        assertTrue(
            "the management terminal suppresses page swipes while open",
            source("ui/screens/home/WorkspacesScreen.kt").contains("enabled = !terminalOpen")
        )
    }

    @Test
    fun nestedTabGroupsUseTheSameEngine() {
        val workspace = source("ui/screens/workspace/WorkspaceScreen.kt")
        assertTrue(
            "workspace segments swipe through the shared engine",
            workspace.contains(".swipeNavigation(") &&
                workspace.contains("selectedSegment += 1") &&
                workspace.contains("selectedSegment -= 1")
        )
        val packages = source("ui/screens/packagemanager/PackageManagerScreen.kt")
        assertTrue(
            "package manager tabs swipe through the shared engine",
            packages.contains(".swipeNavigation(") &&
                packages.contains("if (tab < 1) tab = 1") &&
                packages.contains("if (tab > 0) tab = 0")
        )
        assertTrue(
            "package manager keeps one scroll state per tab",
            packages.contains("val installedListState = rememberLazyListState()") &&
                packages.contains("listState = installedListState")
        )
    }
}
