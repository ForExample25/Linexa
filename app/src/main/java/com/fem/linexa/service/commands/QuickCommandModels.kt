package com.fem.linexa.service.commands

/**
 * The typed model of the built-in Quick Commands catalog
 * (`assets/commands/quick_commands.yaml`).
 *
 * ## One node type per meaning — nothing inferred
 *
 * The legacy `.tiny.yaml` `quick_commands` tree encoded everything in shape
 * and convention: a group was "a mapping with `commands:`", an application
 * was "a group whose children happen to be named Install/Uninstall", and the
 * UI had to guess. This model makes every one of those decisions explicit
 * data ([QuickNodeType]): a [QuickCommandNode] IS a category, a group, an
 * application or an action, and an executable command exists ONLY on an
 * [QuickNodeType.ACTION] node. No consumer ever inspects names or children to
 * decide what a node is.
 *
 * ## Hierarchy
 *
 * `Distribution → Version rule → Category → Group/Application → Action`
 * is expressed structurally: [QuickCommandsBranch.categories] is the ordered
 * root of a tree of [QuickCommandNode.children], version rules patch or
 * extend that tree ([QuickCommandsVersionRule]), and the resolution rules
 * (exact selector → longest prefix match → distribution default; never
 * another distribution's set) live in [QuickCommandsResolver].
 *
 * Pure data — no Android, no YAML — so the whole resolution chain is
 * JVM-testable.
 */
enum class QuickNodeType {
    /** A top navigational level (System, Software Management, Browsers…). */
    CATEGORY,

    /** A nested navigational level (JetBrains IDEs…). */
    GROUP,

    /**
     * A software item (Chromium Browser, PyCharm) whose children are its
     * actions (Install / Uninstall). Never itself executable.
     */
    APPLICATION,

    /**
     * An executable leaf. The only node type carrying [QuickCommandNode.command].
     */
    ACTION;

    companion object {
        /** The YAML spelling of each type. */
        fun fromYaml(value: String?): QuickNodeType? = when (value?.trim()?.lowercase()) {
            "category" -> CATEGORY
            "group" -> GROUP
            "application" -> APPLICATION
            "action" -> ACTION
            else -> null
        }
    }
}

/**
 * The semantic tag an [QuickNodeType.ACTION] may carry — the same closed
 * vocabulary the native `linexa.shortcuts` schema (schema v2) already uses,
 * so an action's EFFECT is stated once, everywhere.
 */
enum class QuickActionKind(val yaml: String) {
    INSTALL("install"),
    REMOVE("remove"),
    UPDATE("update"),
    LAUNCH("launch"),
    CHECK("check");

    companion object {
        fun fromYaml(value: String?): QuickActionKind? =
            entries.firstOrNull { it.yaml == value?.trim()?.lowercase() }
    }
}

/**
 * One node of the resolved command tree.
 *
 * @param id unique within its branch; also the run-state key namespace root
 *   (the UI namespaces resolved ids, so catalog ids cannot collide with the
 *   user-command or one-off-run keys).
 * @param name the user-facing title (a single segment — parents are stated by
 *   the tree, never repeated in the name).
 * @param type what this node is — see [QuickNodeType].
 * @param description optional user-facing explanation.
 * @param children the nested nodes; non-empty only for CATEGORY / GROUP /
 *   APPLICATION (enforced by the validator).
 * @param command the guest shell command; non-null only for ACTION
 *   (enforced by the validator).
 * @param actionKind optional semantic tag on an ACTION node.
 * @param asRoot true when the action runs in the root-emulated terminal.
 * @param icon optional icon key, resolved against the UI's fixed table.
 */
data class QuickCommandNode(
    val id: String,
    val name: String,
    val type: QuickNodeType,
    val description: String? = null,
    val children: List<QuickCommandNode> = emptyList(),
    val command: String? = null,
    val actionKind: QuickActionKind? = null,
    val asRoot: Boolean = false,
    val icon: String? = null
)

/**
 * One version-specific rule: field patches applied onto the default tree by
 * node id, plus whole categories appended after the defaults. Deliberately a
 * DELTA, not a replacement — a version that changes two commands must not
 * duplicate the entire branch (catalog requirement 6).
 */
data class QuickCommandsVersionRule(
    /** The version selector exactly as written in the YAML (quoted). */
    val selector: String,
    /** Field overrides, applied depth-first onto the default tree by [QuickCommandPatch.id]. */
    val patches: List<QuickCommandPatch> = emptyList(),
    /** Categories appended after the patched default tree, in file order. */
    val categories: List<QuickCommandNode> = emptyList()
)

/**
 * A field override for the node with [id] (matched anywhere in the default
 * tree). Only the fields present are replaced; everything else is inherited.
 */
data class QuickCommandPatch(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val command: String? = null
)

/** A distribution's default branch: its ordered category tree. */
data class QuickCommandsBranch(
    val categories: List<QuickCommandNode> = emptyList()
)

/**
 * One distribution of the catalog.
 *
 * @param key the normalized distribution key (`ubuntu`, `debian`, `fedora`,
 *   `arch`, `alpine`, `common`).
 * @param default the branch used when no version rule matches.
 * @param versions the version rules keyed by their selector.
 */
data class QuickCommandsDistribution(
    val key: String,
    val default: QuickCommandsBranch = QuickCommandsBranch(),
    val versions: Map<String, QuickCommandsVersionRule> = emptyMap()
)

/** The whole parsed-and-validated catalog. */
data class QuickCommandsCatalog(
    val schemaVersion: Int,
    /** Keyed by normalized distribution key. */
    val distributions: Map<String, QuickCommandsDistribution>
) {
    companion object {
        /** The one schema version this build parses. */
        const val SUPPORTED_SCHEMA_VERSION = 1

        /** The portable branch every workspace receives. */
        const val KEY_COMMON = "common"

        /** The distribution keys this build's catalog may declare. */
        val KNOWN_DISTRIBUTION_KEYS: Set<String> = setOf(
            KEY_COMMON, "ubuntu", "debian", "fedora", "arch", "alpine"
        )
    }
}
