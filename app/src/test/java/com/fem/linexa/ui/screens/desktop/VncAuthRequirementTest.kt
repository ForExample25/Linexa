package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** P11: an unreadable stored password must never be treated as "no password". */
class VncAuthRequirementTest {

    @Test
    fun aPasswordMeansPasswordAuth() {
        assertEquals(VncAuthRequirement.Password, DesktopVncAuthProvisioner.authRequirement("pw12345678", false))
        assertEquals(VncAuthRequirement.Password, DesktopVncAuthProvisioner.authRequirement("pw12345678", true))
    }

    @Test
    fun emptyAndReadableIsTheUsersChoiceOfNoAuth() {
        assertEquals(VncAuthRequirement.None, DesktopVncAuthProvisioner.authRequirement("", false))
    }

    @Test
    fun emptyBecauseUnreadableBlocksTheStart() {
        assertEquals(VncAuthRequirement.BlockedUnreadable, DesktopVncAuthProvisioner.authRequirement("", true))
    }
}
