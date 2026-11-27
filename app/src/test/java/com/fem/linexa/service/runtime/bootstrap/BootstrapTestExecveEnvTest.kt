package com.fem.linexa.service.runtime.bootstrap

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the fresh-install acceptance finding (2026-11-26):
 * `BootstrapManager.testExecve()` cleared the child environment and executed a
 * bundled dynamic binary with no `LD_LIBRARY_PATH`. On a clean install that
 * produced a real linker abort in the system crash buffer
 * (`CANNOT LINK EXECUTABLE .../busybox: library "libbusybox.so.1.38.0" not
 * found`), i.e. a false negative for the probe plus a native crash entry for a
 * binary that works perfectly with the normal bootstrap environment.
 *
 * The subject is a process launch (not JVM-observable), so the pin is
 * source-level, consistent with the other bootstrap wiring tests: the probe must
 * install [BootstrapManager.processEnv] into the child before starting it.
 */
class BootstrapTestExecveEnvTest {

    private fun bootstrapSource(): String {
        val file = File("src/main/java/com/fem/linexa/service/runtime/bootstrap/BootstrapManager.kt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun testExecveBody(): String {
        val source = bootstrapSource()
        val start = source.indexOf("fun testExecve(")
        assertTrue("testExecve not found", start >= 0)
        val end = source.indexOf("\n    fun ", start + 1)
        assertTrue("testExecve body not delimited", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `the execve probe runs with the real bootstrap environment`() {
        val body = testExecveBody()
        assertTrue(
            "testExecve must put processEnv() into the child environment",
            body.contains("environment().putAll(processEnv())")
        )
        assertTrue(
            "the probe must not start a dynamic binary without LD_LIBRARY_PATH",
            body.indexOf("putAll(processEnv())") < body.indexOf(".start()")
        )
    }
}
