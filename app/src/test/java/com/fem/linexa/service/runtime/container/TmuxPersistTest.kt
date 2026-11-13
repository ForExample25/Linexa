package com.fem.linexa.service.runtime.container

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * §1.58: JVM tests for the pure tmux-persistence argv construction.
 *
 * These encode the "tmux wraps proot wraps bash" chain (§1.20, §1.58): a
 * workspace terminal launch must come out as a tmux new-session invocation
 * whose inner command is the full proot argv ending in the guest shell, so
 * a) interactive sessions persist across app restarts (tmux server keeps
 * running detached) and b) stopping a workspace can tear the server down
 * with a matching kill-server invocation.
 */
class TmuxPersistTest {

    @Test
    fun newSessionWrapsInnerExecutableAndArgsAfterTmuxFlags() {
        val prootExe = "/data/app/com.fem.linexa/lib/arm64/lib__bin__proot__.so"
        val prootArgs = listOf(
            "-r", "/storage/emulated/0/Android/data/com.fem.linexa/files/workspaces/w1/rootfs",
            "-w", "/root",
            "--link2symlink",
            "-b", "/proc",
            "/bin/bash", "--login"
        )

        val args = TmuxPersist.newSessionArgs(
            socketPath = "/data/user/0/com.fem.linexa/files/workspaces/w1/.tmux.sock",
            sessionName = TmuxPersist.DEFAULT_SESSION_NAME,
            innerExecutable = prootExe,
            innerArgs = prootArgs
        )

        assertEquals(
            listOf(
                "-S", "/data/user/0/com.fem.linexa/files/workspaces/w1/.tmux.sock",
                "new-session", "-A", "-s", "linexa",
                prootExe
            ) + prootArgs,
            args
        )
        // The chain: tmux first, then the whole inner command untouched.
        assertEquals(prootExe, args[6])
        assertEquals(prootArgs, args.subList(7, args.size))
    }

    @Test
    fun killServerTargetsTheSameSocketNewSessionCreates() {
        val socket = TmuxPersist.socketPath(File("/tmp/linexa-ws", "workspaces/w2"))

        val newSession = TmuxPersist.newSessionArgs(
            socketPath = socket,
            sessionName = "linexa",
            innerExecutable = "/bin/proot",
            innerArgs = emptyList()
        )
        val killServer = TmuxPersist.killServerArgs(socket)

        assertEquals(listOf("-S", socket, "kill-server"), killServer)
        // Same socket in both: teardown addresses the server new-session joined.
        assertEquals(newSession[1], killServer[1])
    }

    @Test
    fun socketPathIsPerWorkspaceUnderTheWorkspaceDir() {
        val wsDir = File("/tmp/linexa-ws/workspaces/w1")
        val path = TmuxPersist.socketPath(wsDir)
        assertEquals(File(wsDir, ".tmux.sock").absolutePath, path)
        // Per-workspace socket => workspaces can never collide on one tmux server.
        val other = TmuxPersist.socketPath(File("/tmp/linexa-ws/workspaces/w2"))
        assert(path != other)
    }

    @Test
    fun emptyInnerArgsStillYieldValidInvocation() {
        val args = TmuxPersist.newSessionArgs("/sock", "s", "/bin/sh", emptyList())
        assertEquals(listOf("-S", "/sock", "new-session", "-A", "-s", "s", "/bin/sh"), args)
    }
}
