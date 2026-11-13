package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.domain.launcher.LauncherApp
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledStatesTest {

    private fun app(pkgs: List<String>, exes: List<String> = emptyList(), id: String = "builtin:t") = LauncherApp(
        id = id, name = "T", description = null, iconKey = null,
        category = null, launchCommand = null, installCommand = null,
        removeCommand = null, verificationPackages = pkgs, verificationExecutables = exes,
        launchAsRoot = false
    )

    @Test
    fun `all declared packages present means installed`() {
        val a = app(listOf("python3", "python3-pip"))
        assertEquals(
            AppInstallTracker.InstalledState.INSTALLED,
            InstalledStates.stateOf(a, setOf("python3", "python3-pip", "git"), emptyMap())
        )
    }

    @Test
    fun `a partial package set is not installed`() {
        val a = app(listOf("python3", "python3-pip"))
        assertEquals(
            AppInstallTracker.InstalledState.NOT_INSTALLED,
            InstalledStates.stateOf(a, setOf("python3", "git"), emptyMap())
        )
    }

    @Test
    fun `all packages absent means not installed`() {
        val a = app(listOf("git"))
        assertEquals(
            AppInstallTracker.InstalledState.NOT_INSTALLED,
            InstalledStates.stateOf(a, setOf("bash"), emptyMap())
        )
    }

    @Test
    fun `no complete installed set means unknown`() {
        val a = app(listOf("git"))
        assertEquals(
            AppInstallTracker.InstalledState.UNKNOWN,
            InstalledStates.stateOf(a, null, emptyMap())
        )
    }

    @Test
    fun `package verdict wins over executable fallback`() {
        val a = app(listOf("git"), listOf("git"))
        assertEquals(
            AppInstallTracker.InstalledState.NOT_INSTALLED,
            InstalledStates.stateOf(a, setOf("bash"), mapOf("builtin:t" to true))
        )
    }

    @Test
    fun `executable verdict is the fallback`() {
        val a = app(emptyList(), listOf("claude"))
        assertEquals(
            AppInstallTracker.InstalledState.INSTALLED,
            InstalledStates.stateOf(a, null, mapOf("builtin:t" to true))
        )
        assertEquals(
            AppInstallTracker.InstalledState.NOT_INSTALLED,
            InstalledStates.stateOf(a, null, mapOf("builtin:t" to false))
        )
    }

    @Test
    fun `unsafe package names do not produce a false positive`() {
        val a = app(listOf("git; rm -rf /"))
        assertEquals(
            AppInstallTracker.InstalledState.UNKNOWN,
            InstalledStates.stateOf(a, setOf("git"), emptyMap())
        )
    }

    @Test
    fun `refresh after an external install flips the measured state`() {
        val a = app(listOf("fastfetch"))
        val before = InstalledStates.stateOf(a, setOf("bash", "coreutils"), emptyMap())
        val after = InstalledStates.stateOf(a, setOf("bash", "coreutils", "fastfetch"), emptyMap())
        assertEquals(AppInstallTracker.InstalledState.NOT_INSTALLED, before)
        assertEquals(AppInstallTracker.InstalledState.INSTALLED, after)
    }

    @Test
    fun `merge covers every app`() {
        val a = app(listOf("a"), id = "builtin:a")
        val b = app(emptyList(), listOf("b"), id = "builtin:b")
        val merged = InstalledStates.mergeStates(
            listOf(a, b),
            installedNames = setOf("a"),
            executableVerdicts = mapOf("builtin:b" to false)
        )
        assertEquals(AppInstallTracker.InstalledState.INSTALLED, merged[a.id])
        assertEquals(AppInstallTracker.InstalledState.NOT_INSTALLED, merged[b.id])
        assertEquals(2, merged.size)
    }
}
