package com.fem.linexa.ui.screens.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level pins for the in-app update feature's integration points —
 * the defect class this file guards against is the §1.19 one: everything
 * built and DI-wired correctly, but the screen never actually composes the
 * section (or the manifest loses the receiver the installer's status
 * PendingIntent targets), and no compile step fails.
 */
class AppUpdateWiringSourceTest {

    private fun repoFile(relative: String): File {
        val f = File("src/main/java/com/fem/linexa/$relative")
        assertTrue("expected source file $relative to exist", f.exists())
        return f
    }

    @Test
    fun theSettingsScreenComposesTheUpdateSectionBeforeTheAboutGroup() {
        val screen = repoFile("ui/screens/settings/SettingsScreen.kt").readText()
        val updateIndex = screen.indexOf("AppUpdateGroup()")
        assertTrue(
            "the Application Updates section must be composed on the Settings screen",
            updateIndex >= 0
        )
        assertFalse(
            "the update ViewModel must be created lazily by its section item, " +
                "not eagerly at screen scope (PERF-008 remainder)",
            screen.contains("val updateViewModel: AppUpdateViewModel = hiltViewModel()")
        )
        val footerIndex = screen.indexOf("AppInfoFooter()")
        assertTrue(
            "the update section must render before the About footer (the footer is the last group)",
            updateIndex < footerIndex
        )
    }

    @Test
    fun theManifestDeclaresTheInstallPermissionAndTheStatusReceiver() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "REQUEST_INSTALL_PACKAGES is what lets the app hand its OWN update APK to PackageInstaller",
            manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES")
        )
        assertTrue(
            "the install session's status PendingIntent must have its manifest receiver",
            manifest.contains(".service.update.UpdateInstallStatusReceiver")
        )
        assertTrue(manifest.contains("com.fem.linexa.action.UPDATE_INSTALL_STATUS"))
        // The receiver must not be exported: it answers a broadcast this app
        // itself created.
        assertTrue(
            manifest.contains(
                Regex("""<receiver\s+android:name="\.service\.update\.UpdateInstallStatusReceiver"\s+android:exported="false"""")
            )
        )
    }

    @Test
    fun theInstallPathIsTheOfficialPackageInstallerWithAMutableStatusPendingIntent() {
        val installer = repoFile("service/update/UpdateInstaller.kt").readText()
        assertTrue("installs must go through PackageInstaller sessions", installer.contains("createSession"))
        assertTrue(
            "the status PendingIntent must be MUTABLE — the system writes the status extras into it",
            installer.contains("FLAG_MUTABLE")
        )
        assertTrue(
            "nothing may bypass the platform's own confirmation UI",
            installer.contains("STATUS_PENDING_USER_ACTION")
        )
    }

    @Test
    fun noCredentialsAreEmbeddedAnywhereInTheUpdateFeature() {
        val joined = File("src/main/java/com/fem/linexa/service/update")
            .walkTopDown()
            .filter { it.isFile }
            .map { it.readText() }
            .joinToString("\n")
        assertFalse("no Authorization header may be sent to GitHub", joined.contains("Authorization"))
        assertFalse("no GitHub tokens in source", joined.contains("ghp_"))
        assertFalse("no bearer tokens in source", joined.contains("Bearer "))
    }

    @Test
    fun theSectionReadsItsStringsFromTheBilingualResources() {
        val english = File("src/main/res/values/strings.xml").readText()
        val persian = File("src/main/res/values-fa/strings.xml").readText()
        val section = repoFile("ui/screens/settings/AppUpdateSection.kt").readText()
        // Every app_update_* key the section references must exist in BOTH
        // locales (the R references alone would only catch the default locale).
        val referenced = Regex("R\\.string\\.(app_update_[a-z_]+)").findAll(section)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("the section must reference its own keys", referenced.isNotEmpty())
        referenced.forEach { key ->
            assertTrue("missing EN string: $key", english.contains("""name="$key"""))
            assertTrue("missing FA string: $key", persian.contains("""name="$key"""))
        }
        // And every app_update_* key in resources must be referenced (no dead copy).
        val defined = Regex("""name="(app_update_[a-z_]+)"""").findAll(english)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(defined, referenced)
    }
}
