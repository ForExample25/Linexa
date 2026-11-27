package com.fem.linexa.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-language preference is tri-state on purpose: SYSTEM must stay
 * distinguishable from an explicit English choice, otherwise a later device
 * language change would either be ignored or would overwrite a deliberate
 * choice. These tests pin that contract and the tolerant persisted-key parse.
 */
class AppLanguageTest {

    @Test
    fun `missing or unknown persisted keys degrade to system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromKey(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromKey(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromKey("fr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromKey("SYSTEM"))
    }

    @Test
    fun `persisted keys round-trip`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromKey(language.key))
        }
    }

    @Test
    fun `only concrete languages are explicit`() {
        assertFalse(AppLanguage.SYSTEM.isExplicit)
        assertTrue(AppLanguage.ENGLISH.isExplicit)
        assertTrue(AppLanguage.PERSIAN.isExplicit)
    }

    @Test
    fun `default is system so a fresh install follows the device`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.DEFAULT)
    }
}
