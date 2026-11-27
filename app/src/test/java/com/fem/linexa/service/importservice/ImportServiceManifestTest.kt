package com.fem.linexa.service.importservice

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Finding 012: `ImportService` is a `specialUse` foreground service, so
 * Android 14+ expects it to declare
 * `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. The manifest is the only
 * place that can regress silently — a missing property still compiles and only
 * surfaces in Play review or on an OEM's FGS enforcement.
 *
 * Reads the real manifest from the module, like
 * [com.fem.linexa.core.i18n.LocalizationSourceTest] does for resources.
 */
class ImportServiceManifestTest {

    private lateinit var manifest: Element

    @Before
    fun parseManifest() {
        val marker = File("src/main/AndroidManifest.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                val document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(File(dir, marker.path))
                manifest = document.documentElement
                return
            }
            dir = dir.parentFile
        }
        error("Could not locate src/main/AndroidManifest.xml from ${File(".").absolutePath}")
    }

    private fun children(element: Element, tag: String): List<Element> {
        val nodes: NodeList = element.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun serviceNamed(name: String): Element {
        val service = children(manifest, "service").firstOrNull {
            it.getAttribute("android:name") == name
        }
        assertNotNull("manifest must declare $name", service)
        return service!!
    }

    @Test
    fun importServiceDeclaresItsSpecialUseSubtype() {
        val service = serviceNamed(".service.importservice.ImportService")
        assertEquals("specialUse", service.getAttribute("android:foregroundServiceType"))

        val property = children(service, "property").firstOrNull {
            it.getAttribute("android:name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        }
        assertNotNull(
            "ImportService must declare PROPERTY_SPECIAL_USE_FGS_SUBTYPE (finding 012)",
            property
        )
        assertEquals("rootfs-import", property!!.getAttribute("android:value"))
    }

    @Test
    fun everySpecialUseServiceDeclaresASubtype() {
        // Guards the next specialUse service too: the property is required by
        // policy, not by this one class.
        val specialUse = children(manifest, "service").filter {
            it.getAttribute("android:foregroundServiceType").contains("specialUse")
        }
        assertTrue("expected at least the import and runtime services", specialUse.size >= 2)
        for (service in specialUse) {
            val name = service.getAttribute("android:name")
            val property = children(service, "property").firstOrNull {
                it.getAttribute("android:name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            }
            assertNotNull("$name is specialUse but has no FGS subtype property", property)
            assertTrue(
                "$name must declare a non-blank FGS subtype",
                property!!.getAttribute("android:value").isNotBlank()
            )
        }
    }
}
