package com.fem.linexa.service.runtime.features

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding 014: four source comments still described the VNC password as
 * "plaintext in Room" long after [WorkspaceSecretAttributes] moved it to an
 * AES-GCM envelope. A future maintainer could weaken the backup rules or add a
 * plaintext fallback based on that false premise, so the corrected statements
 * are pinned here rather than left to prose review.
 */
class VncSecretDocumentationTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/AndroidManifest.xml")
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

    private val documents: Map<String, String>
        get() = listOf(
            "src/main/AndroidManifest.xml",
            "src/main/res/xml/backup_rules.xml",
            "src/main/res/xml/data_extraction_rules.xml",
            "src/main/java/com/fem/linexa/service/runtime/features/FeatureSettings.kt"
        ).associateWith { File(moduleRoot, it).readText() }

    @Test
    fun noDocumentClaimsTheVncPasswordIsStoredInPlaintext() {
        val forbidden = listOf(
            "desktop VNC password as plaintext",
            "plaintext Room attributes",
            "in plaintext (workspace attributes)",
            "stores the desktop VNC password as plaintext"
        )
        for ((path, text) in documents) {
            for (phrase in forbidden) {
                assertFalse("$path must not claim '$phrase' (finding 014)", text.contains(phrase))
            }
        }
    }

    @Test
    fun everyDocumentStatesTheEncryptedStorage() {
        // The manifest and both backup-rule files explain WHY the DB is
        // excluded even though the secret is encrypted; FeatureSettings states
        // the envelope and the legacy migration directly.
        for ((path, text) in documents) {
            assertTrue("$path must name the AES-GCM envelope", text.contains("AES-GCM"))
            assertTrue("$path must mention the legacy plaintext migration", text.contains("plaintext"))
        }
    }
}
