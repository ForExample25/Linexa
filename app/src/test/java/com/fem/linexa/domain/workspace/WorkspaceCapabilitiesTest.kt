package com.fem.linexa.domain.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [WorkspaceCapabilities] — the desktop-capability resolution
 * from persisted metadata (feature toggles / attributes). Defaults off; never
 * infers capability from a display name.
 */
class WorkspaceCapabilitiesTest {

    @Test
    fun defaultsToOffWhenNothingPersisted() {
        assertFalse(
            WorkspaceCapabilities.desktopEnabled(
                featureToggles = emptyMap(),
                attributes = emptyMap()
            )
        )
    }

    @Test
    fun enabledFromDesktopAttribute() {
        assertTrue(
            WorkspaceCapabilities.desktopEnabled(
                featureToggles = emptyMap(),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "true")
            )
        )
    }

    @Test
    fun disabledFromDesktopAttribute() {
        assertFalse(
            WorkspaceCapabilities.desktopEnabled(
                featureToggles = emptyMap(),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "false")
            )
        )
    }

    @Test
    fun toggleTrueWinsOverAttributeFalse() {
        // featureToggles are checked first — a user/config toggle is authoritative.
        assertTrue(
            WorkspaceCapabilities.desktopEnabled(
                featureToggles = mapOf("desktop" to true),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "false")
            )
        )
    }

    @Test
    fun toggleFalseWinsOverAttributeTrue() {
        assertFalse(
            WorkspaceCapabilities.desktopEnabled(
                featureToggles = mapOf("desktop" to false),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "true")
            )
        )
    }

    // ── 2026-09-02 desktop prerequisites (Phase 2): intent AND capability ──

    @Test
    fun desktopUsableRequiresBothIntentAndCapability() {
        // Intent without capability (toggle on, rootfs probe false) is the
        // exact fake-ready case Phase 2 removes — the button must stay off.
        assertFalse(
            WorkspaceCapabilities.desktopUsable(
                featureToggles = mapOf("desktop" to true),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "false")
            )
        )
        // Capability without intent is off too (the user switched it away).
        assertFalse(
            WorkspaceCapabilities.desktopUsable(
                featureToggles = mapOf("desktop" to false),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "true")
            )
        )
        // Both present → usable.
        assertTrue(
            WorkspaceCapabilities.desktopUsable(
                featureToggles = mapOf("desktop" to true),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "true")
            )
        )
    }

    @Test
    fun desktopUsableDefaultsOff() {
        assertFalse(
            WorkspaceCapabilities.desktopUsable(
                featureToggles = emptyMap(),
                attributes = emptyMap()
            )
        )
    }

    @Test
    fun desktopUsableHonorsIntentResolutionBeforeTheAnd() {
        // The intent half keeps desktopEnabled's resolution semantics
        // (toggle wins over the attribute-as-intent-fallback): an explicit
        // false toggle keeps the button off even when the capability attr
        // is true (same as desktopEnabled).
        assertFalse(
            WorkspaceCapabilities.desktopUsable(
                featureToggles = mapOf("desktop" to false),
                attributes = mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "true")
            )
        )
    }

    @Test
    fun desktopUsableWithoutACapabilityAttributeIsOff() {
        // The capability attribute is written at install/import time and
        // backfilled at startup for rows that predate it; while it is
        // absent there is no proof of capability, so the button stays off
        // ("never show a fake-ready state").
        assertFalse(
            WorkspaceCapabilities.desktopUsable(
                featureToggles = mapOf("desktop" to true),
                attributes = emptyMap()
            )
        )
    }
}
