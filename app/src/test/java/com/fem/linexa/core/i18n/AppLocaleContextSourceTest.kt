package com.fem.linexa.core.i18n

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Root-composition crash regression: the localized context must keep the host
 * Activity reachable.
 *
 * The root composition provides `AppLocale.localizedContext(...)` as
 * `LocalContext`; `AppNavGraph` obtains its activity-scoped `HomeViewModel`
 * through `LocalContext.current.findActivity()`. `createConfigurationContext`
 * returns a bare `ContextImpl`, not a `ContextWrapper`, so returning it
 * directly made `findActivity()` null and the app died on launch with
 * "AppNavGraph requires a ComponentActivity host".
 *
 * The fix is structural, and this host cannot run Robolectric (the pinned
 * Conscrypt stack has no aarch64 JNI), so the repo's established source-guard
 * idiom pins the contract; the device launch verifies the runtime behavior.
 */
class AppLocaleContextSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/core/i18n/AppLocale.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
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
        File(moduleRoot, "src/main/java/com/fem/linexa/$path").readText()

    @Test
    fun localizedContextWrapsTheBaseContext() {
        val locale = source("core/i18n/AppLocale.kt")

        assertTrue(
            "the localized context must be a ContextWrapper: $locale",
            locale.contains(") : ContextWrapper(base)")
        )
        assertTrue(
            "only resource resolution may be replaced",
            locale.contains("override fun getResources(): Resources = localized")
        )
        assertTrue(
            "assets must follow the localized resources",
            locale.contains("override fun getAssets(): AssetManager = localized.assets")
        )
        assertTrue(
            "the wrapper must wrap the caller's context, not the bare configuration context",
            locale.contains("return LocalizedResourcesContext(context, localized.resources)")
        )
        assertFalse(
            "returning createConfigurationContext directly loses the activity identity",
            Regex("return context\\.createConfigurationContext\\(").containsMatchIn(locale)
        )
    }

    @Test
    fun navGraphUnwrapsTheLocalizedContextToItsActivity() {
        val nav = source("navigation/NavGraph.kt")

        assertTrue(
            "the nav graph must unwrap LocalContext instead of casting it",
            nav.contains("LocalContext.current.findActivity() as? ComponentActivity")
        )
        assertTrue(
            "a missing host must fail loudly rather than silently degrade",
            nav.contains("AppNavGraph requires a ComponentActivity host")
        )
    }

    @Test
    fun findActivityWalksTheWrapperChain() {
        val locale = source("core/i18n/AppLocale.kt")

        assertTrue(locale.contains("while (current is ContextWrapper)"))
        assertTrue(locale.contains("current = current.baseContext"))
        assertTrue(locale.contains("return current as? Activity"))
    }
}
