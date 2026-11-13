package com.fem.linexa.ui.screens.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

class PhantomLimitLocalizationTest {

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

    private fun strings(locale: String): Map<String, String> {
        val file = File(moduleRoot, "src/main/res/$locale/strings.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    @Test
    fun `every P1 key exists in both locales`() {
        val english = strings("values")
        val persian = strings("values-fa")
        val englishKeys = english.keys.filter { it.startsWith("settings_phantom_limit_") }.toSet()
        val persianKeys = persian.keys.filter { it.startsWith("settings_phantom_limit_") }.toSet()

        assertTrue("P1 keys were not found in the default locale", englishKeys.isNotEmpty())
        assertEquals(englishKeys, persianKeys)
        assertTrue(english.containsKey("common_copied"))
        assertTrue(persian.containsKey("common_copied"))

        englishKeys.forEach { key ->
            assertTrue("blank English value for $key", !english.getValue(key).isBlank())
            assertTrue("blank Persian value for $key", !persian.getValue(key).isBlank())
        }
    }

    @Test
    fun `Persian P1 values are not English fallbacks`() {
        val persian = strings("values-fa")
        assertEquals("بی‌ربط", persian["settings_phantom_limit_status_not_applicable"])
        assertNotEquals("N/A", persian["settings_phantom_limit_status_not_applicable"])
        assertNotEquals("Copied to clipboard", persian["common_copied"])
    }

    @Test
    fun `adb commands are code constants rather than translatable resources`() {
        listOf("values", "values-fa").forEach { locale ->
            val values = strings(locale)
            assertFalse(
                "$locale must not carry an adb command resource",
                values.containsKey("settings_phantom_limit_adb_command")
            )
        }
    }
}
