package com.fem.linexa.service.commands

import org.yaml.snakeyaml.Yaml

/**
 * Shape parser for the built-in Quick Commands catalog
 * (`assets/commands/quick_commands.yaml`) — YAML text into
 * [QuickCommandsCatalogParseResult].
 *
 * ## Failure semantics (catalog requirement 16)
 *
 * The file is project-owned, so *structural* garbage — not YAML, no
 * `linexa_quick_commands` mapping, wrong schema version — fails the whole
 * load loudly ([QuickCommandsCatalogParseResult.Invalid]) and the UI renders
 * without built-ins rather than crashing. But an individual malformed NODE is
 * a local defect, and one broken entry must not take 99 good entries with it:
 * a node that fails any shape or semantic rule is **skipped
 * deterministically** (first failure wins, siblings survive) and the reason
 * is recorded in [QuickCommandsCatalogParseResult.issues] for the loader to
 * log.
 *
 * Semantic rules live here too (mirroring the RootFsMetadataParser/
 * Validator split would mean a second walk over the same tree for checks
 * that are trivially local): type/field coherence, duplicate ids, empty
 * commands, unknown fields, unsupported distribution keys, malformed version
 * selectors. The rules ARE the validator — see the class kdoc on each
 * `reject*` helper for why each check exists.
 *
 * Pure and JVM-testable: bytes in, result out, no Android.
 */
object QuickCommandsCatalogParser {

    private const val ROOT_KEY = "linexa_quick_commands"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_DISTRIBUTIONS = "distributions"
    private const val KEY_DEFAULT = "default"
    private const val KEY_VERSIONS = "versions"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_PATCHES = "patches"
    private const val KEY_ID = "id"
    private const val KEY_TYPE = "type"
    private const val KEY_NAME = "name"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_CHILDREN = "children"
    private const val KEY_COMMAND = "command"
    private const val KEY_ACTION = "action"
    private const val KEY_AS_ROOT = "as_root"
    private const val KEY_ICON = "icon"

    /** Caps so a defective file cannot bloat memory or the UI. */
    const val MAX_BYTES: Int = 512 * 1024
    const val MAX_NODES = 500
    const val MAX_NAME_CHARS = 80
    const val MAX_DESCRIPTION_CHARS = 200
    const val MAX_COMMAND_CHARS = 512
    const val MAX_ID_CHARS = 64

    /** A safe identifier token: the id keys run state and must never carry shell/struct meaning. */
    private val SAFE_ID = Regex("[a-z0-9][a-z0-9._-]{0,${MAX_ID_CHARS - 1}}")

    /** A version selector: quoted string, printable, no whitespace or structural characters. */
    private val SAFE_SELECTOR = Regex("[A-Za-z0-9.+_-]{1,32}")

    sealed interface Result {
        /** The catalog parsed (possibly with per-node [issues] — those nodes were skipped). */
        data class Parsed(
            val catalog: QuickCommandsCatalog,
            val issues: List<String> = emptyList()
        ) : Result

        /** The file is unusable as a whole; [reason] names the first structural defect. */
        data class Invalid(val reason: String) : Result
    }

    fun parse(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result.Invalid("catalog is empty")
        if (bytes.size > MAX_BYTES) return Result.Invalid("catalog exceeds $MAX_BYTES bytes")

        val root = try {
            Yaml().load<Any?>(bytes.inputStream().buffered())
        } catch (e: Exception) {
            return Result.Invalid("not valid YAML: ${e.message}")
        }
        if (root !is Map<*, *>) return Result.Invalid("top level must be a mapping")

        val block = root[ROOT_KEY] as? Map<*, *>
            ?: return Result.Invalid("missing '$ROOT_KEY' mapping")
        rejectUnknownKeys(
            block, ROOT_KEY, KEY_SCHEMA_VERSION, KEY_DISTRIBUTIONS
        )?.let { return Result.Invalid(it) }

        val schemaVersion = (block[KEY_SCHEMA_VERSION] as? Long)?.toInt()
            ?: (block[KEY_SCHEMA_VERSION] as? Int)
            ?: return Result.Invalid("missing or non-integer '$ROOT_KEY.$KEY_SCHEMA_VERSION'")
        if (schemaVersion != QuickCommandsCatalog.SUPPORTED_SCHEMA_VERSION) {
            return Result.Invalid(
                "unsupported $ROOT_KEY.$KEY_SCHEMA_VERSION $schemaVersion " +
                    "(this build understands ${QuickCommandsCatalog.SUPPORTED_SCHEMA_VERSION})"
            )
        }

        val distributionsBlock = block[KEY_DISTRIBUTIONS] as? Map<*, *>
            ?: return Result.Invalid("missing '$ROOT_KEY.$KEY_DISTRIBUTIONS' mapping")

        val issues = mutableListOf<String>()
        val distributions = linkedMapOf<String, QuickCommandsDistribution>()

        for ((key, value) in distributionsBlock) {
            val distroKey = key as? String
            if (distroKey == null) {
                issues += "distributions: non-string key skipped"
                continue
            }
            val normalized = distroKey.trim().lowercase()
            if (normalized !in QuickCommandsCatalog.KNOWN_DISTRIBUTION_KEYS) {
                // Unsupported distribution keys are detected and DROPPED, never
                // resolved: a typo'd key must not silently alias to a real
                // branch, and an unknown future key must not crash this build.
                issues += "distributions.$distroKey: unsupported distribution key — skipped"
                continue
            }
            val distroBlock = value as? Map<*, *>
            if (distroBlock == null) {
                issues += "distributions.$distroKey: must be a mapping — skipped"
                continue
            }
            val unknownDistroKey = rejectUnknownKeys(
                distroBlock, "distributions.$distroKey", KEY_DEFAULT, KEY_VERSIONS
            )
            if (unknownDistroKey != null) {
                issues += "distributions.$distroKey: $unknownDistroKey — skipped"
                continue
            }

            // `default:` is a mapping carrying `categories:` (and only that key).
            val defaultBlock = distroBlock[KEY_DEFAULT] as? Map<*, *>
            if (defaultBlock == null) {
                issues += "distributions.$distroKey: '$KEY_DEFAULT' must be a mapping — branch skipped"
                continue
            }
            val unknownDefaultKey = rejectUnknownKeys(
                defaultBlock, "distributions.$distroKey.$KEY_DEFAULT", KEY_CATEGORIES
            )
            if (unknownDefaultKey != null) {
                issues += "distributions.$distroKey.$KEY_DEFAULT: $unknownDefaultKey — branch skipped"
                continue
            }
            val defaultCategories = parseCategoryList(
                defaultBlock[KEY_CATEGORIES], "distributions.$distroKey.$KEY_DEFAULT.$KEY_CATEGORIES", issues
            )
            val versions = parseVersionRules(
                distroBlock[KEY_VERSIONS], "distributions.$distroKey.$KEY_VERSIONS", issues
            )

            distributions[normalized] = QuickCommandsDistribution(
                key = normalized,
                default = QuickCommandsBranch(categories = defaultCategories),
                versions = versions
            )
        }

        if (distributions.isEmpty()) {
            return Result.Invalid("no usable distribution branch in '$ROOT_KEY.$KEY_DISTRIBUTIONS'")
        }

        return Result.Parsed(
            QuickCommandsCatalog(schemaVersion = schemaVersion, distributions = distributions),
            issues = issues
        )
    }

    // ── versions ────────────────────────────────────────────────────────

    private fun parseVersionRules(
        value: Any?,
        where: String,
        issues: MutableList<String>
    ): Map<String, QuickCommandsVersionRule> {
        if (value == null) return emptyMap()
        val block = value as? Map<*, *> ?: run {
            issues += "$where: must be a mapping of selector → rule — ignored"
            return emptyMap()
        }
        val out = linkedMapOf<String, QuickCommandsVersionRule>()
        for ((key, ruleValue) in block) {
            val selector = key as? String
            if (selector == null || !SAFE_SELECTOR.matches(selector.trim())) {
                issues += "$where.${key}: malformed version selector — skipped"
                continue
            }
            val normalizedSelector = selector.trim()
            if (normalizedSelector in out) {
                issues += "$where.$normalizedSelector: duplicate selector — first rule wins"
                continue
            }
            val ruleBlock = ruleValue as? Map<*, *>
            if (ruleBlock == null) {
                issues += "$where.$normalizedSelector: rule must be a mapping — skipped"
                continue
            }
            val unknownRuleKey = rejectUnknownKeys(ruleBlock, "$where.$normalizedSelector", KEY_PATCHES, KEY_CATEGORIES)
            if (unknownRuleKey != null) {
                issues += "$where.$normalizedSelector: $unknownRuleKey — skipped"
                continue
            }
            val patches = parsePatches(ruleBlock[KEY_PATCHES], "$where.$normalizedSelector.$KEY_PATCHES", issues)
            val categories = parseCategoryList(
                ruleBlock[KEY_CATEGORIES], "$where.$normalizedSelector.$KEY_CATEGORIES", issues
            )
            out[normalizedSelector] = QuickCommandsVersionRule(
                selector = normalizedSelector,
                patches = patches,
                categories = categories
            )
        }
        return out
    }

    private fun parsePatches(
        value: Any?,
        where: String,
        issues: MutableList<String>
    ): List<QuickCommandPatch> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: run {
            issues += "$where: must be a list of patch mappings — ignored"
            return emptyList()
        }
        val out = mutableListOf<QuickCommandPatch>()
        list.forEachIndexed { index, entry ->
            val block = entry as? Map<*, *> ?: run {
                issues += "$where[$index]: must be a mapping — skipped"
                return@forEachIndexed
            }
            rejectUnknownKeys(block, "$where[$index]", KEY_ID, KEY_NAME, KEY_DESCRIPTION, KEY_COMMAND)?.let {
                issues += "$where[$index]: $it — skipped"
                return@forEachIndexed
            }
            val id = text(block[KEY_ID], MAX_ID_CHARS)
            if (id == null || !SAFE_ID.matches(id)) {
                issues += "$where[$index]: patch needs a valid '$KEY_ID' — skipped"
                return@forEachIndexed
            }
            val name = text(block[KEY_NAME], MAX_NAME_CHARS)
            val description = text(block[KEY_DESCRIPTION], MAX_DESCRIPTION_CHARS)
            val command = commandText(block[KEY_COMMAND], "$where[$index]", issues)
            if (name == null && description == null && command == null) {
                issues += "$where[$index]: patch changes nothing — skipped"
                return@forEachIndexed
            }
            out += QuickCommandPatch(id = id, name = name, description = description, command = command)
        }
        return out
    }

    // ── nodes ───────────────────────────────────────────────────────────

    /** Parses a `categories:` list; a null/absent value is a normal empty branch. */
    private fun parseCategoryList(
        value: Any?,
        where: String,
        issues: MutableList<String>
    ): List<QuickCommandNode> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: run {
            issues += "$where: must be a list of node mappings — ignored"
            return emptyList()
        }
        val ids = HashSet<String>()
        val out = mutableListOf<QuickCommandNode>()
        for ((index, entry) in list.withIndex()) {
            if (out.size >= MAX_NODES) break
            val node = parseNode(
                entry, "$where[$index]", depth = 0, ids = ids, issues = issues
            ) ?: continue
            out += node
        }
        return out
    }

    /**
     * One node, or null when it violates any rule (recorded in [issues] and
     * deterministically skipped — the siblings survive). [ids] carries the
     * sibling ids already seen at EVERY level of this branch, so duplicate
     * ids are detected across the whole tree (the id keys run state).
     */
    private fun parseNode(
        entry: Any?,
        where: String,
        depth: Int,
        ids: MutableSet<String>,
        issues: MutableList<String>
    ): QuickCommandNode? {
        val block = entry as? Map<*, *> ?: run {
            issues += "$where: must be a mapping — skipped"
            return null
        }
        if (depth > MAX_DEPTH) {
            issues += "$where: deeper than $MAX_DEPTH levels — skipped"
            return null
        }
        rejectUnknownKeys(
            block, where, KEY_ID, KEY_TYPE, KEY_NAME, KEY_DESCRIPTION,
            KEY_CHILDREN, KEY_COMMAND, KEY_ACTION, KEY_AS_ROOT, KEY_ICON
        )?.let {
            issues += "$where: $it — skipped"
            return null
        }

        val id = text(block[KEY_ID], MAX_ID_CHARS)
        if (id == null || !SAFE_ID.matches(id)) {
            issues += "$where: missing or unsafe '$KEY_ID' — skipped"
            return null
        }
        if (!ids.add(id)) {
            issues += "$where: duplicate id '$id' — skipped"
            return null
        }
        val name = text(block[KEY_NAME], MAX_NAME_CHARS)
        if (name == null) {
            issues += "$where.$id: missing '$KEY_NAME' — skipped"
            return null
        }
        val type = QuickNodeType.fromYaml(text(block[KEY_TYPE], 20))
        if (type == null) {
            issues += "$where.$id: missing or unknown '$KEY_TYPE' — skipped"
            return null
        }
        val description = text(block[KEY_DESCRIPTION], MAX_DESCRIPTION_CHARS)
        val icon = text(block[KEY_ICON], 32)

        when (type) {
            QuickNodeType.ACTION -> {
                val asRoot = (block[KEY_AS_ROOT] as? Boolean) ?: false
                if (block.containsKey(KEY_AS_ROOT) && block[KEY_AS_ROOT] !is Boolean) {
                    issues += "$where.$id: '$KEY_AS_ROOT' must be a boolean — skipped"
                    return null
                }
                if (block.containsKey(KEY_CHILDREN)) {
                    issues += "$where.$id: an action must not declare '${KEY_CHILDREN}' — skipped"
                    return null
                }
                val command = if (block.containsKey(KEY_COMMAND)) {
                    commandText(block[KEY_COMMAND], "$where.$id", issues)
                } else {
                    null
                }
                if (command == null && !asRoot) {
                    // One report per node: a malformed command value was already
                    // recorded by commandText; only a MISSING key reports here.
                    if (!block.containsKey(KEY_COMMAND)) {
                        issues += "$where.$id: an executable action needs a non-empty '$KEY_COMMAND' — skipped"
                    }
                    return null
                }
                val actionKind = if (block.containsKey(KEY_ACTION)) {
                    QuickActionKind.fromYaml(text(block[KEY_ACTION], 20)) ?: run {
                        issues += "$where.$id: unknown '$KEY_ACTION' tag — skipped"
                        return null
                    }
                } else {
                    null
                }
                return QuickCommandNode(
                    id = id,
                    name = name,
                    type = type,
                    description = description,
                    children = emptyList(),
                    command = command,
                    actionKind = actionKind,
                    asRoot = asRoot,
                    icon = icon
                )
            }

            QuickNodeType.CATEGORY, QuickNodeType.GROUP, QuickNodeType.APPLICATION -> {
                if (block.containsKey(KEY_COMMAND)) {
                    // A navigational node that also executes is exactly the
                    // ambiguity this schema exists to remove.
                    issues += "$where.$id: a ${type.name.lowercase()} must not declare '${KEY_COMMAND}' — skipped"
                    return null
                }
                if (block.containsKey(KEY_ACTION)) {
                    issues += "$where.$id: a ${type.name.lowercase()} must not declare '${KEY_ACTION}' — skipped"
                    return null
                }
                if (block.containsKey(KEY_AS_ROOT)) {
                    issues += "$where.$id: a ${type.name.lowercase()} must not declare '${KEY_AS_ROOT}' — skipped"
                    return null
                }
                val childrenBlock = block[KEY_CHILDREN]
                if (childrenBlock == null) {
                    issues += "$where.$id: a ${type.name.lowercase()} needs '${KEY_CHILDREN}' — skipped"
                    return null
                }
                val childrenList = childrenBlock as? List<*> ?: run {
                    issues += "$where.$id: '${KEY_CHILDREN}' must be a list — skipped"
                    return null
                }
                if (childrenList.isEmpty()) {
                    issues += "$where.$id: '${KEY_CHILDREN}' must not be empty — drop the node instead"
                    return null
                }
                val children = mutableListOf<QuickCommandNode>()
                childrenList.forEachIndexed { childIndex, child ->
                    if (children.size >= MAX_NODES) return@forEachIndexed
                    parseNode(
                        child, "$where.$id.$KEY_CHILDREN[$childIndex]", depth + 1, ids, issues
                    )?.let { children += it }
                }
                if (children.isEmpty()) {
                    issues += "$where.$id: no usable children — skipped"
                    return null
                }
                return QuickCommandNode(
                    id = id,
                    name = name,
                    type = type,
                    description = description,
                    children = children,
                    icon = icon
                )
            }
        }
    }

    // ── scalar helpers ──────────────────────────────────────────────────

    /** A displayable single-line scalar, trimmed and length-capped; null when absent/empty. */
    private fun text(value: Any?, maxChars: Int): String? {
        val s = when (value) {
            null -> return null
            is String -> value
            is Long, is Int, is Double, is Float, is Boolean -> value.toString()
            else -> return null
        }
        val cleaned = s.map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > maxChars) cleaned.take(maxChars).trimEnd() else cleaned
    }

    /**
     * The guest command: single-line (control characters refused — the exec
     * bridge and the card-consent rule both require it), non-blank,
     * length-capped. A malformed command value is recorded and the node is
     * skipped by the caller.
     */
    private fun commandText(value: Any?, where: String, issues: MutableList<String>): String? {
        if (value == null) return null
        if (value !is String) {
            issues += "$where: '$KEY_COMMAND' must be a string — skipped"
            return null
        }
        if (value.any { it.isISOControl() }) {
            issues += "$where: '$KEY_COMMAND' contains control characters — skipped"
            return null
        }
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            issues += "$where: '$KEY_COMMAND' is blank — skipped"
            return null
        }
        if (trimmed.length > MAX_COMMAND_CHARS) {
            issues += "$where: '$KEY_COMMAND' exceeds $MAX_COMMAND_CHARS characters — skipped"
            return null
        }
        return trimmed
    }

    /** The one unknown-key check, shared by every mapping level. */
    private fun rejectUnknownKeys(block: Map<*, *>, where: String, vararg supported: String): String? {
        val allowed = supported.toSet()
        for (key in block.keys) {
            val k = key as? String ?: return "$where: non-string key"
            if (k !in allowed) return "unknown key '$k'"
        }
        return null
    }

    /** Deepest allowed nesting: category → group → application → action, with headroom. */
    private const val MAX_DEPTH = 5
}
