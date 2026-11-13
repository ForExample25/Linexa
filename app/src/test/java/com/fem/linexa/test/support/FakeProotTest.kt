package com.fem.linexa.test.support

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.70 — sanity tests for the fake PRoot stand-in itself: the script must
 * actually be executable via [ProcessBuilder], emit the configured
 * output/exit-code behavior, and record argv/env faithfully. If any of
 * these fail, every test built on the harness is untrustworthy.
 */
class FakeProotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Run the fake proot with a cleared environment, exactly like production's full-replacement env. */
    private fun run(fake: File, vararg args: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(fake.absolutePath) + args)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }

    private fun fake(scenario: FakeProot.Scenario): Pair<File, File> {
        val dir = FakeProot.install(tmp.newFolder(), scenario)
        return dir to File(dir, FakeProot.BINARY_NAME)
    }

    @Test
    fun cleanScenarioExitsZeroSilently() {
        val (_, fake) = fake(FakeProot.Scenario.CLEAN_ALWAYS)
        val (rc, out) = run(fake)
        assertEquals(0, rc)
        assertEquals("", out.trim())
    }

    @Test
    fun seccompBugScenarioExitsOneWithMarker() {
        val (_, fake) = fake(FakeProot.Scenario.SECCOMP_BUG_ALWAYS)
        val (rc, out) = run(fake)
        assertEquals(1, rc)
        assertTrue("output must carry the §1.50 marker, got: '$out'", out.contains(FakeProot.SECCOMP_MARKER))
    }

    @Test
    fun epermScenarioExits70WithoutMarker() {
        val (_, fake) = fake(FakeProot.Scenario.EPERM_NONZERO_ALWAYS)
        val (rc, out) = run(fake)
        assertEquals(70, rc)
        assertTrue("output must carry the EPERM message, got: '$out'", out.contains("Operation not permitted"))
        assertFalse("EPERM output must NOT carry the seccomp marker", out.contains(FakeProot.SECCOMP_MARKER))
    }

    @Test
    fun genericFailureExits1WithoutMarker() {
        val (_, fake) = fake(FakeProot.Scenario.GENERIC_FAILURE_ALWAYS)
        val (rc, out) = run(fake)
        assertEquals(1, rc)
        assertFalse(out.contains(FakeProot.SECCOMP_MARKER))
    }

    @Test
    fun seccompBugOnceEmitsMarkerOnlyOnFirstInvocation() {
        val (_, fake) = fake(FakeProot.Scenario.SECCOMP_BUG_ONCE)
        val (rc1, out1) = run(fake)
        assertEquals(1, rc1)
        assertTrue(out1.contains(FakeProot.SECCOMP_MARKER))
        val (rc2, _) = run(fake)
        assertEquals("second invocation must exit 0", 0, rc2)
    }

    @Test
    fun seccompBugOn3rdEmitsMarkerOnlyOnThirdInvocation() {
        // §1.70: the §1.46/§1.52 forensics in RootfsExtractor launch the fake
        // twice before its first ProcessBuilder attempt, so the extraction test
        // drives the marker onto invocation #3.
        val (_, fake) = fake(FakeProot.Scenario.SECCOMP_BUG_ON_3RD)
        assertEquals(0, run(fake).first)
        assertEquals("invocation #2 must exit 0", 0, run(fake).first)
        val (rc3, out3) = run(fake)
        assertEquals("invocation #3 must fail with the marker", 1, rc3)
        assertTrue(out3.contains(FakeProot.SECCOMP_MARKER))
        val (rc4, _) = run(fake)
        assertEquals("invocation #4 must exit 0", 0, rc4)
    }

    @Test
    fun invocationLogRecordsArgvAndFullEnv() {
        val (dir, fake) = fake(FakeProot.Scenario.CLEAN_ALWAYS)
        run(fake, "-r", "/rootfs", "echo", "hi", env = mapOf("PROOT_NO_SECCOMP" to "1", "FOO" to "bar"))

        val invocations = FakeProot.invocations(dir)
        assertEquals(1, invocations.size)
        val invocation = invocations[0]
        assertEquals(listOf("-r", "/rootfs", "echo", "hi"), invocation.argv)
        assertEquals("1", invocation.env["PROOT_NO_SECCOMP"])
        assertEquals("bar", invocation.env["FOO"])
        assertTrue(invocation.hadNoSeccompEnv)
        assertEquals(1, FakeProot.invocationCount(dir))
    }

    @Test
    fun scenarioCanBeFlippedMidRun() {
        val (dir, fake) = fake(FakeProot.Scenario.SECCOMP_BUG_ALWAYS)
        assertEquals(1, run(fake).first)
        FakeProot.setScenario(dir, FakeProot.Scenario.CLEAN_ALWAYS)
        assertEquals(0, run(fake).first)
        assertEquals(2, FakeProot.invocationCount(dir))
    }

    @Test
    fun consecutiveInvocationsAreNumbered() {
        val (dir, fake) = fake(FakeProot.Scenario.CLEAN_ALWAYS)
        run(fake, "a")
        run(fake, "b")
        val invocations = FakeProot.invocations(dir)
        assertEquals(listOf(1, 2), invocations.map { it.number })
        assertEquals(listOf(listOf("a"), listOf("b")), invocations.map { it.argv })
    }
}
