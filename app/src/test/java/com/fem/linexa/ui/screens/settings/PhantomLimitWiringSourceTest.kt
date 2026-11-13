package com.fem.linexa.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhantomLimitWiringSourceTest {

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

    private val accessGroup: String
        get() = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/ui/screens/settings/AccessSettingsGroup.kt"
        ).readText()

    @Test
    fun `status and description mapping use the pure selectors`() {
        assertTrue(accessGroup.contains("phantomLimitStatusText(status)"))
        assertTrue(accessGroup.contains("phantomLimitDescriptionText(sdkInt, status)"))
        assertTrue(accessGroup.contains("phantomLimitShowsRuntimeCaveat(status)"))
    }

    @Test
    fun `guidance selection is sdk specific and nullable`() {
        assertTrue(accessGroup.contains("PhantomLimitPolicy.guidanceFor(sdkInt, status)"))
        assertTrue(accessGroup.contains("if (guidance != null)"))
        assertTrue(accessGroup.contains("PhantomLimitGuidanceBlock(guidance)"))
    }

    @Test
    fun `the command is copied exactly and never executed`() {
        assertTrue(accessGroup.contains("val command = guidance.command"))
        assertTrue(accessGroup.contains("clipboard.setText(AnnotatedString(command))"))
        assertFalse(accessGroup.contains("Runtime.getRuntime()"))
        assertFalse(accessGroup.contains("ProcessBuilder"))
        assertFalse(accessGroup.contains("settings_phantom_limit_adb_command"))
    }

    @Test
    fun `the settings read is off the main thread`() {
        assertTrue(accessGroup.contains("withContext(Dispatchers.IO)"))
        assertTrue(accessGroup.contains("readPhantomLimitRaw(context)"))
    }

    @Test
    fun `the runtime caveat is separate from the truncating row description`() {
        assertTrue(accessGroup.contains("R.string.settings_phantom_limit_caveat"))
        assertTrue(accessGroup.contains("text = stringResource(R.string.settings_phantom_limit_caveat)"))
        assertFalse(accessGroup.contains("settings_phantom_limit_desc_unknown)"))
    }
}
