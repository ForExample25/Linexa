package com.fem.linexa.ui.screens.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural guard for the finding 007/016 policy wiring, in the same idiom as
 * [DesktopStartupProbeWiringSourceTest]: the decision functions are pure and
 * unit-tested, but a caller can still forget to pass the consent flag or fall
 * back to the nullable pre-tri-state helper. Neither mistake is a compile
 * error, so the call sites are pinned here.
 */
class DesktopVncPolicyWiringSourceTest {

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
        error("Could not locate the app module from ${File(".").absolutePath}")
    }

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/$relative").readText()

    private val viewModel: String
        get() = source("ui/screens/desktop/DesktopViewModel.kt")

    @Test
    fun bothStartPathsPassTheExplicitConsentFlagToTheNoAuthGate() {
        assertEquals(
            "both the VNC and X11 start paths must consult the no-auth gate",
            2,
            Regex("unauthenticatedStartBlocked\\(").findAll(viewModel).count()
        )
        assertTrue(
            "each gate and bind guard must pass the user's explicit consent flag",
            Regex("desktopConfig\\.desktopVncAllowUnauthenticated").findAll(viewModel).count() >= 4
        )
    }

    @Test
    fun bothStartPathsUseTheTriStateBindProof() {
        assertEquals(
            "both start paths must classify the bind as a tri-state proof",
            2,
            Regex("vncLoopbackProof\\(workspaceId, port, runtimeBinder\\)").findAll(viewModel).count()
        )
        listOf(
            "LoopbackProof.Verified",
            "LoopbackProof.Unverified",
            "LoopbackProof.Violation"
        ).forEach { state ->
            assertTrue("the start paths must handle $state", viewModel.contains(state))
        }
        assertFalse(
            "the nullable pre-tri-state helper must not be used as a pass",
            viewModel.contains("vncBindViolationMessage(")
        )
    }

    @Test
    fun enablingTheDesktopGeneratesTheDefaultPassword() {
        val detail = source("ui/screens/workspace/WorkspaceDetailViewModel.kt")
        assertTrue(
            "setFeatureToggle must call the enable-time password rule",
            detail.contains("passwordToGenerateOnEnable(")
        )
        assertTrue(
            "the generated password must be persisted under the VNC password key",
            detail.contains("FeatureSettings.KEY_DESKTOP_VNC_PASSWORD")
        )
    }

    @Test
    fun bothStartPathsProvisionThePasswordOnStdin() {
        // Finding 031: the secret must never be interpolated into the guest
        // command (and therefore PRoot's argv). Both start paths must go
        // through the shared helper, which uses the stdin-capable exec seam.
        assertEquals(
            "the helper's definition plus both start-path call sites",
            3,
            Regex("provisionVncPassword\\(").findAll(viewModel).count()
        )
        assertTrue(
            "the helper must build a provisionRequest",
            viewModel.contains("DesktopVncAuthProvisioner.provisionRequest(")
        )
        assertTrue(
            "and deliver it through the stdin-capable exec seam",
            viewModel.contains("runtimeBinder.execCommandWithInput(")
        )
        assertFalse(
            "the argv-bearing provisionCommand must be gone",
            viewModel.contains("provisionCommand(")
        )
    }

    @Test
    fun theSettingsPanelExposesTheConsentSwitch() {
        val panel = source("ui/screens/workspace/FeatureSettingsPanels.kt")
        assertTrue(
            "the VNC panel must let the user grant no-auth consent",
            panel.contains("settings.desktopVncAllowUnauthenticated")
        )
        assertTrue(
            "the consent row must render the warning help text",
            panel.contains("feature_settings_desktop_allow_unauth_help")
        )
    }
}
