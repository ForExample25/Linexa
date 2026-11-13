package com.fem.linexa.domain.packagemanager

/**
 * §24 — the attribute key under which the Package Manager screen's detection
 * probe persists a workspace's detected package-manager key
 * ([PackageManagerDetector] vocabulary: `apt`, `pacman`, `apk`, `dnf`,
 * `zypper`).
 *
 * Historically this class also resolved the per-PM "update system" command
 * for the hard-coded built-in shortcuts — that whole selection layer is gone:
 * the project's Quick Commands catalog
 * (`assets/commands/quick_commands.yaml`) now carries the exact per-
 * distribution commands itself, resolved by
 * [com.fem.linexa.service.commands.QuickCommandsResolver]. What remains here
 * is the persisted detection fact, which the Package Manager feature still
 * writes and the workspace still owns. The attribute is harmless when no
 * command consumer reads it — it remains a truthful record of what the guest
 * ships.
 */
object UpdateCommandResolver {

    /**
     * Attribute key under which the detected package-manager key is
     * persisted for a workspace.
     */
    const val ATTR_DETECTED_PM = "linexa.pm.detected"
}
