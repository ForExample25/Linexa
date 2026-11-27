package com.fem.linexa.core.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

/**
 * Structural guard for the English/Persian localization contract.
 *
 * The parts that can regress silently are not the translations themselves but
 * the *plumbing*: a new English key with no Persian counterpart, a translated
 * string that drops a `%1$s`, a language selector that stops offering one of the
 * three states, or the root composition reverting to an activity recreation.
 * Each would still compile and would still render English-looking text.
 *
 * This test reads the real resources from the module, so it fails on the same
 * commit that introduces the defect rather than at runtime in a Persian locale.
 */
class LocalizationSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/res/values/strings.xml")
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

    private fun resFile(relative: String): File = File(moduleRoot, "src/main/res/$relative")

    private fun source(relative: String): String = File(moduleRoot, "src/main/$relative").readText()

    /** `name -> every string/plural item text`, in resource-file order. */
    private fun strings(path: String): LinkedHashMap<String, List<String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path)
        val root = document.documentElement
        val result = LinkedHashMap<String, List<String>>()
        for (node in 0 until root.childNodes.length) {
            val element = root.childNodes.item(node) as? Element ?: continue
            when (element.tagName) {
                "string" -> result[element.getAttribute("name")] = listOf(element.textContent)
                "plurals" -> {
                    val items = mutableListOf<String>()
                    for (i in 0 until element.childNodes.length) {
                        val item = element.childNodes.item(i) as? Element ?: continue
                        if (item.tagName == "item") items += item.textContent
                    }
                    result[element.getAttribute("name")] = items
                }
            }
        }
        return result
    }

    private val english: LinkedHashMap<String, List<String>>
        get() = strings(resFile("values/strings.xml").path)

    private val persian: LinkedHashMap<String, List<String>>
        get() = strings(resFile("values-fa/strings.xml").path)

    private fun placeholders(text: String): List<String> =
        Regex("""%(?:\d+\$)?[sd]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `every english resource has a persian counterpart and vice versa`() {
        val en = english
        val fa = persian
        assertEquals("Persian resource key set must match English", en.keys, fa.keys)
        for ((key, values) in en) {
            assertTrue("$key must not be empty in values-fa", fa.getValue(key).all { it.isNotBlank() })
            assertEquals("$key must keep the same plural item count", values.size, fa.getValue(key).size)
        }
    }

    @Test
    fun `persian resources preserve every format placeholder`() {
        val fa = persian
        for ((key, values) in english) {
            val translated = fa.getValue(key)
            values.zip(translated).forEachIndexed { index, (source, target) ->
                assertEquals(
                    "placeholder mismatch in $key[$index]: '$source' -> '$target'",
                    placeholders(source),
                    placeholders(target)
                )
            }
        }
    }

    @Test
    fun `persian resources are translated rather than copied english`() {
        val fa = persian
        val persianLetters = Regex("""[\u0600-\u06FF]""")
        // Proper names and unit labels are intentionally identical; the vast
        // majority of resources must contain Persian text.
        val translated = fa.values.count { values -> values.any { persianLetters.containsMatchIn(it) } }
        assertTrue(
            "Only $translated/${fa.size} Persian resources contain Persian text",
            translated >= fa.size * 9 / 10
        )
    }

    @Test
    fun `language selector is present before appearance and offers all three states`() {
        val settings = source("java/com/fem/linexa/ui/screens/settings/SettingsScreen.kt")
        for (required in listOf(
            "global_settings_language_group",
            "AppLanguage.SYSTEM",
            "AppLanguage.ENGLISH",
            "AppLanguage.PERSIAN",
            "LanguageOptionRow("
        )) {
            assertTrue("SettingsScreen must contain $required", settings.contains(required))
        }
        assertTrue(
            "Language must be the first settings group",
            settings.indexOf("global_settings_language_group") <
                settings.indexOf("global_settings_appearance_group")
        )
        val viewModel = source("java/com/fem/linexa/ui/screens/settings/GlobalSettingsViewModel.kt")
        assertTrue(viewModel.contains("setAppLanguage(language)"))
        assertTrue(viewModel.contains("observeAppLanguage()"))
    }

    @Test
    fun `root composition swaps the localized context but pins LTR layout`() {
        val main = source("java/com/fem/linexa/MainActivity.kt")
        for (required in listOf(
            "CompositionLocalProvider(",
            "LocalContext provides localizedContext",
            "LocalConfiguration provides localizedContext.resources.configuration",
            "LocalLayoutDirection provides LayoutDirection.Ltr",
            "AppLocale.localizedContext(baseContext, appLanguage)"
        )) {
            assertTrue("MainActivity must contain $required", main.contains(required))
        }
        assertFalse(
            "Layout direction must never be derived from the selected language",
            main.contains("LayoutDirection.Rtl")
        )
        assertFalse(
            "Layout direction must not be driven by AppLocale.isRtl",
            main.contains("AppLocale.isRtl")
        )
        assertFalse(
            "A language switch must not recreate the activity",
            main.contains("recreate()")
        )
    }

    @Test
    fun `global layout policy is LTR in every language`() {
        val appLocale = source("java/com/fem/linexa/core/i18n/AppLocale.kt")
        assertTrue(
            "The localized configuration must force the layout-direction bit to LTR",
            appLocale.contains("setLayoutDirection(Locale.ROOT)")
        )
        assertFalse(
            "The locale must not drive the configuration layout direction",
            Regex("""setLayoutDirection\(locale\)""").containsMatchIn(appLocale)
        )
        assertFalse(
            "A language-to-layout-direction helper invites RTL inheritance",
            appLocale.contains("fun isRtl(")
        )

        val manifest = source("AndroidManifest.xml")
        assertTrue(
            "The platform must be told the app lays out LTR regardless of locale",
            manifest.contains("""android:supportsRtl="false"""")
        )
    }

    @Test
    fun `only the composition root and locale helper may touch layout direction`() {
        val mainRoot = File(moduleRoot, "src/main")
        val allowed = setOf(
            "java/com/fem/linexa/MainActivity.kt",
            "java/com/fem/linexa/core/i18n/AppLocale.kt"
        )
        val forbiddenMarkers = listOf(
            "LocalLayoutDirection provides",
            "LayoutDirection.Rtl",
            "setLayoutDirection(",
            "android:layoutDirection=",
            "android:textDirection="
        )
        val offenders = File(mainRoot, "java").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .map { it to it.relativeTo(mainRoot).path }
            .filter { (file, relative) ->
                relative !in allowed && forbiddenMarkers.any { marker ->
                    file.readText().contains(marker)
                }
            }
            .map { (_, relative) -> relative }
            .toList()
        assertTrue(
            "Layout direction is a global policy; screens must not override it. Offenders: $offenders",
            offenders.isEmpty()
        )

        val resXml = File(mainRoot, "res").walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .filter { file ->
                file.readText().let { text ->
                    text.contains("android:layoutDirection=") ||
                        text.contains("android:textDirection=")
                }
            }
            .map { it.relativeTo(mainRoot).path }
            .toList()
        assertTrue(
            "No XML resource may override the global LTR layout. Offenders: $resXml",
            resXml.isEmpty()
        )
    }

    @Test
    fun `manifest declares the supported per-app locales`() {
        val manifest = source("AndroidManifest.xml")
        assertTrue(manifest.contains("""android:localeConfig="@xml/locales_config""""))
        assertFalse(
            "Persian is still supported; only RTL layout mirroring is declined",
            manifest.contains("""android:supportsRtl="true"""")
        )

        // The XML parser helper is string/plural-shaped; read locale names directly.
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(resFile("xml/locales_config.xml").path)
        val locales = document.getElementsByTagName("locale")
        val tags = (0 until locales.length).map {
            (locales.item(it) as Element).getAttribute("android:name")
        }.toSet()
        assertEquals(setOf("en", "fa"), tags)
    }

    @Test
    fun `technical text styles stay left-to-right while prose keeps bidi direction`() {
        val colors = source("java/com/fem/linexa/ui/theme/haven/HavenColors.kt")
        val ltrStyles = Regex("""textDirection\s*=\s*TextDirection\.Ltr""")
            .findAll(colors)
            .count()
        assertTrue(
            "Mono/numeric styles (paths, commands, IDs, terminal) must pin LTR; found $ltrStyles",
            ltrStyles >= 6
        )

        // Body/heading styles must stay TextDirection.Unspecified: Compose then
        // applies first-strong bidi, so Persian prose renders RTL inside the LTR
        // chrome. Pinning Ltr here would force Persian text itself to LTR.
        for (style in listOf(
            "h1", "h2", "h3", "title", "titleLg", "rowTitle", "body", "desc", "small"
        )) {
            val declaration = Regex("""val $style = TextStyle\([^\n]*\)""").find(colors)
            assertTrue("$style style declaration must exist", declaration != null)
            assertFalse(
                "$style is prose and must not pin a text direction",
                declaration!!.value.contains("TextDirection.")
            )
        }
    }

    @Test
    fun `saf provider and workspace features route user-visible text through resources`() {
        val provider = source("java/com/fem/linexa/ui/misc/LinexaDocumentsProvider.kt")
        assertFalse(
            "SAF failures must not hardcode English prose",
            provider.contains("FileNotFoundException(\"")
        )
        assertTrue(provider.contains("saf_error_cannot_open"))
        assertTrue(provider.contains("saf_error_invalid_name"))

        val content = source("java/com/fem/linexa/data/WorkspaceContent.kt")
        assertTrue(content.contains("feature_audio_title"))
        assertFalse(content.contains("title = \"Audio\""))
        assertTrue(
            source("java/com/fem/linexa/ui/screens/workspace/FeaturesTab.kt")
                .contains("feature.titleRes")
        )
    }

    @Test
    fun `device info prose resolves through device info labels`() {
        val mapper = source("java/com/fem/linexa/ui/screens/device/DeviceInfoMapper.kt")
        assertTrue(mapper.contains("DeviceInfoLabels"))
        assertFalse(mapper.contains("\"max \$it\""))

        val labels = source("java/com/fem/linexa/ui/screens/device/DeviceInfoLabels.kt")
        assertTrue(labels.contains("R.string.device_summary_cores"))
        assertTrue(labels.contains("R.string.device_thermal_shutdown"))

        assertTrue(
            source("java/com/fem/linexa/di/DeviceInfoLabelsModule.kt")
                .contains("DeviceInfoLabels.from(context)")
        )
    }

    @Test
    fun `shell chrome and terminal menu no longer hardcode english labels`() {
        assertFalse(
            source("java/com/fem/linexa/ui/components/haven/HavenChrome.kt")
                .contains("""NavItem(HavenDestination.WORKSPACES, com.fem.linexa.R.drawable.ic_haven_grid, "Workspaces")""")
        )
        assertTrue(
            source("java/com/fem/linexa/ui/components/haven/HavenChrome.kt")
                .contains("R.string.haven_nav_workspaces")
        )
        assertTrue(
            source("java/com/fem/linexa/ui/components/haven/HavenTerminal.kt")
                .contains("R.string.haven_terminal_all_sessions")
        )
        assertTrue(
            source("java/com/fem/linexa/ui/screens/activity/ActivityScreen.kt")
                .contains("stringResource(R.string.haven_nav_activity)")
        )
    }
}
