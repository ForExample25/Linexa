package com.fem.linexa.domain.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalChromePolicyTest {

    @Test
    fun closingTheLastOpenWindowReturnsHome() {
        assertTrue(TerminalChromePolicy.returnsHomeAfterClose(openWindowCount = 1, closingIsOpen = true))
    }

    @Test
    fun closingOneOfSeveralWindowsStaysInTheTerminal() {
        assertFalse(TerminalChromePolicy.returnsHomeAfterClose(openWindowCount = 2, closingIsOpen = true))
        assertFalse(TerminalChromePolicy.returnsHomeAfterClose(openWindowCount = 5, closingIsOpen = true))
    }

    @Test
    fun aWindowThatIsAlreadyClosingNeverNavigates() {
        assertFalse(TerminalChromePolicy.returnsHomeAfterClose(openWindowCount = 1, closingIsOpen = false))
    }
}
