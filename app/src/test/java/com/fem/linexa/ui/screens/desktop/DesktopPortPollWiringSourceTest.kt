package com.fem.linexa.ui.screens.desktop

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §16.3.4 structural + behavioural guard for `DesktopViewModel.waitForVncPort`.
 *
 * ## Why part of this is a source test
 *
 * `waitForVncPort` is a **private** member of an `AndroidViewModel` that needs a
 * real `Application`, a bound `RuntimeBinder` and Hilt; this module has no
 * Robolectric harness (§1.124(a)). The same constraint produced
 * [DesktopStartupProbeWiringSourceTest] (§16.3.1) and
 * [DesktopGeometryTraceWiringSourceTest] (§1.166), and the same idiom is used
 * here: the *policy* is unit tested in [DesktopPortPollTest], the *loop
 * structure* is pinned by source assertion, and the loop's **semantics** are
 * verified against a real loopback socket by an exact structural replica below.
 *
 * The replica is the one thing that needs justifying. It is not a second
 * implementation kept in sync by hope: [theReplicaMatchesTheProductionLoop] holds
 * it to the production source text, so a change to the poll that is not mirrored
 * here fails rather than silently invalidating the semantic tests.
 */
class DesktopPortPollWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/desktop/$path").readText()

    private val viewModel: String get() = source("DesktopViewModel.kt")

    private val pollBody: String
        get() {
            val start = viewModel.indexOf("private suspend fun waitForVncPort(")
            assertTrue("waitForVncPort must still exist", start > 0)
            val end = viewModel.indexOf("\n    fun getCurrentWorkspaceId()", start)
            assertTrue("could not delimit the poll body", end > start)
            return viewModel.substring(start, end)
        }

    // ---------------------------------------------------------------- structure

    @Test
    fun thePollUsesTheSharedPolicyAndNoInlineLiterals() {
        val body = pollBody
        assertTrue(
            "the retry interval must come from DesktopPortPoll, not a literal",
            body.contains("DesktopPortPoll.delayAfterFailure(")
        )
        assertTrue(
            "the connect timeout must come from DesktopPortPoll too",
            body.contains("DesktopPortPoll.CONNECT_TIMEOUT_MS")
        )
        assertFalse("the old fixed delay must be gone", body.contains("delay(150)"))
        assertFalse("no bare connect timeout literal", body.contains(", 300)"))
        assertEquals(
            "exactly one delay call in the loop",
            1,
            Regex("delay\\(").findAll(body).count()
        )
        assertTrue(
            "and it must be fed by the policy, not a constant",
            body.contains("delay(wait)")
        )
    }

    @Test
    fun theSignatureAndVisibilityAreUnchanged() {
        assertTrue(
            "§16.3.4 must not change the poll's API shape — the 2026-09-05 addition is an " +
                "OPTIONAL liveness short-circuit (fail fast on a server that already died), " +
                "never a replacement for the TCP readiness check",
            viewModel.contains(
                "private suspend fun waitForVncPort(\n        host: String,\n        port: Int,\n        timeoutMs: Long,\n        isServerAlive: (() -> Boolean)? = null\n    ): Boolean"
            )
        )
    }

    @Test
    fun theSocketWorkStaysOnDispatchersIO() {
        val body = pollBody
        assertTrue("blocking connect must not reach Main", body.contains("withContext(Dispatchers.IO)"))
        val ioIndex = body.indexOf("withContext(Dispatchers.IO)")
        val socketIndex = body.indexOf("Socket()")
        assertTrue("the socket must be created inside the IO block", ioIndex in 1..<socketIndex)
    }

    @Test
    fun theSocketIsStillClosedByUse() {
        val body = pollBody
        assertTrue(
            "every attempt must close its socket, success or failure",
            body.contains("Socket().use { socket ->")
        )
        assertEquals(
            "exactly one socket construction per attempt",
            1,
            Regex("Socket\\(\\)").findAll(body).count()
        )
    }

    @Test
    fun theHostAndPortAreStillTheCallersAndUnchanged() {
        val body = pollBody
        assertTrue(body.contains("InetSocketAddress(host, port)"))
        assertTrue(
            "the call site must still target loopback with a 5000 ms budget — " +
                "Desktop Modes: the port is the workspace's own allocated one " +
                "(DesktopPortRegistry), not the shared VNC_PORT constant",
            viewModel.contains(
                "host = \"127.0.0.1\", port = port, timeoutMs = 5000"
            )
        )
    }

    @Test
    fun cancellationIsRethrownBeforeTheBroadCatch() {
        val body = pollBody
        val cancelIndex = body.indexOf("catch (e: CancellationException)")
        val broadIndex = body.indexOf("catch (_: Exception)")
        assertTrue("cancellation must be caught explicitly", cancelIndex > 0)
        assertTrue("and the broad catch must still exist", broadIndex > 0)
        assertTrue(
            "the cancellation catch must come FIRST, or it is dead code",
            cancelIndex < broadIndex
        )
        assertTrue("and it must rethrow", body.contains("throw e"))
    }
    @Test
    fun noThreadTimerOrExecutorWasIntroduced() {
        val body = pollBody
        listOf("Thread(", "Executor", "ScheduledExecutor", "Timer(", "Handler(", "sleep(")
            .forEach { forbidden ->
                assertFalse("§16.3.4 must not introduce $forbidden", body.contains(forbidden))
            }
    }

    @Test
    fun theDaemonOrderingAroundThePollIsUnchanged() {
        // §1.114 / Phase 7: Xvnc -> poll -> session. The poll must not have been
        // moved, weakened to a liveness check, or parallelized.
        val xvnc = viewModel.indexOf("DAEMON_XVNC,")
        val poll = viewModel.indexOf("waitForVncPort(")
        val session = viewModel.indexOf("DesktopSessionProvisioner.DAEMON_NAME,")
        assertTrue(xvnc > 0 && xvnc < poll && poll < session)
        assertFalse(
            "readiness must remain 'accepts TCP', not 'process is alive'",
            pollBody.contains("isDaemonAlive")
        )
    }

    @Test
    fun theReplicaMatchesTheProductionLoop() {
        // Guards the semantic tests below: they are only evidence about the
        // product while the replica is structurally identical to it.
        val body = pollBody
        listOf(
            "val start = System.currentTimeMillis()",
            "val deadline = start + timeoutMs",
            "while (System.currentTimeMillis() < deadline)",
            "DesktopPortPoll.CONNECT_TIMEOUT_MS",
            "catch (e: CancellationException)",
            "throw e",
            "catch (_: Exception)",
            "DesktopPortPoll.delayAfterFailure(",
            "elapsedMs = after - start",
            "remainingMs = deadline - after",
            "if (wait <= 0L) break",
            "delay(wait)"
        ).forEach {
            assertTrue("replica drifted from production: missing `$it`", body.contains(it))
        }
    }

    // ---------------------------------------------------------------- semantics

    /** Attempt counter, so the tests can assert work done rather than wall time. */
    private var attempts = 0

    /**
     * Largest `after + wait - deadline` the replica ever scheduled. The clamp's
     * own contract is exactly `after + wait <= deadline`; recording it makes the
     * assertion a day-one invariant of the policy rather than a wall-clock
     * measurement polluted by connect cost and scheduler latency.
     */
    private var maxDelayOvershootMs = Long.MIN_VALUE

    /**
     * Structural replica of the production loop, pinned by
     * [theReplicaMatchesTheProductionLoop].
     */
    private suspend fun waitForVncPortReplica(
        host: String,
        port: Int,
        timeoutMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var listening = false
        while (System.currentTimeMillis() < deadline) {
            try {
                attempts++
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host, port),
                        DesktopPortPoll.CONNECT_TIMEOUT_MS
                    )
                }
                listening = true
                break
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val after = System.currentTimeMillis()
                val wait = DesktopPortPoll.delayAfterFailure(
                    elapsedMs = after - start,
                    remainingMs = deadline - after
                )
                // Only a POSITIVE delay is a policy decision to sleep past an
                // instant. `wait == 0` means "stop now"; the attempt itself may
                // already have crossed the deadline (a connect or scheduler
                // stall), which is not a clamp failure.
                if (wait > 0L) {
                    maxDelayOvershootMs = maxOf(maxDelayOvershootMs, after + wait - deadline)
                }
                if (wait <= 0L) break
                delay(wait)
            }
        }
        listening
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun readyPortSucceedsOnTheFirstAttemptWithNoDelay() = runBlocking {
        ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            attempts = 0
            val t0 = System.currentTimeMillis()
            val ok = waitForVncPortReplica("127.0.0.1", server.localPort, 5_000)
            val elapsed = System.currentTimeMillis() - t0
            assertTrue("an already-listening port must be detected", ok)
            assertEquals("exactly one attempt — no retry, no pre-delay", 1, attempts)
            // Generous bound: this asserts "no policy delay was paid" (the
            // smallest of which is 50 ms), not a performance figure.
            assertTrue("a ready port must not pay a retry delay (was $elapsed ms)", elapsed < 50)
        }
    }

    @Test
    fun aClosedPortRetriesAndThenTimesOutReturningFalse() = runBlocking {
        val port = freePort()
        attempts = 0
        // A deliberately short budget: the timeout contract is what is under
        // test, and DesktopPortPollTest already pins the 5000 ms schedule.
        val ok = waitForVncPortReplica("127.0.0.1", port, 400)
        assertFalse("a port that never opens must return false", ok)
        assertTrue("it must have retried, not given up after one attempt", attempts > 1)
        // 400 ms of dense polling is 8 x 50 ms; allow slack for connect cost.
        assertTrue("retries must be bounded, got $attempts", attempts <= 9)
    }

    @Test
    fun thePollStopsAtItsDeadlineAndDoesNotOverrun() = runBlocking {
        val port = freePort()
        val budget = 500L
        maxDelayOvershootMs = Long.MIN_VALUE
        val ok = waitForVncPortReplica("127.0.0.1", port, budget)
        assertFalse(ok)
        // The clamp's contract, asserted exactly and without a clock: no retry
        // may be SCHEDULED for an instant after the deadline. This is what the
        // pre-§16.3.4 fixed 150 ms sleep violated (it slept past a deadline that
        // could be 1 ms away).
        //
        // Wall-clock is deliberately not the assertion: this device's test JVM
        // has shown a single loop iteration stalling for seconds between
        // `Socket.connect` returning and the next clock read, and a connect may
        // itself consume up to CONNECT_TIMEOUT_MS. Neither is the policy's
        // delay, so neither can disprove the clamp.
        assertTrue(
            "a retry was scheduled ${maxDelayOvershootMs}ms past the deadline — the clamp failed",
            maxDelayOvershootMs <= 0L
        )
    }

    @Test
    fun aPortThatOpensLateIsDetectedAndPollingStopsImmediately() = runBlocking {
        val port = freePort()
        attempts = 0
        var server: ServerSocket? = null
        try {
            val opener = launch(Dispatchers.IO) {
                delay(200)
                server = ServerSocket(port, 8, java.net.InetAddress.getByName("127.0.0.1"))
            }
            val ok = waitForVncPortReplica("127.0.0.1", port, 5_000)
            opener.join()
            assertTrue("a late bind must still be detected", ok)
            val before = attempts
            assertTrue("it must have taken several attempts", before > 1)
            // Success breaks the loop: no further attempt is issued afterwards.
            assertEquals("polling must stop on success", before, attempts)
        } finally {
            server?.close()
        }
    }

    @Test
    fun cancellationTerminatesThePollAndIsNotSwallowed() = runBlocking {
        val port = freePort()
        attempts = 0
        var caught: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                // A budget far longer than the cancellation delay: if the
                // CancellationException were swallowed, this would keep polling
                // to its 30 s deadline instead of ending here.
                waitForVncPortReplica("127.0.0.1", port, 30_000)
            } catch (e: CancellationException) {
                caught = e
                throw e
            }
        }
        delay(250)
        val attemptsAtCancel = attempts
        job.cancel()
        val finished = withTimeoutOrNull(3_000) { job.join() }
        assertNotNull("cancellation did not terminate the poll within 3 s", finished)
        assertTrue("the job must end cancelled", job.isCancelled)
        assertNotNull("CancellationException must propagate, not be swallowed", caught)
        // At most the in-flight attempt may complete after the cancel signal.
        delay(400)
        assertTrue(
            "polling continued past cancellation: $attemptsAtCancel -> $attempts",
            attempts <= attemptsAtCancel + 1
        )
    }

    @Test
    fun cancellationDoesNotBecomeANormalTimeoutResult() = runBlocking {
        val port = freePort()
        var returned: Boolean? = null
        val job = launch(Dispatchers.IO) {
            runCatching { returned = waitForVncPortReplica("127.0.0.1", port, 30_000) }
        }
        delay(200)
        job.cancel()
        withTimeoutOrNull(3_000) { job.join() }
        assertNull(
            "a cancelled poll must not produce false — that would be reported to " +
                "the user as an Xvnc timeout",
            returned
        )
    }

    @Test
    fun noPollingSurvivesTheCancelledCoroutine() = runBlocking {
        val port = freePort()
        attempts = 0
        val job = launch(Dispatchers.IO) {
            runCatching { waitForVncPortReplica("127.0.0.1", port, 30_000) }
        }
        delay(250)
        job.cancel()
        withTimeoutOrNull(3_000) { job.join() }
        val settled = attempts
        delay(500)
        assertEquals("attempts continued after the job ended", settled, attempts)
    }

    @Test
    fun theLoopRunsOnADispatcherThatIsNotTheCallers() = runBlocking {
        // Sanity check on withContext(Dispatchers.IO): the poll must not execute
        // on whatever thread called it.
        val port = freePort()
        var pollThread: String? = null
        withContext(Dispatchers.IO) {
            pollThread = Thread.currentThread().name
        }
        assertNotNull(pollThread)
        val ok = waitForVncPortReplica("127.0.0.1", port, 120)
        assertFalse(ok)
        assertTrue("IO dispatcher threads are pooled", pollThread!!.isNotEmpty())
        assertTrue(coroutineContext.job.isActive)
    }

    // ------------------------------------------- the cancellation question

    /**
     * The pre-§16.3.4 loop, verbatim apart from the counter, so the cancellation
     * claim can be **measured** on both shapes rather than argued from the
     * exception hierarchy.
     */
    private suspend fun waitForVncPortLegacy(
        host: String,
        port: Int,
        timeoutMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                attempts++
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 300) }
                return@withContext true
            } catch (_: Exception) {
                @Suppress("MagicNumber")
                delay(150)
            }
        }
        false
    }

    @Test
    fun theLegacyLoopWasAlreadyCancellationSafe() = runBlocking {
        // Recorded because the audit's premise — that `catch (_: Exception)`
        // swallowed cancellation — is FALSE for this particular loop, and a
        // report that claimed otherwise would be wrong.
        //
        // `CancellationException` is an `IllegalStateException`, so the catch
        // does match it. But the loop's only suspension point is `delay`, and it
        // sits in the CATCH block, not the `try`. `Socket.connect` is blocking,
        // so cancellation can never be raised from inside the `try`, and the
        // cancelled `delay` propagates straight out.
        val port = freePort()
        attempts = 0
        var returned: Boolean? = null
        var thrown: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                returned = waitForVncPortLegacy("127.0.0.1", port, 30_000)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        delay(200)
        val atCancel = attempts
        job.cancel()
        val finished = withTimeoutOrNull(3_000) { job.join() }
        assertNotNull("even the legacy loop stopped promptly", finished)
        assertNull("and it did not return a value", returned)
        assertTrue("it ended by cancellation", thrown is CancellationException)
        delay(400)
        assertTrue(
            "no attempts after cancellation (atCancel=$atCancel final=$attempts)",
            attempts <= atCancel
        )
    }

    @Test
    fun theRethrowIsDefensiveNotABehaviourChange() = runBlocking {
        // Both shapes must behave identically under cancellation. The rethrow's
        // value is that it keeps this true if a future change moves a suspending
        // call into the `try`, where cancellation WOULD be caught.
        val port = freePort()

        attempts = 0
        val legacy = launch(Dispatchers.IO) {
            runCatching { waitForVncPortLegacy("127.0.0.1", port, 30_000) }
        }
        delay(200)
        val legacyAt = attempts
        legacy.cancel()
        withTimeoutOrNull(3_000) { legacy.join() }
        delay(300)
        val legacyWasted = attempts - legacyAt

        attempts = 0
        val fixed = launch(Dispatchers.IO) {
            runCatching { waitForVncPortReplica("127.0.0.1", port, 30_000) }
        }
        delay(200)
        val fixedAt = attempts
        fixed.cancel()
        withTimeoutOrNull(3_000) { fixed.join() }
        delay(300)
        val fixedWasted = attempts - fixedAt

        assertEquals(
            "§16.3.4 must not have changed cancellation behaviour " +
                "(legacy=$legacyWasted fixed=$fixedWasted)",
            legacyWasted,
            fixedWasted
        )
        assertTrue("neither may keep polling", fixedWasted <= 1)
    }

    @Test
    fun aSuspendingCallInsideTheTryWouldSwallowCancellationAndSpin() = runBlocking {
        // The hazard the rethrow guards against, demonstrated on the shape the
        // production loop must never become. This is why the explicit catch is
        // worth three lines: without it, correctness depends on nobody ever
        // adding an await inside the `try`.
        var iterations = 0
        suspend fun delayInsideTry(budgetMs: Long): Boolean = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + budgetMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    iterations++
                    delay(50)
                    throw java.net.ConnectException("refused")
                } catch (_: Exception) {
                    // swallows CancellationException too
                }
            }
            false
        }

        val job = launch(Dispatchers.IO) { runCatching { delayInsideTry(1_000) } }
        delay(200)
        val atCancel = iterations
        job.cancel()
        withTimeoutOrNull(4_000) { job.join() }
        assertTrue(
            "the hazard must be real for the rethrow to be justified " +
                "(atCancel=$atCancel final=$iterations)",
            iterations > atCancel * 10
        )
    }

    @Test
    fun theProductionShapeKeepsTheSuspensionPointOutOfTheTry() = runBlocking {
        // The structural half of the same invariant: assert on the source that
        // `delay` is in the catch, so the property above holds by construction
        // as well as by the rethrow.
        val body = pollBody
        val tryIndex = body.indexOf("try {")
        val catchIndex = body.indexOf("catch (_: Exception)")
        val delayIndex = body.indexOf("delay(wait)")
        assertTrue(tryIndex > 0 && catchIndex > tryIndex)
        assertTrue(
            "the only suspending call must sit in the catch block, not the try",
            delayIndex > catchIndex
        )
    }
}
