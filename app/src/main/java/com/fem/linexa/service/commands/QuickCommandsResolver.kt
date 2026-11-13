package com.fem.linexa.service.commands

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the Quick Commands branch for a workspace, from the ONE catalog
 * ([QuickCommandsCatalogLoader]) — the domain layer that owns every
 * distribution/version decision, so the UI never does.
 *
 * ## The resolution contract
 *
 * Given a distribution key and a version:
 *
 *  1. **Exact version selector wins.** `13` matches the `debian` rule `"13"`.
 *  2. **Otherwise the most specific matching rule wins** — longest
 *     dot-component prefix (`22.04.5` matches `"22.04"` before it would
 *     match `"22"`; a prefix must end on a component boundary, so `12` never
 *     matches `127`). Non-numeric versions (`trixie`, `latest`) match only
 *     their literal spelling.
 *  3. **Otherwise the distribution default applies.**
 *  4. **A workspace whose distribution is unknown gets ONLY the `common`
 *     branch** — never another distribution's command set. There is no
 *     fallback from `alpine` to `debian`'s `apt` commands.
 *
 * The `common` branch (genuinely portable commands only) is appended for
 * EVERY resolved distribution, known or not.
 *
 * ## Where the inputs come from (and why this class doesn't decide)
 *
 * [QuickDistroKeys] maps a workspace's EXISTING identity — its declared
 * rootfs distro, its persisted catalog id attribute, its probed
 * [com.fem.linexa.domain.workspace.DistroInfo] — onto a catalog key. That
 * mapping is a pure function too, so the whole pipeline is testable without
 * Android, and no second detection system is created: the resolver trusts
 * whatever identity the caller already established.
 *
 * Pure and deterministic: the same (key, version) always yields the same
 * tree, in catalog order.
 */
@Singleton
class QuickCommandsResolver @Inject constructor(
    private val loader: QuickCommandsCatalogLoader
) {

    /**
     * The resolved command tree for [distroKey] at [version]: the
     * distribution's own categories (default branch + matched version rule)
     * followed by the portable `common` categories.
     *
     * @param distroKey a catalog distribution key (see [QuickDistroKeys]) or
     *   null/unknown — the unknown case resolves to the common branch only.
     * @param version the workspace's version string, already normalized by
     *   [QuickDistroKeys.versionKey]; null/blank selects the default branch.
     */
    fun resolve(distroKey: String?, version: String?): List<QuickCommandNode> {
        val catalog = loader.catalog() ?: return emptyList()
        val out = mutableListOf<QuickCommandNode>()

        catalog.distributions[distroKey?.trim()?.lowercase(Locale.US)]?.let { distro ->
            out += resolveBranch(distro, version)
        }
        catalog.distributions[QuickCommandsCatalog.KEY_COMMON]?.let { common ->
            out += resolveBranch(common, null)
        }
        return out
    }

    /**
     * The version rule for [version], applying the exact → longest-prefix →
     * null ordering. Visible for tests; [resolve] is the only production caller.
     */
    fun matchingRule(distribution: QuickCommandsDistribution, version: String?): QuickCommandsVersionRule? {
        val v = version?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 1. exact
        distribution.versions[v]?.let { return it }
        // 2. longest dot-component prefix (component boundary respected)
        for (prefix in versionCandidates(v)) {
            distribution.versions[prefix]?.let { return it }
        }
        return null
    }

    private fun resolveBranch(distribution: QuickCommandsDistribution, version: String?): List<QuickCommandNode> {
        val rule = matchingRule(distribution, version)
        val base = distribution.default.categories
        if (rule == null) return base
        return base.map { applyRule(it, rule) } + rule.categories
    }

    /** Applies [rule]'s patches onto [node] (identity-preserving, order-preserving). */
    private fun applyRule(node: QuickCommandNode, rule: QuickCommandsVersionRule): QuickCommandNode {
        val patched = if (node.children.isEmpty()) {
            node
        } else {
            node.copy(children = node.children.map { applyRule(it, rule) })
        }
        val patch = rule.patches.firstOrNull { it.id == node.id } ?: return patched
        return patched.copy(
            name = patch.name ?: patched.name,
            description = patch.description ?: patched.description,
            command = patch.command ?: patched.command
        )
    }

    companion object {
        /**
         * The version candidates [version] should be tried against, most
         * specific first: `22.04.5` → `22.04` → `22`. Mirrors the dot-component
         * convention [com.fem.linexa.service.rootfs.BundledRootfsMetadataProvider]
         * already established for bundled profiles, so the app matches
         * versions the same way everywhere.
         */
        fun versionCandidates(version: String): List<String> {
            val v = version.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
            val components = v.split('.')
            if (components.size < 2) return emptyList()
            val out = mutableListOf<String>()
            for (take in components.size - 1 downTo 1) {
                val candidate = components.take(take).joinToString(".")
                // Every dropped component must be numeric: a non-numeric tail
                // ("latest", "sid") never produces prefixes. Also guards
                // against matching "12" against version "12.7-sid".
                if (components.drop(take).any { !it.matches(NUMERIC) }) break
                out += candidate
            }
            return out
        }

        private val NUMERIC = Regex("[0-9]+")
    }
}

/**
 * Maps a workspace's EXISTING distribution identity onto a catalog key and a
 * normalized version string. Pure string work — no detection happens here.
 *
 * The three identity sources, in the order the caller should prefer them
 * (strongest declaration first):
 *
 * 1. the rootfs image's **declared** distro id (`linexa.distro.id`,
 *    os-release semantics: `debian`, `ubuntu`, `alpine`, `arch…`, `fedora`);
 * 2. the workspace's persisted **catalog id** attribute
 *    (`distroId`: the [com.fem.linexa.domain.distro.DistroCatalog] id —
 *    note Arch's catalog id is `archlinux`);
 * 3. the **probed distro name** (the `DistroInfo.name` the install pipeline
 *    persisted, e.g. `Debian`, `Arch Linux ARM`).
 *
 * Unknown inputs produce `null` — the resolver's cue to use the common
 * branch only. An unknown distribution NEVER falls back to another
 * distribution's commands.
 */
object QuickDistroKeys {

    /**
     * The catalog key for [declaredDistroId] / [catalogId] / [probedName],
     * first non-null wins. Every alias the project already recognizes for
     * Arch (`arch`, `archarm`, `archlinux`, `archlinuxarm` — see
     * [com.fem.linexa.service.rootfs.RootFsDescriptor.distroIdOf]) maps to
     * the catalog's `arch` key.
     */
    fun distroKey(declaredDistroId: String?, catalogId: String?, probedName: String?): String? {
        for (candidate in listOf(declaredDistroId, catalogId, probedName)) {
            val key = normalizeKey(candidate) ?: continue
            return key
        }
        return null
    }

    private fun normalizeKey(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.US).takeIf { !it.isNullOrEmpty() } ?: return null
        return when (value) {
            "debian" -> "debian"
            "ubuntu" -> "ubuntu"
            "alpine", "alpine linux" -> "alpine"
            "fedora" -> "fedora"
            "arch", "archarm", "archlinux", "archlinuxarm", "arch linux", "arch linux arm" -> "arch"
            else -> null
        }
    }

    /**
     * The version to resolve rules against: the declared version when the
     * image declared one, else the persisted probed version. Both are
     * normalized by [versionKey] first — persisted versions carry prose
     * (`"12 (Bookworm)"`, `"22.04.5 LTS (Jammy)"`) that the rules never
     * match on.
     */
    fun versionKey(declaredVersion: String?, persistedVersion: String?): String? =
        versionKey(declaredVersion) ?: versionKey(persistedVersion)

    /**
     * Normalizes a version string to the comparable selector: the first
     * whitespace-separated token with any parenthetical removed.
     * `"12 (Bookworm)"` → `12`; `"22.04.5 LTS (Jammy)"` → `22.04.5`;
     * `"latest (rolling)"` → `latest`; null for blank input.
     */
    fun versionKey(raw: String?): String? =
        raw?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.substringBefore(' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
