package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Finding 021 — the package-lock message parser.
 *
 * These are the exact shapes apt/dpkg print when a terminal owns the lock.
 * The parser must extract the holder when the tool names one, and must not
 * invent a holder for an unrelated failure (the raw output stays the source of
 * truth for everything else).
 */
class PackageLockDiagnosticsTest {

    @Test
    fun aptListsLockNamesTheHoldingProcess() {
        val holder = PackageLockDiagnostics.detect(
            "E: Could not get lock /var/lib/apt/lists/lock. " +
                "It is held by process 1234 (apt-get)\n" +
                "E: Unable to lock directory /var/lib/apt/lists/"
        )

        assertEquals(1234, holder?.pid)
        assertEquals("apt-get", holder?.process)
        assertEquals("/var/lib/apt/lists/lock", holder?.lockPath)
    }

    @Test
    fun dpkgFrontendLockNamesTheHoldingProcessAndTrimsTheSentencePeriod() {
        val holder = PackageLockDiagnostics.detect(
            "E: Could not get lock /var/lib/dpkg/lock-frontend. " +
                "It is held by process 4242 (apt)"
        )

        assertEquals(4242, holder?.pid)
        assertEquals("apt", holder?.process)
        assertEquals("/var/lib/dpkg/lock-frontend", holder?.lockPath)
    }

    @Test
    fun frontendLockWithoutAPidStillReportsContention() {
        val holder = PackageLockDiagnostics.detect(
            "E: Unable to acquire the dpkg frontend lock " +
                "(/var/lib/dpkg/lock-frontend), is another process using it?"
        )

        assertEquals(null, holder?.pid)
        assertEquals(null, holder?.process)
        assertEquals("/var/lib/dpkg/lock-frontend", holder?.lockPath)
    }

    @Test
    fun namedPidVariantIsRecognised() {
        val holder = PackageLockDiagnostics.detect(
            "dpkg: error: dpkg frontend lock was locked by another process with pid 777"
        )

        assertEquals(777, holder?.pid)
        assertEquals("dpkg", holder?.process)
    }

    @Test
    fun unrelatedFailuresAreNotMislabelled() {
        assertNull(PackageLockDiagnostics.detect("E: Unable to locate package not-a-package"))
        assertNull(PackageLockDiagnostics.detect(""))
        assertNull(PackageLockDiagnostics.detect("   "))
    }

    @Test
    fun aLockMessageInTheMiddleOfProgressOutputIsStillFound() {
        val holder = PackageLockDiagnostics.detect(
            "Reading package lists...\n" +
                "E: Could not get lock /var/lib/dpkg/lock-frontend. " +
                "It is held by process 99 (unattended-upgr)\n" +
                "E: Unable to acquire the dpkg frontend lock, are you root?"
        )

        assertEquals(99, holder?.pid)
        assertEquals("unattended-upgr", holder?.process)
    }
}
