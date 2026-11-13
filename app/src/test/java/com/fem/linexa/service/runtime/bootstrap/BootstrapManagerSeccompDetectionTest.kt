package com.fem.linexa.service.runtime.bootstrap

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * §1.70 — Roadmap Item 1: unit coverage for
 * [BootstrapManager.isSeccompKernelBugFailure], the exact-string seccomp
 * detector that gates the §1.50 PROOT_NO_SECCOMP=1 fallback.
 *
 * The contract under test: detection must fire ONLY on the known marker
 * string (byte-for-byte, case-sensitive, mid-stream OK) and must NEVER fire
 * on a generic non-zero exit or EPERM-style failure — that non-firing is
 * what keeps the retry from misfiring on unrelated failures.
 * [isSeccompKernelBugFailure] touches no Android state, so a bare mock
 * [Application] is enough to construct the manager.
 */
class BootstrapManagerSeccompDetectionTest {

    private fun manager(): BootstrapManager = BootstrapManager(Mockito.mock(Application::class.java))

    private val fullMarker = """
        proot: pid 1234: It seems your kernel contains this bug:
        https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161
        To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.
    """.trimIndent()

    @Test
    fun detectsFullMarkerOutput() {
        assertTrue(manager().isSeccompKernelBugFailure(fullMarker))
    }

    @Test
    fun detectsMarkerAppearingMidStream() {
        assertTrue(
            manager().isSeccompKernelBugFailure(
                "garbage prefix\nIt seems your kernel contains this bug\nsome trailing log\n"
            )
        )
    }

    @Test
    fun detectsMarkerAlone() {
        assertTrue(manager().isSeccompKernelBugFailure("It seems your kernel contains this bug"))
    }

    @Test
    fun emptyOutputIsNotAFailure() {
        assertFalse(manager().isSeccompKernelBugFailure(""))
    }

    @Test
    fun epermOutputIsNotAFailure() {
        assertFalse(manager().isSeccompKernelBugFailure("tar: Cannot open: Operation not permitted"))
        assertFalse(manager().isSeccompKernelBugFailure("proot: open() failed: Operation not permitted"))
    }

    @Test
    fun genericNonZeroOutputIsNotAFailure() {
        assertFalse(manager().isSeccompKernelBugFailure("proot: fatal: unknown error occurred"))
        assertFalse(manager().isSeccompKernelBugFailure("bash: not found"))
    }

    @Test
    fun closeButNotExactMarkerIsNotAFailure() {
        // The detector is an exact-string match (verified in the shipped
        // binary): phrases that paraphrase the marker must NOT be detected.
        assertFalse(manager().isSeccompKernelBugFailure("It seems your kernel may contain this bug"))
        assertFalse(manager().isSeccompKernelBugFailure("it seems your kernel contains this bug"))
        assertFalse(manager().isSeccompKernelBugFailure("IT SEEMS YOUR KERNEL CONTAINS THIS BUG"))
        assertFalse(manager().isSeccompKernelBugFailure("kernel contains this bug"))
    }
}