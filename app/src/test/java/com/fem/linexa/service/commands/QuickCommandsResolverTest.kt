package com.fem.linexa.service.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuickCommandsResolver]: the distribution/version selection contract.
 *
 * The rules that are silent when broken — an exact version must beat the
 * default, a prefix match must respect component boundaries, an unknown
 * distribution must NEVER receive another distribution's commands, and the
 * portable common branch must be present for everyone — are all pinned here
 * against both fixture catalogs AND the shipped one, because the resolver is
 * the component that decides what a workspace's Commands screen offers.
 */
class QuickCommandsResolverTest {

    // The loader takes a byte-source seam; fixtures serialize the catalog
    // model into a minimal YAML document the parser accepts, so the resolver
    // is exercised through its real path without touching the shipped file.
    private var fixtureBytes: ByteArray? = null

    private fun resolverOver(catalog: QuickCommandsCatalog): QuickCommandsResolver {
        fixtureBytes = serializeForTest(catalog)
        return QuickCommandsResolver(QuickCommandsCatalogLoader { fixtureBytes })
    }

    // serialization for the fixture path: the loader takes bytes, so the
    // fixture catalog model is emitted back to YAML — faithfully, including
    // each node's real id/name/command — and re-parsed through the real
    // pipeline.
    private fun serializeForTest(catalog: QuickCommandsCatalog): ByteArray = buildString {
        appendLine("linexa_quick_commands:")
        appendLine("  schema_version: 1")
        appendLine("  distributions:")
        for ((key, distro) in catalog.distributions) {
            appendLine("    $key:")
            appendLine("      default:")
            appendLine("        categories:")
            for (node in distro.default.categories) writeNode(node)
            if (distro.versions.isNotEmpty()) {
                appendLine("      versions:")
                for ((selector, rule) in distro.versions) {
                    appendLine("        \"$selector\":")
                    if (rule.patches.isNotEmpty()) {
                        appendLine("          patches:")
                        for (patch in rule.patches) {
                            appendLine("            - id: ${patch.id}")
                            patch.name?.let { appendLine("              name: \"${it}\"") }
                            patch.description?.let { appendLine("              description: \"${it}\"") }
                            patch.command?.let { appendLine("              command: \"${it}\"") }
                        }
                    }
                    if (rule.categories.isNotEmpty()) {
                        appendLine("          categories:")
                        for (node in rule.categories) writeNode(node)
                    }
                }
            }
        }
    }.toByteArray()

    private fun StringBuilder.writeNode(node: QuickCommandNode, indent: String = "          ") {
        appendLine("$indent- id: ${node.id}")
        appendLine("$indent  type: ${node.type.name.lowercase()}")
        appendLine("$indent  name: \"${node.name}\"")
        node.description?.let { appendLine("$indent  description: \"${it}\"") }
        node.command?.let { appendLine("$indent  command: \"${it}\"") }
        if (node.children.isNotEmpty()) {
            appendLine("$indent  children:")
            for (child in node.children) writeNode(child, "$indent    ")
        }
    }

    // ── the shipped catalog through the real loader ─────────────────────

    private val shipped = QuickCommandsResolver(
        QuickCommandsCatalogLoader {
            java.io.File("src/main/assets/commands/quick_commands.yaml").readBytes()
        }
    )

    @Test
    fun debianSelectsTheDebianBranchPlusCommon() {
        val resolved = shipped.resolve("debian", null)
        val first = resolved.first()
        // The distro's own System category comes first, carrying apt commands.
        assertEquals("System", first.name)
        assertTrue(
            "the debian branch must carry apt-get commands",
            flattenCommands(first).any { it.contains("apt-get") }
        )
        // …and the portable common branch follows.
        assertEquals("System Tools", resolved.last().name)
    }

    @Test
    fun ubuntuSelectsUbuntuAndNeverDebiansEntries() {
        val resolved = shipped.resolve("ubuntu", null)
        val commands = resolved.flatMap { flattenCommands(it) }
        assertTrue("commands must resolve", commands.isNotEmpty())
        // Ubuntu's deliberate snap-era choice: no chromium entry, Firefox via PPA.
        assertTrue(
            "ubuntu must not offer chromium",
            commands.none { it.contains("apt-get install -y chromium") }
        )
        assertTrue(
            "ubuntu's Firefox entry must come from the Mozilla team PPA",
            commands.any { it.contains("mozillateam") }
        )
    }

    @Test
    fun fedoraArchAndAlpineEachUseTheirOwnPackageManager() {
        val fedora = shipped.resolve("fedora", null).flatMap { flattenCommands(it) }
        val arch = shipped.resolve("arch", null).flatMap { flattenCommands(it) }
        val alpine = shipped.resolve("alpine", null).flatMap { flattenCommands(it) }
        assertTrue(fedora.any { it.startsWith("dnf ") || it.contains(" dnf ") })
        assertTrue(arch.any { it.startsWith("pacman ") || it.contains(" pacman ") })
        assertTrue(alpine.any { it.startsWith("apk ") || it.contains(" apk ") })
        // No apt PACKAGE OPERATIONS outside the portable dispatcher (which
        // legitimately names every manager it probes).
        assertTrue("no apt installs on fedora", fedora.none { it.contains("apt-get install") })
        assertTrue("no apt installs on arch", arch.none { it.contains("apt-get install") })
        assertTrue("no apt installs on alpine", alpine.none { it.contains("apt-get install") })
    }

    @Test
    fun anUnknownDistributionGetsOnlyTheCommonBranch() {
        val resolved = shipped.resolve("windows95", null)
        assertTrue(resolved.isNotEmpty())
        assertTrue(
            "an unknown distro must get the portable branch only",
            resolved.all { it.id.startsWith("general") || it.name == "System Tools" }
        )
        // And NOT a byte of another distribution's package manager.
        val commands = resolved.flatMap { flattenCommands(it) }
        assertTrue(commands.none { it.contains("apt-get install") })
        assertTrue(commands.any { it.contains("command -v apt-get") }) // the portable dispatcher
    }

    @Test
    fun aNullDistributionGetsOnlyTheCommonBranch() {
        val resolved = shipped.resolve(null, null)
        assertEquals(listOf("System Tools"), resolved.map { it.name })
    }

    @Test
    fun exactVersionRuleAppliesForDebian13() {
        val resolved = shipped.resolve("debian", "13")
        val commands = resolved.flatMap { flattenCommands(it) }
        // The "13" rule replaces the system-info command with the fastfetch variant.
        assertTrue(
            commands.any { it.contains("fastfetch") && it.contains("lscpu") && !it.contains("linexa-hostinfo") }
        )
    }

    @Test
    fun defaultBranchAppliesWhenNoRuleMatches() {
        val resolved = shipped.resolve("debian", "11")
        val commands = resolved.flatMap { flattenCommands(it) }
        // Debian 11 has no rule — the default system-info (with the host feed) stays.
        assertTrue(commands.any { it.contains("linexa-hostinfo") })
    }

    @Test
    fun aVersionPrefixMatchesOnAComponentBoundary() {
        val resolved = shipped.resolve("ubuntu", "24.04.2")
        val commands = resolved.flatMap { flattenCommands(it) }
        // "24.04.2" → prefix "24.04" → the PEP-668 venv patch applies.
        assertTrue(commands.any { it.contains("python3 -m venv") })
        // But "12" never matches "127": the prefix must end on a boundary.
        assertEquals(
            emptyList<String>(),
            QuickCommandsResolver.versionCandidates("127").filter { it == "12" }
        )
    }

    // ── fixture-driven edge cases ───────────────────────────────────────

    @Test
    fun theMostSpecificPrefixRuleWins() {
        val catalog = QuickCommandsCatalog(
            schemaVersion = 1,
            distributions = mapOf(
                "debian" to QuickCommandsDistribution(
                    key = "debian",
                    default = branchOf("default-command"),
                    versions = mapOf(
                        "12" to QuickCommandsVersionRule(
                            selector = "12",
                            patches = listOf(QuickCommandPatch(id = "n", command = "patch-12"))
                        ),
                        "12.7" to QuickCommandsVersionRule(
                            selector = "12.7",
                            patches = listOf(QuickCommandPatch(id = "n", command = "patch-12-7"))
                        )
                    )
                )
            )
        )
        val resolver = resolverOver(catalog)
        assertEquals("patch-12-7", firstCommand(resolver.resolve("debian", "12.7.3")))
        assertEquals("patch-12", firstCommand(resolver.resolve("debian", "12.9")))
        assertEquals("default-command", firstCommand(resolver.resolve("debian", "11")))
    }

    @Test
    fun versionRuleCategoriesAreAppendedAfterTheDefaultTree() {
        val catalog = QuickCommandsCatalog(
            schemaVersion = 1,
            distributions = mapOf(
                "debian" to QuickCommandsDistribution(
                    key = "debian",
                    default = branchOf("default-command"),
                    versions = mapOf(
                        "13" to QuickCommandsVersionRule(
                            selector = "13",
                            categories = listOf(action("extra-13", "Extra"))
                        )
                    )
                )
            )
        )
        val resolver = resolverOver(catalog)
        val branch = resolver.resolve("debian", "13")
        assertEquals(2, branch.size)
        assertEquals("Extra", branch.last().name)
    }

    @Test
    fun nonNumericVersionsMatchOnlyTheirLiteralSpelling() {
        val catalog = QuickCommandsCatalog(
            schemaVersion = 1,
            distributions = mapOf(
                "arch" to QuickCommandsDistribution(
                    key = "arch",
                    default = branchOf("rolling-default"),
                    versions = mapOf(
                        "latest" to QuickCommandsVersionRule(
                            selector = "latest",
                            patches = listOf(QuickCommandPatch(id = "n", command = "rolling-rule"))
                        )
                    )
                )
            )
        )
        val resolver = resolverOver(catalog)
        assertEquals("rolling-rule", firstCommand(resolver.resolve("arch", "latest")))
        assertEquals("rolling-default", firstCommand(resolver.resolve("arch", "lat")))
        assertNull(QuickDistroKeys.versionKey("  "))
    }

    @Test
    fun aCatalogLoadFailureResolvesToNothingWithoutThrowing() {
        val resolver = QuickCommandsResolver(QuickCommandsCatalogLoader { null })
        assertTrue(resolver.resolve("debian", "13").isEmpty())
        assertTrue(resolver.resolve(null, null).isEmpty())
    }

    // ── QuickDistroKeys ─────────────────────────────────────────────────

    @Test
    fun distroKeyPrefersTheDeclarationThenTheCatalogIdThenTheProbedName() {
        assertEquals("debian", QuickDistroKeys.distroKey("debian", "ubuntu", "Alpine Linux"))
        assertEquals("ubuntu", QuickDistroKeys.distroKey(null, "ubuntu", "Alpine Linux"))
        assertEquals("arch", QuickDistroKeys.distroKey(null, "archlinux", null))
        assertEquals("arch", QuickDistroKeys.distroKey("archlinuxarm", null, null))
        assertEquals("arch", QuickDistroKeys.distroKey("archarm", null, null))
        assertEquals("alpine", QuickDistroKeys.distroKey(null, null, "Alpine Linux"))
        assertEquals("fedora", QuickDistroKeys.distroKey(null, null, "Fedora"))
        assertNull(QuickDistroKeys.distroKey(null, null, "Linux From Scratch"))
        assertNull(QuickDistroKeys.distroKey(null, "gentoo", "Gentoo"))
    }

    @Test
    fun versionKeyStripsProseFromPersistedVersions() {
        assertEquals("12", QuickDistroKeys.versionKey("12 (Bookworm)"))
        assertEquals("22.04.5", QuickDistroKeys.versionKey("22.04.5 LTS (Jammy)"))
        assertEquals("latest", QuickDistroKeys.versionKey("latest (rolling)"))
        assertEquals("13", QuickDistroKeys.versionKey("13"))
        assertNull(QuickDistroKeys.versionKey("  "))
        // Declared wins over persisted, and each is normalized first.
        assertEquals("13", QuickDistroKeys.versionKey("13", "12 (Bookworm)"))
        assertEquals("12", QuickDistroKeys.versionKey(null, "12 (Bookworm)"))
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun branchOf(command: String) = QuickCommandsBranch(
        categories = listOf(action("n", "The Node", command))
    )

    private fun action(id: String, name: String, command: String = "$id-command") = QuickCommandNode(
        id = id,
        name = name,
        type = QuickNodeType.ACTION,
        command = command
    )

    private fun firstCommand(nodes: List<QuickCommandNode>): String? =
        nodes.firstNotNullOfOrNull { flattenCommands(it).firstOrNull() }

    private fun flattenCommands(node: QuickCommandNode): List<String> =
        listOfNotNull(node.command) + node.children.flatMap { flattenCommands(it) }
}
