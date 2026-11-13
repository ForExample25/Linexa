package com.fem.linexa.domain.terminal

/**
 * Navigation rules for the terminal window chrome, kept pure so they are
 * testable on the plain JVM.
 *
 *  - Minimize (−) always returns to the workspace home and never touches the
 *    session: there is deliberately no decision to make, so nothing here.
 *  - Close (×) ends one terminal; the user only leaves the terminal screen when
 *    that terminal was the last open window.
 */
object TerminalChromePolicy {

    /**
     * @param openWindowCount windows that are not already closing
     * @param closingIsOpen whether the window being closed is one of them
     *   (a second tap on an already-closing window must not navigate)
     */
    fun returnsHomeAfterClose(openWindowCount: Int, closingIsOpen: Boolean): Boolean =
        closingIsOpen && openWindowCount <= 1
}
