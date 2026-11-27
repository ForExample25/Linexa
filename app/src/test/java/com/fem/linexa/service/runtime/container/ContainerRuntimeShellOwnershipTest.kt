package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlin.coroutines.Continuation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §17.1 (terminal audit) — the launch shell is the workspace's property, not
 * the caller's.
 *
 * [ContainerRuntime.openTerminal] used to declare `shellPath: String =
 * "/bin/bash"`. No implementation read it: [PRootContainerRuntime] accepted the
 * argument and resolved the shell itself from `config.yaml`'s `launch.shell`,
 * then the rootfs's declared `linexa.environment.shell`, then a probe of the
 * image. `WorkspaceTerminalManager` dutifully forwarded its own default into
 * the void. A future caller passing `/bin/zsh` would have been silently
 * ignored — and worse, a caller *relying* on the `/bin/bash` default would have
 * been describing a shell that does not exist on Alpine or minimal Fedora,
 * which is exactly what the probe was written to avoid.
 *
 * The parameter is gone. These tests pin both halves of that decision: the
 * signature no longer offers the choice, and the shell that actually reaches
 * argv still tracks the workspace.
 *
 * ## Why a signature assertion, and why reflection
 *
 * Removing a parameter is not self-guarding: re-adding one compiles, every
 * existing test keeps passing (they never asserted it), and the resulting
 * argument is once again ignored by the only implementation. That is precisely
 * how the original lie survived. Java reflection over the interface is the
 * cheapest way to state "this method takes a workspace, a rootfs and a terminal
 * id, and nothing that looks like a shell" — `kotlin-reflect` is not a
 * dependency of this module, and the Kotlin default-argument bridge
 * (`openTerminal$default`) is itself part of what is being excluded.
 *
 * The behavioural half runs through the REAL [PRootContainerRuntime] against
 * [FakeProot], so it observes the argv the pty is actually launched with.
 */
class ContainerRuntimeShellOwnershipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    /**
     * A rootfs that really contains [shells] as executable scripts, so the
     * probe and the finding-018 validator both see a usable shell.
     */
    private fun rootfsWith(vararg shells: String): String {
        val rootfs = harness.fakeRootfs()
        shells.forEach { shell ->
            val file = File(rootfs, shell.removePrefix("/"))
            file.parentFile?.mkdirs()
            file.writeText("#!/bin/sh\n")
            check(file.setExecutable(true)) { "could not chmod +x $file" }
        }
        return rootfs.absolutePath
    }

    private fun openSession(workspaceId: String, rootfsPath: String): Unit = runBlocking {
        harness.runtime.openTerminal(workspaceId, rootfsPath, DEFAULT_TERMINAL_ID, asRoot = false)
    }

    @Test
    fun theInterfaceDoesNotLetACallerNameTheShell() {
        val overloads = ContainerRuntime::class.java.methods.filter { it.name == "openTerminal" }
        assertTrue("openTerminal must exist on the interface", overloads.isNotEmpty())

        for (method in overloads) {
            // A suspend function's last JVM parameter is the Continuation; the
            // declared Kotlin parameters are everything before it.
            val declared = method.parameterTypes.filterNot { Continuation::class.java == it }
            // Issue #1 (2026-09-03): the fourth parameter is the Boolean
            // `asRoot` elevation flag — still NOT a shell selection. The ban
            // this pin guards is a caller-supplied SHELL (§17.1): no String
            // fourth parameter may exist, and the shell remains
            // workspace-resolved (launch.shell → environment.shell → probe).
            assertEquals(
                "openTerminal takes (workspaceId, rootfsPath, terminalId, asRoot) — a fourth " +
                    "STRING parameter would be §17.1's ignored `shellPath` coming back. The " +
                    "shell is resolved from the workspace; a caller that needs to choose sets " +
                    "launch.shell",
                listOf(String::class.java, String::class.java, String::class.java, Boolean::class.java),
                declared
            )
        }
    }

    @Test
    fun noDefaultArgumentBridgeSurvivesForTheShell() {
        // `shellPath: String = "/bin/bash"` on an interface method also
        // generates a `$default` bridge. Its absence is a second, independent
        // signal that no caller can supply a shell. (Issue #1's `asRoot` is
        // declared WITHOUT a default on the interface on purpose: the
        // implementation's default keeps the old callers compiling, but the
        // interface requires every caller to pass it explicitly — the same
        // no-silent-default discipline this pin has always enforced for the
        // shell.)
        val bridges = ContainerRuntime::class.java.declaredClasses
            .flatMap { it.declaredMethods.toList() }
            .filter { it.name.startsWith("openTerminal") }
        assertTrue(
            "no openTerminal default-argument bridge should exist: ${bridges.map { it.name }}",
            bridges.isEmpty()
        )
    }

    @Test
    fun theShellComesFromTheRootfsNotFromAnyCallerDefault() {
        // The old default was "/bin/bash". This rootfs has no bash at all, so a
        // reintroduced caller-supplied default would produce an argv that
        // cannot exec — the Alpine/minimal-Fedora failure mode.
        openSession("ws-sh-only", rootfsWith("/bin/sh"))

        assertEquals(
            "the probe's answer for this image must reach argv",
            listOf("/bin/sh", "-l"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun aRootfsThatHasBashStillGetsBash() {
        // The counterpart: removing the parameter must not have pinned the
        // shell to the fallback either. Same call, different image, different
        // answer — which is the property that makes resolution the runtime's
        // job.
        openSession("ws-with-bash", rootfsWith("/bin/sh", "/bin/bash"))

        assertEquals(
            listOf("/bin/bash", "--login"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun twoWorkspacesOpenedTheSameWayCanStillLandOnDifferentShells() {
        // The end-to-end statement of §17.1: identical call sites, no shell
        // argument anywhere, and the two sessions still launch the shell each
        // image actually has.
        openSession("ws-a", rootfsWith("/bin/sh"))
        openSession("ws-b", rootfsWith("/bin/sh", "/bin/bash"))

        assertEquals(2, harness.createCalls.size)
        assertEquals(listOf("/bin/sh", "-l"), harness.createCalls[0].args.takeLast(2))
        assertEquals(listOf("/bin/bash", "--login"), harness.createCalls[1].args.takeLast(2))
    }

    @Test
    fun theOnlySupportedWayToChooseAShellIsLaunchShell() {
        // What replaced the parameter, asserted so the kdoc's redirection is
        // real: a per-workspace, persisted override that outranks the probe.
        val dir = File(harness.filesDir, "workspaces/ws-override")
        dir.mkdirs()
        File(dir, "config.yaml").writeText(
            """
            linexa:
              launch:
                shell: /bin/zsh
            """.trimIndent()
        )

        openSession("ws-override", rootfsWith("/bin/sh", "/bin/bash", "/bin/zsh"))

        assertEquals(
            "launch.shell must outrank the probe — this is the supported replacement for the " +
                "removed parameter",
            listOf("/bin/zsh", "--login"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun theSessionIsStillReturnedAndLive() {
        // Guards against the trivial way a signature change goes wrong: the
        // parameter removed from the interface but the implementation's own
        // positional arguments left shifted, so `rootfsPath` or `terminalId`
        // silently swap.
        val pty: PtySession = runBlocking {
            harness.runtime.openTerminal("ws-live", rootfsWith("/bin/sh"), "T7", asRoot = false)
        }

        assertTrue("the returned session must be alive", pty.isAlive)
        assertEquals("the terminal id must not have shifted into another parameter", "ws-live", pty.workspaceId)
        assertTrue(
            "the rootfs must not have shifted: argv must bind the rootfs we passed",
            harness.createCalls.single().args.any { it.contains("ws-live") || it.contains("rootfs") }
        )
    }
}
