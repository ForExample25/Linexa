package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPasswordPolicyTest {

    @Test
    fun `an auto-detected desktop with no password needs one generated`() {
        assertTrue(DesktopVncAuthProvisioner.needsGeneratedPassword("", unreadable = false, allowUnauthenticated = false))
    }

    @Test
    fun `a chosen password, an unreadable secret or explicit consent never triggers generation`() {
        assertFalse(DesktopVncAuthProvisioner.needsGeneratedPassword("secret", false, false))
        assertFalse(DesktopVncAuthProvisioner.needsGeneratedPassword("", true, false))
        assertFalse(DesktopVncAuthProvisioner.needsGeneratedPassword("", false, true))
    }

    @Test
    fun `passwordToGenerateOnEnable agrees with the predicate`() {
        assertNotNull(DesktopVncAuthProvisioner.passwordToGenerateOnEnable("", false, false))
        assertNull(DesktopVncAuthProvisioner.passwordToGenerateOnEnable("x", false, false))
    }
}
