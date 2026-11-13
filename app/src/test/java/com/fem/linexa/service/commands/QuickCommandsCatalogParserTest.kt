package com.fem.linexa.service.commands

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Quick Commands catalog parser: shape + semantic rules, and — most
 * importantly — the SHIPPED catalog itself, which must parse with zero issues
 * (a bundled file that fails here is a build defect, not a runtime fallback:
 * same philosophy as the bundled rootfs profiles' tests).
 */
class QuickCommandsCatalogParserTest {

    private fun parse(yaml: String): QuickCommandsCatalogParser.Result =
        QuickCommandsCatalogParser.parse(yaml.trimIndent().toByteArray())

    private fun parsed(yaml: String): QuickCommandsCatalog =
        (parse(yaml) as QuickCommandsCatalogParser.Result.Parsed).catalog

    private fun parsedWithIssues(yaml: String): Pair<QuickCommandsCatalog, List<String>> {
        val result = parse(yaml) as QuickCommandsCatalogParser.Result.Parsed
        return result.catalog to result.issues
    }

    // ── the shipped file ────────────────────────────────────────────────

    @Test
    fun theShippedCatalogParsesWithZeroIssues() {
        val file = File("src/main/assets/commands/quick_commands.yaml")
        assertTrue("the shipped catalog must exist at $file", file.isFile)
        val result = QuickCommandsCatalogParser.parse(file.readBytes())
        val parsed = result as? QuickCommandsCatalogParser.Result.Parsed
        assertNotNull("shipped catalog must parse: $result", parsed)
        assertEquals("the shipped file must be issue-free", emptyList<String>(), parsed!!.issues)
        // Every distribution the product supports is present, plus common.
        for (key in listOf("common", "ubuntu", "debian", "fedora", "arch", "alpine")) {
            assertTrue("branch '$key' must exist", parsed.catalog.distributions.containsKey(key))
        }
        // Every branch's default categories parse into a usable tree.
        for (distro in parsed.catalog.distributions.values) {
            assertTrue("branch ${distro.key} must have categories", distro.default.categories.isNotEmpty())
        }
    }

    @Test
    fun theShippedCatalogCarriesItsVersionRules() {
        val catalog = parsed(File("src/main/assets/commands/quick_commands.yaml").readText())
        // debian@13 patches system-info; ubuntu@24.04 patches python3.
        assertTrue(catalog.distributions.getValue("debian").versions.containsKey("13"))
        assertTrue(catalog.distributions.getValue("ubuntu").versions.containsKey("24.04"))
    }

    // ── structural failures: the whole load fails ───────────────────────

    @Test
    fun notYamlFailsTheWholeLoad() {
        assertTrue(parse("this is: [not\n\ta mapping") is QuickCommandsCatalogParser.Result.Invalid)
    }

    @Test
    fun aMissingRootKeyFailsTheWholeLoad() {
        assertTrue(parse("something_else: {}") is QuickCommandsCatalogParser.Result.Invalid)
    }

    @Test
    fun anUnsupportedSchemaVersionFailsTheWholeLoad() {
        val result = parse(
            """
            linexa_quick_commands:
              schema_version: 99
              distributions:
                common:
                  default:
                    categories: []
            """
        )
        assertTrue(result is QuickCommandsCatalogParser.Result.Invalid)
    }

    @Test
    fun anUnknownDistributionOrNodeKeyIsRejectedAndDropped() {
        val (catalog, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                ubuntu:
                  default:
                    categories:
                      - id: a
                        type: action
                        name: A
                        command: a
                        bogus_key: 1
                obsoleteos:
                  default:
                    categories:
                      - id: b
                        type: action
                        name: B
                        command: b
            """
        )
        assertTrue("unsupported distribution key detected", issues.any { it.contains("obsoleteos") })
        assertTrue("unknown key detected", issues.any { it.contains("bogus_key") })
        // The unsupported distribution is DROPPED — never resolvable.
        assertNull(catalog.distributions["obsoleteos"])
        // The known branch survives (its node was skipped, its issue recorded).
        assertTrue(catalog.distributions.containsKey("ubuntu"))
    }

    // ── per-node rejections: deterministic skips ────────────────────────

    @Test
    fun aBadNodeIsSkippedWhileItsSiblingsSurvive() {
        val (catalog, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: good
                        type: action
                        name: Good
                        command: good
                      - type: action
                        name: No Id
                        command: x
                      - id: no-name
                        type: action
                        command: x
                      - id: no-type
                        name: No Type
                        command: x
                      - id: bad-type
                        name: Bad Type
                        type: script
                        command: x
                      - id: empty-command
                        name: Empty
                        type: action
                        command: "   "
                      - id: no-command
                        name: No Command
                        type: action
                      - id: also-good
                        type: action
                        name: Also Good
                        command: also-good
            """
        )
        assertEquals(6, issues.size)
        val system = catalog.distributions.getValue("debian").default.categories
        assertEquals(listOf("Good", "Also Good"), system.map { it.name })
    }

    @Test
    fun duplicateIdsAreDetectedAcrossTheWholeBranchTree() {
        val (catalog, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: system
                        type: category
                        name: System
                        children:
                          - id: dup
                            type: action
                            name: First
                            command: first
                      - id: software
                        type: category
                        name: Software
                        children:
                          - id: dup
                            type: action
                            name: Second
                            command: second
                          - id: ok
                            type: action
                            name: OK
                            command: ok
            """
        )
        assertTrue(issues.any { it.contains("duplicate id 'dup'") })
        val names = catalog.distributions.getValue("debian").default.categories
            .flatMap { it.children }.map { it.name }
        // First wins; the second occurrence is skipped, its sibling survives.
        assertEquals(listOf("First", "OK"), names)
    }

    @Test
    fun anActionWithChildrenIsRejected() {
        val (_, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: sneaky
                        type: action
                        name: Sneaky
                        command: run
                        children:
                          - id: child
                            type: action
                            name: Child
                            command: child
            """
        )
        assertTrue(issues.any { it.contains("must not declare 'children'") })
    }

    @Test
    fun aNavigationalNodeWithACommandOrActionIsRejected() {
        val (_, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: exec-category
                        type: category
                        name: Exec
                        command: run
                        children:
                          - id: ok
                            type: action
                            name: OK
                            command: ok
                      - id: tagged-group
                        type: group
                        name: Tagged
                        action: install
                        children:
                          - id: ok2
                            type: action
                            name: OK2
                            command: ok2
            """
        )
        assertTrue(issues.any { it.contains("a category must not declare 'command'") })
        assertTrue(issues.any { it.contains("a group must not declare 'action'") })
    }

    @Test
    fun anApplicationRequiresChildrenAndAPatchNeedsAnEffect() {
        val (_, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: empty-app
                        type: application
                        name: Empty
                      - id: ok
                        type: action
                        name: OK
                        command: ok
                  versions:
                    "13":
                      patches:
                        - id: no-op-patch
            """
        )
        assertTrue(issues.any { it.contains("a application needs 'children'") })
        assertTrue(issues.any { it.contains("patch changes nothing") })
    }

    @Test
    fun aMalformedVersionSelectorIsSkipped() {
        val (catalog, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: ok
                        type: action
                        name: OK
                        command: ok
                  versions:
                    "has space!":
                      categories: []
                    "13":
                      patches:
                        - id: ok
                          description: patched
            """
        )
        assertTrue(issues.any { it.contains("malformed version selector") })
        assertNotNull(catalog.distributions.getValue("debian").versions["13"])
    }

    @Test
    fun anActionTagMustBeFromTheClosedVocabulary() {
        val (_, issues) = parsedWithIssues(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: bad-tag
                        type: action
                        name: Bad
                        action: uninstall-everything
                        command: x
            """
        )
        assertTrue(issues.any { it.contains("unknown 'action' tag") })
    }

    @Test
    fun aControlCharacterInACommandIsRejected() {
        val (_, issues) = parsedWithIssues(
            "linexa_quick_commands:\n" +
                "  schema_version: 1\n" +
                "  distributions:\n" +
                "    debian:\n" +
                "      default:\n" +
                "        categories:\n" +
                "          - id: sneaky\n" +
                "            type: action\n" +
                "            name: Sneaky\n" +
                "            command: \"echo hi\\u0003rm -rf /\"\n"
        )
        assertTrue(issues.any { it.contains("control characters") })
    }

    // ── the happy path keeps order and every field ──────────────────────

    @Test
    fun aWellFormedTreeParsesWithItsStructureAndOrderIntact() {
        val catalog = parsed(
            """
            linexa_quick_commands:
              schema_version: 1
              distributions:
                debian:
                  default:
                    categories:
                      - id: system
                        type: category
                        name: System
                        description: Package operations.
                        children:
                          - id: update
                            type: action
                            name: Check for updates and upgrade
                            action: update
                            icon: refresh
                            command: apt-get update && apt-get upgrade -y
                      - id: software
                        type: category
                        name: Software Management
                        children:
                          - id: chromium-browser
                            type: application
                            name: Chromium Browser
                            children:
                              - id: chromium-install
                                type: action
                                name: Install
                                action: install
                                command: apt-get install -y chromium
                              - id: chromium-remove
                                type: action
                                name: Uninstall
                                action: remove
                                command: apt-get autoremove --purge -y chromium
                      - id: jetbrains
                        type: group
                        name: JetBrains IDEs
                        children:
                          - id: pycharm
                            type: application
                            name: PyCharm
                            children:
                              - id: pycharm-install
                                type: action
                                name: Install
                                action: install
                                command: install-pycharm
            """
        )
        val categories = catalog.distributions.getValue("debian").default.categories
        assertEquals(listOf("System", "Software Management", "JetBrains IDEs"), categories.map { it.name })
        assertEquals(QuickNodeType.CATEGORY, categories[0].type)
        val chromium = categories[1].children.single()
        assertEquals(QuickNodeType.APPLICATION, chromium.type)
        assertEquals(listOf("Install", "Uninstall"), chromium.children.map { it.name })
        assertEquals(
            QuickActionKind.INSTALL,
            chromium.children.first { it.name == "Install" }.actionKind
        )
        // The explicit type is what lets the UI map without guessing — the
        // application is NOT executable and its children ARE.
        assertNull(chromium.command)
        assertTrue(chromium.children.all { !it.command.isNullOrBlank() })
    }
}
