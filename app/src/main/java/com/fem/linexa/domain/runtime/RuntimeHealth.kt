package com.fem.linexa.domain.runtime

/**
 * Workspace Runtime Health (§6 of the transformation spec — the observable
 * `DEGRADED` member of the workspace lifecycle).
 *
 * ## Why health is a field of [RuntimeState.Running], not a sibling state
 *
 * The transformation spec's state machine (STOPPED → STARTING → RUNNING →
 * DEGRADED → STOPPING → STOPPED) is *conceptual*: it requires that "running"
 * stop meaning merely "some process exists" and that component failures be
 * observable without logcat. A literal top-level `Degraded` state, however,
 * would break the semantic every consumer already relies on: dozens of
 * `state is RuntimeState.Running` checks (resume paths, desktop gating,
 * re-entrancy guards) must keep treating a degraded workspace as *running*
 — a degraded workspace still serves its terminals, its desktop and its
 * persistence. Modeling degradation as [RuntimeState.Running.health] keeps
 * the transition safety (Running ⇒ Running with different health) while
 * making the degraded condition a first-class, observable fact.
 *
 * A [Degraded] health is reported per *component* ([RuntimeDegradation]) —
 * ssh, notifications, clipboard, audio, network, desktop, persistence,
 * `daemon:<name>` — so the UI can show exactly which subsystem fell over
 * while the workspace itself keeps running (§28 failure-recovery matrix:
 * a desktop failure keeps the terminal alive; a network failure keeps the
 * runtime alive; a persistence failure must not silently claim persistence).
 */

/** One component-level failure of a running workspace. */
data class RuntimeDegradation(
    /** Stable component key, e.g. `"ssh"`, `"network"`, `"daemon:xvnc"`. */
    val component: String,
    /** Human-readable, user-surfaceable description of what failed. */
    val detail: String,
) {
    /** Presentation label for the diagnostics surface (§29). */
    val componentLabel: String
        get() = component.removePrefix(DAEMON_COMPONENT_PREFIX)
            .replaceFirstChar { it.uppercase() }

    companion object {
        /** Component key prefix for supervised guest daemons. */
        const val DAEMON_COMPONENT_PREFIX = "daemon:"
    }
}

/** The aggregate health of a [RuntimeState.Running] workspace. */
sealed interface RuntimeHealth {

    /** Every tracked component is either healthy or not yet attempted. */
    data object Healthy : RuntimeHealth

    /** At least one component failed while the workspace keeps running. */
    data class Degraded(val degradations: List<RuntimeDegradation>) : RuntimeHealth
}

/** Convenience readers so callers never destructure the Running payload. */
val RuntimeState.isDegraded: Boolean
    get() = this is RuntimeState.Running && health is RuntimeHealth.Degraded

/**
 * The degradation list of a running workspace — empty when the workspace is
 * healthy, stopped, or starting (degradation is only meaningful while running).
 */
fun RuntimeState.degradations(): List<RuntimeDegradation> =
    (this as? RuntimeState.Running)?.let { running ->
        (running.health as? RuntimeHealth.Degraded)?.degradations.orEmpty()
    }.orEmpty()
