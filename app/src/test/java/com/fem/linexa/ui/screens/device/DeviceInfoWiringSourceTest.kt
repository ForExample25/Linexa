package com.fem.linexa.ui.screens.device

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural guard for the Device & System Information screen, in the exact
 * technique (and for the exact reason) as `AboutWiringSourceTest`/§1.19: this
 * repo has no Compose UI harness, and an unreferenced composable compiles
 * perfectly — the highest-frequency defect here is a screen that exists but
 * nothing opens. These tests pin REACHABILITY and PURITY by scanning sources.
 *
 * Purity half enforces the track's critical rules at the source level:
 * the screen/mapper/state package must contain zero shell execution, zero
 * `/proc` reads, zero `Build.*` access, zero Linux-command parsing. All host
 * facts may enter ONLY through HostSystemInfoProvider.
 */
class DeviceInfoWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/MainActivity.kt"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/$path").readText()

    private val navGraph: String get() = source("com/fem/linexa/navigation/NavGraph.kt")
    // HAVEN rebuild: the Settings surface is the SettingsScreen tab (the old
    // HomeScreen/GlobalSettingsScreen pair is gone).
    private val settingsScreen: String
        get() = source("com/fem/linexa/ui/screens/settings/SettingsScreen.kt")
    private val deviceScreen: String
        get() = source("com/fem/linexa/ui/screens/device/DeviceInfoScreen.kt")
    private val deviceMapper: String
        get() = source("com/fem/linexa/ui/screens/device/DeviceInfoMapper.kt")
    private val viewModelSource: String
        get() = source("com/fem/linexa/ui/screens/device/DeviceInfoViewModel.kt")

    // ── The screen is reachable ────────────────────────────────────────────

    @Test
    fun theRouteIsRegisteredInTheNavGraph() {
        assertTrue(
            "a route constant with no composable() is not a destination",
            navGraph.contains("composable(Routes.DEVICE_INFO)")
        )
        assertTrue(
            "and the destination must actually be the Device info screen",
            navGraph.contains("DeviceInfoScreen(")
        )
    }

    @Test
    fun theRouteIsNavigatedToFromHome() {
        assertTrue(
            "nothing navigates to the Device-info route",
            navGraph.contains("navigate(Routes.DEVICE_INFO)")
        )
        assertTrue(
            "the callback must be handed to HomeScreen, which owns the Settings tab",
            navGraph.contains("onOpenDeviceInfo =")
        )
    }

    @Test
    fun theSettingsTabAcceptsTheCallback() {
        // The Settings tab is a top-level destination; the callback must be
        // accepted there and actually invoked by the Device & system row — an
        // accepted-but-dropped parameter is the §1.19 bug shape.
        assertTrue(
            "SettingsScreen must accept the callback",
            settingsScreen.contains("onOpenDeviceInfo: () -> Unit")
        )
        assertTrue(
            "and its row must invoke it (onClick = onOpenDeviceInfo)",
            settingsScreen.contains("onClick = onOpenDeviceInfo")
        )
    }

    @Test
    fun theSettingsRowInvokesTheCallbackRatherThanDoingNothing() {
        assertTrue(
            "the Device & system row must exist in Settings",
            settingsScreen.contains("R.string.device_info_row_title")
        )
        assertTrue(
            "and its tap must open the destination",
            settingsScreen.contains("onClick = onOpenDeviceInfo")
        )
    }

    // ── The ViewModel binds to the single authoritative source ─────────────

    @Test
    fun theViewModelConsumesTheHostSystemInfoProviderOnly() {
        assertTrue(
            "the Device-info producer MUST inject HostSystemInfoProvider",
            viewModelSource.contains("hostSystemInfoProvider: HostSystemInfoProvider")
        )
        assertTrue(
            "manual refresh routes through refreshSnapshot (provider rate-guarded)",
            viewModelSource.contains("refreshSnapshot()")
        )
    }

    @Test
    fun theViewModelIsConstructedThroughHilt() {
        assertTrue(
            "@HiltViewModel keeps construction consistent with every other screen",
            viewModelSource.contains("@HiltViewModel")
        )
        assertTrue(
            "and Inject",
            viewModelSource.contains("@Inject constructor(")
        )
    }

    // ── UI purity: no independent collection of Android facts anywhere ─────

    /**
     * Strip /* */ and // comments before pattern scanning: the forbidden
     * constructs must be absent from CODE — KDoc that *documents* why e.g.
     * ActivityManager is not touched (mapper's Android-views note) is fine.
     */
    private fun codeOnly(kotlin: String): String = buildString {
        var i = 0
        var inString = false
        while (i < kotlin.length) {
            val c = kotlin[i]
            if (inString) {
                append(c)
                if (c == '\\' && i + 1 < kotlin.length) {
                    append(kotlin[i + 1]); i++
                } else if (c == '"') {
                    inString = false
                }
                i++
            } else when (c) {
                '"' -> { inString = true; append(c); i++ }
                '/' -> if (i + 1 < kotlin.length && kotlin[i + 1] == '/') {
                    while (i < kotlin.length && kotlin[i] != '\n') i++
                } else if (i + 1 < kotlin.length && kotlin[i + 1] == '*') {
                    i += 2
                    while (i + 1 < kotlin.length && !(kotlin[i] == '*' && kotlin[i + 1] == '/')) i++
                    i += 2
                } else { append(c); i++ }
                else -> { append(c); i++ }
            }
        }
    }

    @Test
    fun theDevicePackageNeverExecutesCommandsOrReadsProc() {
        // Everything in this list would be a second collection system — the
        // exact thing Phase 11 forbids. Scans comment-stripped CODE only.
        listOf(deviceScreen, deviceMapper, viewModelSource).forEach { file ->
            val code = codeOnly(file)
            assertFalse(
                "no Runtime.exec/process spawning in the device-info UI",
                Regex("\\bProcessBuilder\\b|\\bRuntime\\.getRuntime\\(\\)\\.exec").containsMatchIn(code)
            )
            assertFalse(
                "no /proc or /sys path reads in the device-info UI",
                code.contains("\"/proc") || code.contains("\"/sys")
            )
            assertFalse(
                "no android.os.Build identity reads in the device-info UI (Build.* belongs to the provider)",
                code.contains("android.os.Build") ||
                    Regex("\\bBuild\\.(SUPPORTED_ABIS|MANUFACTURER|MODEL|VERSION)\\b").containsMatchIn(code)
            )
            assertFalse(
                "no direct platform-manager construction/access in the device-info UI",
                Regex("\\b(getSystemService|registerReceiver)\\(").containsMatchIn(code)
            )
        }
    }

    @Test
    fun theScreenResolvesLabelsFromResourcesRatherThanHardcodingVisibleText() {
        assertTrue(
            "row labels resolve through string resources (InfoRowId switch)",
            deviceScreen.contains("R.string.device_row_manufacturer")
        )
        assertTrue(
            "section titles resolve through string resources",
            deviceScreen.contains("R.string.device_section_cpu")
        )
    }

    @Test
    fun unknownValuesRenderAsTheStructuralDashNotZero() {
        assertTrue(
            "screen renders mapper-provided dash values muted",
            deviceScreen.contains("DeviceInfoState.DASH")
        )
    }

    @Test
    fun expandableSectionsPersistAcrossConfigurationChanges() {
        assertTrue(
            "per-core expansion uses rememberSaveable",
            deviceScreen.contains("rememberSaveable")
        )
    }
}
