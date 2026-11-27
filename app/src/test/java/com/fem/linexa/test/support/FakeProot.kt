package com.fem.linexa.test.support

import java.io.File

/**
 * §1.70 — Roadmap Item 1: a standing-in for the real `libproot.so` used by
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime] at test
 * time.
 *
 * The real app executes PRoot by launching
 * `{nativeLibraryDir}/libproot.so` with a typed argv/env set built by
 * [com.fem.linexa.service.runtime.container.PRootLauncher]. This harness
 * substitutes a minimal POSIX-shell script in that exact path: the script
 * can be configured (via a per-directory scenario file) to emit specific
 * stdout/stderr content and exit codes, so the failure-handling logic in
 * `PRootContainerRuntime` (`resolveSeccompFallback`, the seccomp probe, and
 * the `exec()` retry) is exercised against a fake process whose behavior is
 * fully under test control — the thing every PRoot bug in this project's
 * history required an on-device round trip to observe.
 *
 * Design constraints (see roadmap §1.70):
 *  - The substitution point is `ApplicationInfo.nativeLibraryDir` on a
 *    Mockito-faked `Application`. That path only ever points into a
 *    temporary test directory *constructed inside test code* — production
 *    code never instantiates mocks, so the fake can never be triggered
 *    outside test compilation. No system property or env var is involved.
 *  - The real launch path is untouched; production `PRootLauncher` and
 *    `BootstrapManager` still build argv/env against this directory, so
 *    these tests run the actual construction code, not a mirror of it.
 */
object FakeProot {

    /** The exact file name production resolves for the PRoot binary. */
    const val BINARY_NAME = "libproot.so"

    /** The exact §1.50 marker substring — must match the production constant. */
    const val SECCOMP_MARKER = "It seems your kernel contains this bug"

    /**
     * Configurable fake behaviors. The scenario is read from a per-directory
     * scenario file at every invocation, so a test can flip behavior
     * mid-run (e.g. simulate a transient bug that clears after a retry).
     */
    enum class Scenario {
        /** Every invocation emits the seccomp marker to stdout and exits 1. */
        SECCOMP_BUG_ALWAYS,

        /**
         * Only the FIRST invocation emits the marker and exits 1; all later
         * invocations exit 0 silently. Mirrors "retry with
         * PROOT_NO_SECCOMP=1 makes it work".
         */
        SECCOMP_BUG_ONCE,

        /** Every invocation prints an EPERM-style error and exits 70 — no marker. */
        EPERM_NONZERO_ALWAYS,

        /**
         * ONLY invocation #3 emits the marker and exits 1; #1, #2 and #4+
         * exit 0. Used by the extraction fallback tests:
         * `RootfsExtractor.extract()` launches the fake twice for the §1.46
         * forensics (`testExecve(proot)` preflight + the "proot is static
         * ELF" probe at §1.52) — invocations #1 and #2 — so the FIRST extraction
         * ProcessBuilder attempt is precisely #3, the attempt the §1.50 retry
         * must catch.
         */
        SECCOMP_BUG_ON_3RD,

        /** Every invocation exits 1 with generic (non-marker) output. */
        GENERIC_FAILURE_ALWAYS,

        /** Every invocation exits 0 silently. */
        CLEAN_ALWAYS,

        /**
         * Emulates apt's guest behavior: parses the `-r <rootfs>` arg and
         * fails (exit 2, apt's "unable to mkstemp /tmp/apt.sig" marker)
         * when the rootfs has no usable `/tmp`. Exit 0 once `/tmp` exists
         * and is writable. Lets tests prove "apt update succeeds without
         * manual `/tmp` setup" end-to-end through the real runtime.
         */
        APT_UPDATE_REQUIRES_TMP,

        /**
         * §1.83: stands in for a long-lived guest daemon. Unlike every other
         * scenario, the fake PRoot process stays alive until killed — the
         * shape the real `libproot.so` takes when its foreground command is
         * a daemon like Xvnc. Tests use it to prove a guest daemon survives
         * the launching call and is torn down by [stopDaemon].
         */
        DAEMON_STAYS_ALIVE,

        /**
         * F-6: stands in for a long-running one-shot guest command (e.g. an
         * `apt-get install` the Package Manager UI launched). Writes the
         * [SLOW_STARTED_FILE] marker as soon as it starts, then sleeps for
         * [SLOW_SLEEP_SECONDS] before exiting 0 — so a test can determinst-
         * ically observe behavior while the exec is in flight.
         */
        SLOW_EXIT,

        /**
         * 10E: a descendant that IGNORES SIGTERM. Invocations #1/#2 (the
         * §1.46 testExecve preflight and the §1.52 static-ELF probe — same
         * counting as [SECCOMP_BUG_ON_3RD]) exit 0 fast; the FIRST extraction
         * attempt (#3+) signals its start via [TERM_STARTED_FILE], records
         * the host PID and a real `sleep` child PID, traps TERM
         * (`trap '' TERM` — SIG_IGN, inherited by the sleep child), then
         * waits for the child: only SIGKILL can end the tree. RootFS R1
         * uses this shape to prove the extractor's terminator reaches the
         * descendant, not just the PRoot host.
         */
        IGNORES_TERM,

        /**
         * Phase 0: a long-lived daemon WITH a real child process (the shape
         * of a PRoot host whose initial tracee forked a worker). Writes the
         * child PID to [DAEMON_CHILD_FILE] then waits for it, so a test can
         * assert the whole daemon tree — not just the host — is terminated.
         */
        DAEMON_WITH_CHILD,

        /**
         * Phase 0: an exec that hangs WITH a child process, but only for the
         * command containing [TIMEOUT_MARKER]. Every other invocation exits 0
         * immediately, so guest-preparation probes during `execInternal` do
         * not turn into multi-second sleeps. Used by the exec-timeout
         * containment regression test.
         */
        TIMEOUT_WITH_CHILD
    }

    /**
     * Create a fresh fake-PRoot directory holding [BINARY_NAME] configured
     * for [scenario]. The mock Application's `nativeLibraryDir` should
     * point at the returned directory.
     */
    fun install(parent: File, scenario: Scenario): File {
        val dir = File(parent, "fake-proot-${System.nanoTime()}")
        require(dir.mkdirs()) { "could not create fake proot dir ${dir.absolutePath}" }
        setScenario(dir, scenario)
        writeScript(dir)
        return dir
    }

    /** Set the scenario the next fake invocation(s) will follow. */
    fun setScenario(dir: File, scenario: Scenario) {
        File(dir, SCENARIO_FILE).writeText(scenario.name)
    }

    /** The script the fake executes when launched in place of libproot.so. */
    fun writeScript(dir: File, name: String = BINARY_NAME) {
        val script = File(dir, name)
        script.writeText(SCRIPT)
        check(script.setExecutable(true)) { "could not chmod +x ${script.absolutePath}" }
    }

    /**
     * Write a fake busybox dispatcher into [dir] at the exact file name
     * `BootstrapManager` resolves busybox to (`lib__bin__busybox__.so`).
     *
     * §1.80: the guest `/tmp` ensurer runs `chmod 1777` through the bundled
     * busybox `chmod` applet, so the harness must provide a working applet
     * chain: `binDir/chmod -> busybox -> applib -> {dir}/lib__bin__busybox__.so`.
     * The dispatcher keys off argv[0]'s basename and forwards `chmod` to the
     * host's real `/bin/chmod` (present on CI ubuntu and Android toybox), so
     * the sticky bit (01777) is genuinely set and verified in tests. All
     * other applets/busybox bookkeeping (--install, --list) exit 0.
     */
    fun writeFakeBusybox(dir: File) {
        val busybox = File(dir, BUSYBOX_JNI_NAME)
        busybox.writeText(FAKE_BUSYBOX_SCRIPT)
        check(busybox.setExecutable(true)) { "could not chmod +x ${busybox.absolutePath}" }
    }

    /** The exact file name production resolves busybox to under nativeLibraryDir. */
    const val BUSYBOX_JNI_NAME = "lib__bin__busybox__.so"

    /** The exact file name production resolves tmux to under nativeLibraryDir (§1.43). */
    const val TMUX_JNI_NAME = "lib__bin__tmux__.so"

    /**
     * Write a marker tmux binary into [dir] at the exact file name
     * `PRootLauncher.isTmuxAvailable` checks (a runtime `File.exists`).
     * Only tests that exercise the persistence backend's availability gate
     * need this — the launch gate is `persistenceRequested && available`, so
     * providing the binary never wraps a workspace that did not opt in.
     */
    fun writeFakeTmux(dir: File) {
        val tmux = File(dir, TMUX_JNI_NAME)
        tmux.writeText("#!/bin/sh\nexit 0\n")
        check(tmux.setExecutable(true)) { "could not chmod +x ${tmux.absolutePath}" }
    }

    /** The configured scenario currently in effect. */
    fun currentScenario(dir: File): Scenario {
        val raw = File(dir, SCENARIO_FILE).readTextOrNull() ?: return Scenario.CLEAN_ALWAYS
        return runCatching { Scenario.valueOf(raw.trim()) }.getOrDefault(Scenario.CLEAN_ALWAYS)
    }

    /** Number of fake invocations recorded so far. */
    fun invocationCount(dir: File): Int =
        File(dir, COUNTER_FILE).readTextOrNull()?.trim()?.toIntOrNull() ?: 0

    /** Full log of every fake invocation (raw text). */
    fun readLog(dir: File): String = File(dir, LOG_FILE).readTextOrNull() ?: ""

    /** Parsed per-invocation records, one per launched fake process. */
    fun invocations(dir: File): List<Invocation> {
        val raw = readLog(dir)
        if (raw.isBlank()) return emptyList()
        return raw.split(HEADER).drop(1).mapNotNull { block ->
            val number = Regex("^\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val args = block.lineSequence()
                .filter { it.startsWith("argv[") }
                .map { it.substringAfter("=") }
                .toList()
            val envLines = block.lineSequence()
                .dropWhile { !it.startsWith(ENV_SECTION) }
                .drop(1)
                .takeWhile { !it.startsWith(END_MARK) }
                .map { it.trim() }
            val env = buildMap<String, String> {
                for (line in envLines) {
                    val idx = line.indexOf('=')
                    if (idx > 0) put(line.substring(0, idx), line.substring(idx + 1))
                }
            }
            Invocation(number = number, argv = args, env = env)
        }
    }

    /**
     * One raw recorded log block per fake invocation (header line excluded).
     *
     * Unlike [invocations], this keeps the FULL text of every invocation,
     * including argv elements that embed newlines (e.g. the §1.99 guest probe
     * script passed as a single `/bin/sh -c <script>` argument, whose
     * "guest OK"/"guest MISSING" markers land on continuation lines that the
     * `argv[`-prefix parser in [invocations] would drop). Tests that identify
     * an invocation by text content should match against these blocks.
     */
    fun rawInvocations(dir: File): List<String> {
        val raw = readLog(dir)
        if (raw.isBlank()) return emptyList()
        return raw.split(HEADER).drop(1)
    }

    /** Invocations whose recorded environment carried PROOT_NO_SECCOMP=1. */
    fun noSeccompInvocations(dir: File): List<Invocation> =
        invocations(dir).filter { it.env["PROOT_NO_SECCOMP"] == "1" }

    /** One fake-PROOT launch as recorded by the script itself. */
    data class Invocation(
        val number: Int,
        val argv: List<String>,
        val env: Map<String, String>
    ) {
        val hadNoSeccompEnv: Boolean get() = env["PROOT_NO_SECCOMP"] == "1"
    }

    // ── script content ────────────────────────────────────────────────────
    //
    // Reads the scenario file each time so mid-run flips work; records each
    // invocation (argv + full environment) into the log so tests can assert
    // exactly what the runtime passed to "PRoot", including the §1.50
    // PROOT_NO_SECCOMP=1 fallback env.
    private val SCRIPT = """
        |#!/bin/sh
        |# Fake PRoot stand-in — generated by FakeProot for JVM tests. Not the real proot.
        |# Some tests launch us with a fully-cleared environment (mimicking the
        |# full-replacement env production uses), so never rely on an inherited PATH.
        |PATH=/usr/bin:/bin:${'$'}PATH
        |DIR=${'$'}(dirname "${'$'}0")
        |CF="${'$'}DIR/$SCENARIO_FILE"
        |CNT="${'$'}DIR/$COUNTER_FILE"
        |LOG="${'$'}DIR/$LOG_FILE"
        |
        |n=0
        |[ -f "${'$'}CNT" ] && n=${'$'}(cat "${'$'}CNT" 2>/dev/null)
        |case "${'$'}n" in '') n=0;; *[!0-9]*) n=0;; esac
        |n=${'$'}((${'$'}n+1))
        |echo "${'$'}n" > "${'$'}CNT"
        |
        |{
        |  echo "${HEADER}${'$'}n"
        |  i=0
        |  for a in "${'$'}@"; do
        |    i=${'$'}((${'$'}i+1))
        |    echo "argv[${'$'}i]=${'$'}a"
        |  done
        |  echo "${ENV_SECTION}"
        |  env | sort
        |  echo "${END_MARK}"
        |} >> "${'$'}LOG" 2>&1
        |
        |scenario=${'$'}(cat "${'$'}CF" 2>/dev/null)
        |case "${'$'}scenario" in
        |  SECCOMP_BUG_ALWAYS)
        |    echo "${MARKER_LINE1}"
        |    echo "${MARKER_LINE2}"
        |    exit 1 ;;
        |  SECCOMP_BUG_ONCE)
        |    if [ "${'$'}n" -eq 1 ]; then
        |      echo "${MARKER_LINE1}"
        |      echo "${MARKER_LINE2}"
        |      exit 1
        |    fi
        |    exit 0 ;;
        |  EPERM_NONZERO_ALWAYS)
        |    echo "tar: Cannot open: Operation not permitted"
        |    exit 70 ;;
        |  SECCOMP_BUG_ON_3RD)
        |    if [ "${'$'}n" -eq 3 ]; then
        |      echo "${MARKER_LINE1}"
        |      echo "${MARKER_LINE2}"
        |      exit 1
        |    fi
        |    exit 0 ;;
        |  GENERIC_FAILURE_ALWAYS)
        |    echo "proot: fatal: unknown error occurred"
        |    exit 1 ;;
        |  APT_UPDATE_REQUIRES_TMP)
        |    # Emulate apt's guest behavior: fail (exit 2, apt's marker) when
        |    # the rootfs has no usable /tmp; succeed once it exists and is
        |    # writable. Parses the guest rootfs from the -r flag.
        |    ROOTFS=""
        |    prev=""
        |    for a in "${'$'}@"; do
        |      if [ "${'$'}prev" = "-r" ]; then ROOTFS="${'$'}a"; fi
        |      prev="${'$'}a"
        |    done
        |    if [ -n "${'$'}ROOTFS" ]; then
        |      if [ ! -d "${'$'}ROOTFS/tmp" ]; then
        |        echo "apt: unable to mkstemp /tmp/apt.sig: No such file or directory"
        |        exit 2
        |      fi
        |      if [ ! -w "${'$'}ROOTFS/tmp" ]; then
        |        echo "apt: unable to mkstemp /tmp/apt.sig: Permission denied"
        |        exit 2
        |      fi
        |    fi
        |    exit 0 ;;
        |  DAEMON_STAYS_ALIVE)
        |    # §1.83: stay alive until killed, like a foreground guest daemon
        |    # under real proot. Loop so the process never exits on its own.
        |    while true; do
        |      sleep 60
        |    done ;;
        |  SLOW_EXIT)
        |    # F-6: long-running one-shot guest command. Signals its start via
        |    # a marker file, then sleeps before exiting 0.
        |    echo started > "${'$'}DIR/$SLOW_STARTED_FILE"
        |    sleep 5
        |    exit 0 ;;
        |  IGNORES_TERM)
        |    # 10E: descendant that ignores SIGTERM. Invocations #1/#2 are the
        |    # preflight probes (testExecve + static-ELF): exit 0 fast. #3+ is
        |    # the real extraction attempt: install the TERM trap FIRST (a
        |    # deadline that fires during sh startup must not kill us before
        |    # the trap exists), record the host and a real sleep child, then
        |    # wait for the child until a SIGKILL — the sleep's output is
        |    # redirected so only the sh holds the capture pipes, keeping EOF
        |    # tied to the sh's death.
        |    if [ "${'$'}n" -lt 3 ]; then
        |      exit 0
        |    fi
        |    trap '' TERM
        |    echo "${'$'}${'$'}" > "${'$'}DIR/$HOST_PID_FILE"
        |    sleep 300 >/dev/null 2>&1 &
        |    child=${'$'}!
        |    echo "${'$'}child" > "${'$'}DIR/$IGNORES_TERM_CHILD_FILE"
        |    echo started > "${'$'}DIR/$TERM_STARTED_FILE"
        |    wait "${'$'}child"
        |    exit 0 ;;
        |  DAEMON_WITH_CHILD)
        |    # Phase 0: a daemon host WITH a real child (PRoot host + tracee).
        |    # The child PID is written so the test can prove the whole tree dies.
        |    echo "${'$'}${'$'}" > "${'$'}DIR/$HOST_PID_FILE"
        |    sleep 300 &
        |    child=${'$'}!
        |    echo "${'$'}child" > "${'$'}DIR/$DAEMON_CHILD_FILE"
        |    wait "${'$'}child" ;;
        |  TIMEOUT_WITH_CHILD)
        |    # Phase 0: only the marked command hangs; everything else is fast.
        |    case "${'$'}*" in
        |      *$TIMEOUT_MARKER*)
        |        echo "${'$'}${'$'}" > "${'$'}DIR/$HOST_PID_FILE"
        |        sleep 300 &
        |        child=${'$'}!
        |        echo "${'$'}child" > "${'$'}DIR/$EXEC_CHILD_FILE"
        |        sleep 10
        |        exit 0 ;;
        |      *) exit 0 ;;
        |    esac ;;
        |  *)
        |    exit 0 ;;
        |esac
        |""".trimMargin()

    // Keep the marker byte-identical to the documented §1.50 output.
    private const val MARKER_LINE1 = "It seems your kernel contains this bug: https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161"
    private const val MARKER_LINE2 = "To workaround it, set the env. variable PROOT_NO_SECCOMP to 1."

    private const val SCENARIO_FILE = ".fake-proot-scenario"
    private const val COUNTER_FILE = ".fake-proot-calls"
    private const val LOG_FILE = "fake-proot.log"

    /** F-6: marker the SLOW_EXIT scenario writes when the process starts. */
    const val SLOW_STARTED_FILE = ".fake-proot-slow-started"

    /** F-6: how long the SLOW_EXIT scenario sleeps before exiting 0. */
    const val SLOW_SLEEP_SECONDS = 5

    /** 10E: marker the IGNORES_TERM scenario writes when the real attempt starts. */
    const val TERM_STARTED_FILE = ".fake-proot-term-started"

    /** RootFS R1: PID file written by IGNORES_TERM for its TERM-ignoring sleep child. */
    const val IGNORES_TERM_CHILD_FILE = ".fake-proot-ignores-term-child-pid"

    /** Phase 0: PID file written by DAEMON_WITH_CHILD / TIMEOUT_WITH_CHILD (the host). */
    const val HOST_PID_FILE = ".fake-proot-host-pid"

    /** Phase 0: PID file written by DAEMON_WITH_CHILD (the daemon's guest child). */
    const val DAEMON_CHILD_FILE = ".fake-proot-daemon-child-pid"

    /** Phase 0: PID file written by TIMEOUT_WITH_CHILD (the exec's guest child). */
    const val EXEC_CHILD_FILE = ".fake-proot-exec-child-pid"

    /** Phase 0: the argv substring that makes TIMEOUT_WITH_CHILD hang. */
    const val TIMEOUT_MARKER = "linexa-timeout-marker"

    /** Phase 0: reads a PID file written by a scenario, or null when absent. */
    fun pidFile(dir: File, name: String): Int? =
        File(dir, name).readTextOrNull()?.trim()?.toIntOrNull()

    /**
     * Phase 0: bounded wait for a scenario's PID file.
     *
     * 30 s, not 10 s: the fake is a real child process, and on a swapping or
     * heavily loaded host (the Android/Termux build box is both) a JVM fork can
     * take far longer than a developer laptop. A timeout here fails the test as
     * a "daemon did not start", which is indistinguishable from a real defect.
     */
    fun awaitPidFile(dir: File, name: String, timeoutMs: Long = 30_000): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pidFile(dir, name)?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    /**
     * Phase 0: bounded wait for a PID file that differs from [previous].
     * Scenarios share the same file name, so starting a second daemon must
     * wait for the rewrite instead of reading the first daemon's stale PID.
     */
    fun awaitNewPidFile(dir: File, name: String, previous: Int?, timeoutMs: Long = 30_000): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pidFile(dir, name)?.takeIf { it != previous }?.let { return it }
            Thread.sleep(25)
        }
        return null
    }
    private const val HEADER = "=== fake-proot invocation "
    private const val ENV_SECTION = "--- env ---"
    private const val END_MARK = "--- end ---"

    private fun File.readTextOrNull(): String? = if (exists()) readText().trim() else null

    /**
     * Fake busybox dispatcher written by [writeFakeBusybox]. Keys off
     * argv[0]'s basename: the applet symlinks in `binDir` exec this script
     * with argv[0] = `binDir/<applet>`, so `chmod` is forwarded to the
     * host's real `/bin/chmod` (exists on CI ubuntu and Android's toybox)
     * — that is what makes a real 01777 mode land on disk in tests.
     */
    // NOTE: deliberately `val`, not `const val` — the `${'$'}` escapes are
    // runtime string templates, which Kotlin forbids in const initializers.
    private val FAKE_BUSYBOX_SCRIPT = """
        |#!/bin/sh
        |# Fake busybox dispatcher — generated by FakeProot for JVM tests. Not the real busybox.
        |PATH=/usr/bin:/bin:${'$'}PATH
        |APP=${'$'}(basename "${'$'}0")
        |case "${'$'}APP" in
        |  busybox)
        |    case "${'$'}1" in
        |      --install|-i) exit 0 ;;
        |      --list) echo "chmod"; echo "sed"; echo "id"; exit 0 ;;
        |    esac
        |    exit 0 ;;
        |  chmod) exec /bin/chmod "${'$'}@" ;;
        |  sed)   exec /bin/sed "${'$'}@" ;;
        |  id)    echo "0"; exit 0 ;;
        |  *)     exit 0 ;;
        |esac
        |""".trimMargin()
}