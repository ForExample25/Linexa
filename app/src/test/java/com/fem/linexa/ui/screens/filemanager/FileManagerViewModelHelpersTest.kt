package com.fem.linexa.ui.screens.filemanager

import com.fem.linexa.ui.screens.filemanager.FileManagerViewModel.Companion.isInside
import com.fem.linexa.ui.screens.filemanager.FileManagerViewModel.Companion.parentOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.115 — the two pure guards in [FileManagerViewModel].
 *
 * Both are data-loss guards rather than cosmetics: [parentOf] is what the Up
 * button navigates to (walking above `/` would ask the guest to list a path
 * outside the rootfs), and [isInside] is what refuses `cp -r`/`mv` of a folder
 * into its own subtree — an operation that either recurses until the workspace
 * fills its storage or fails halfway, leaving a partial copy.
 *
 * They live in the companion object precisely so they can be tested without an
 * Android `Application`; the rest of the ViewModel needs an instrumented test.
 */
class FileManagerViewModelHelpersTest {

    // ── parentOf ────────────────────────────────────────────────────────────

    @Test
    fun parentOfANestedPath() {
        assertEquals("/etc/apt", parentOf("/etc/apt/sources.list"))
    }

    @Test
    fun parentOfATopLevelEntryIsRoot() {
        assertEquals("/", parentOf("/etc"))
    }

    @Test
    fun rootIsItsOwnParentSoUpCannotEscapeTheRootfs() {
        assertEquals("/", parentOf("/"))
    }

    @Test
    fun blankPathIsTreatedAsRoot() {
        assertEquals("/", parentOf(""))
    }

    @Test
    fun aTrailingSlashDoesNotCostALevel() {
        // Navigating into "/etc/apt/" then Up must reach /etc, not /etc/apt.
        assertEquals("/etc", parentOf("/etc/apt/"))
    }

    @Test
    fun parentOfARelativeLookingPathIsRoot() {
        assertEquals("/", parentOf("etc"))
    }

    @Test
    fun aPathWithSpacesIsHandledStructurally() {
        assertEquals("/home/user/my docs", parentOf("/home/user/my docs/notes.txt"))
    }

    // ── isInside ────────────────────────────────────────────────────────────

    @Test
    fun aPathIsInsideItself() {
        // Pasting a folder into itself is the case this exists to refuse.
        assertTrue(isInside("/home/user", "/home/user"))
    }

    @Test
    fun aDescendantIsInside() {
        assertTrue(isInside("/home/user/docs/a", "/home/user"))
    }

    @Test
    fun aTrailingSlashOnTheAncestorDoesNotChangeTheAnswer() {
        assertTrue(isInside("/home/user/docs", "/home/user/"))
    }

    @Test
    fun aSiblingIsNotInside() {
        assertFalse(isInside("/home/user2", "/home/user"))
    }

    @Test
    fun aPrefixThatIsNotAPathBoundaryIsNotInside() {
        // The bug a naive startsWith() has: "/home/userdata" begins with
        // "/home/user" but is a different directory, so copying into it is
        // legitimate and must not be refused.
        assertFalse(isInside("/home/userdata", "/home/user"))
    }

    @Test
    fun anAncestorIsNotInsideItsDescendant() {
        assertFalse(isInside("/home", "/home/user"))
    }

    @Test
    fun everythingIsInsideRoot() {
        assertTrue(isInside("/etc", "/"))
        assertTrue(isInside("/", "/"))
    }
}
