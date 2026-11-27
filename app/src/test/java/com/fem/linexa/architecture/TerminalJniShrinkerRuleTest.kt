package com.fem.linexa.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding 013 — the vendored PTY backend's JNI binding must survive shrinking.
 *
 * `com.termux.terminal.JNI` is package-private and its four methods are bound
 * by `libtermux.so` using the exact `Java_com_termux_terminal_JNI_*` symbol
 * names. R8 renames and strips Java classes, and every real terminal in the
 * app opens through `TermuxPtySession` → `TerminalSession` → `JNI`, so a
 * missing keep rule is a first-terminal `UnsatisfiedLinkError`, not a size
 * trade-off.
 *
 * The rules are latent while `isMinifyEnabled = false`, which is exactly why
 * this test pins them *before* release shrinking is enabled: the failure would
 * otherwise ship only in a minified build.
 */
class TerminalJniShrinkerRuleTest {

    private lateinit var repoRoot: File

    @Before
    fun locateRepoRoot() {
        val marker = "app/src/main/java/com/fem/linexa/MainActivity.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) {
                repoRoot = dir
                return
            }
            dir = dir.parentFile
        }
        repoRoot = File(".")
    }

    private val rules: String
        get() = File(repoRoot, "app/proguard-rules.pro").readText()

    private val jniSource: String
        get() = File(repoRoot, "terminal-emulator/src/main/java/com/termux/terminal/JNI.java").readText()

    @Test
    fun theVendoredPtyJniClassIsKeptByName() {
        assertTrue(
            "proguard-rules.pro must keep com.termux.terminal.JNI intact; " +
                "libtermux.so binds Java_com_termux_terminal_JNI_* by exact name",
            rules.contains("-keep class com.termux.terminal.JNI { *; }")
        )
        assertTrue(
            "native method names in the terminal-emulator package must be kept",
            rules.contains("-keepclasseswithmembernames class com.termux.terminal.**")
        )
    }

    @Test
    fun thePinnedClassStillDeclaresTheFourNativeEntryPoints() {
        // If the vendored backend grows a native method, the keep rule still
        // covers it; this assertion only documents the four names the .so
        // exports today, so a source swap cannot silently invalidate the pin.
        listOf(
            "public static native int createSubprocess(",
            "public static native void setPtyWindowSize(",
            "public static native int waitFor(",
            "public static native void close("
        ).forEach { declaration ->
            assertTrue(
                "JNI.java no longer declares '$declaration'; update the .so/keep-rule contract",
                jniSource.contains(declaration)
            )
        }
    }

    @Test
    fun theOtherNativeLibrariesKeepTheirExistingRules() {
        // zstd-jni and nhterm were the two bindings already documented; the
        // new terminal rule must not have displaced either.
        assertTrue(rules.contains("-keep class com.github.luben.zstd.** { *; }"))
        assertTrue(rules.contains("-keep class com.offsec.nhterm.** { *; }"))
    }
}
