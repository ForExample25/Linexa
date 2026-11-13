package com.fem.linexa.domain.runtime

/**
 * Workspace feature id for Shared Storage.
 *
 * A plain string constant (`"storage"`) with no PRoot-specific meaning — it
 * is matched against `WorkspaceContent.builtinFeatureDefaults` and never
 * names a container mechanic. It was originally declared inside
 * `service.runtime.container.PRootContainerRuntime.companion` because that
 * was the only implementation of the storage feature, but the original
 * placement made it a *container-seam* constant. That layering leak was the
 * `uiSourceMustNotImportConcreteContainerImplementations` rule's frozen debt
 * in `ImportBoundaryTest` (Rule 3 — the `const val` source-scan backstop,
 * which exists precisely because Kotlin inlines `const val` reads at the
 * call site and leaves no bytecode trace for Rule 2 to see), and is the
 * reason this file exists.
 *
 * Lives in `domain.runtime` alongside [DEFAULT_TERMINAL_ID] /
 * [CommandResult] / [RuntimeState] / [TerminalState] for the same Phase 6a
 * precedent: it is a *runtime-domain-level shared constant*, not part of the
 * container seam. The practical consequence: `ui.screens.workspace.FeatureConfig`
 * (whose `NON_CONFIGURABLE_RATIONALE` names this feature) can reference it
 * without importing `PRootContainerRuntime` — a concrete container
 * implementation the UI is not supposed to name under any of the three
 * `ImportBoundaryTest` rules.
 *
 * Kept as a top-level `const val` under the same name so the move is
 * import-only at every one of its call sites — no companion-object alias,
 * no wrapper. The other four feature ids (`FEATURE_SSH`, `FEATURE_AUDIO`,
 * `FEATURE_CLIPBOARD`, `FEATURE_NOTIFICATIONS`) live on their respective
 * provisioner objects in `service.runtime.features.*`, which is fine: the
 * layering test does not forbid the UI from naming the *public feature
 * vocabulary*; it forbids the UI from naming PRoot mechanics. Shared Storage
 * has no provisioner of its own (the mount is built into the container
 * launch argv), so its feature id has no other natural home — and the
 * domain layer is the right place for a vocabulary shared between ui,
 * service, and the data layer's `WorkspaceContent.builtinFeatureDefaults`.
 */
const val FEATURE_STORAGE: String = "storage"

/**
 * Workspace feature id for the **Desktop run mode** (Desktop Modes work, 2026-08).
 *
 * Like [FEATURE_STORAGE], a plain string constant with no PRoot-specific
 * meaning. Its consumer is two-sided and both sides are honest:
 *
 * - the **toggle** feeds [com.fem.linexa.domain.workspace.WorkspaceCapabilities.desktopEnabled],
 *   which gates the Desktop entry in the workspace top bar. The built-in
 *   default is **off** (2026-09-02 desktop prerequisites): a fresh workspace
 *   has Desktop OFF until the user enables it, and enabling is gated on a
 *   live rootfs probe — a persisted `desktop=true` from before the change,
 *   or one written without a desktop present, does NOT by itself light the
 *   entry (see [com.fem.linexa.domain.workspace.WorkspaceCapabilities.desktopUsable]);
 *   the WebView run mode needs no X client and enables without a desktop.
 * - the **settings** ([com.fem.linexa.service.runtime.features.FeatureSettings]'s
 *   `feature.desktop.*` attributes) decide *how* the Desktop screen runs —
 *   which run mode (VNC / WebView) and that mode's parameters. They are read
 *   by [com.fem.linexa.ui.screens.desktop.DesktopViewModel] at start time.
 *
 * Lives in `domain.runtime` next to [FEATURE_STORAGE] for the same layering
 * reason: the vocabulary is shared by `ui.screens.workspace.FeatureConfig`
 * (which must not import concrete container implementations) and by
 * `service.runtime.features.FeatureSettings`, and a domain-level constant
 * serves both without a layering leak.
 */
const val FEATURE_DESKTOP: String = "desktop"

/**
 * Workspace feature id for **GPU acceleration** (host VirGL server + guest
 * Mesa `virpipe` over the vtest socket, 2026-09 GPU work).
 *
 * Same placement rationale as [FEATURE_STORAGE]: the vocabulary is shared by
 * `ui.screens.workspace.FeatureConfig` (which must not import concrete
 * container/runtime implementations) and by
 * `com.fem.linexa.service.runtime.graphics.WorkspaceGpuSupervisor`'s launch
 * integration in `PRootContainerRuntime`, and a domain-level constant serves
 * both without a layering leak. The toggle is read on the container launch
 * path (`PRootContainerRuntime.isGpuEnabled`), exactly like Shared Storage —
 * the host-side daemon and the guest env it provisions are part of the
 * launch argv/env, not a post-launch reconciler step.
 *
 * Unlike the §1.117-removed decorative `gpu` toggle, this id has a real
 * consumer: the supervisor probes the bundled host binaries
 * (`VirglHostServer.probe`), supervises the server process, and reports its
 * condition through [com.fem.linexa.service.runtime.WorkspaceHealthRegistry].
 * When the binaries are absent the feature reports a Degraded health entry
 * and the guest keeps software rendering — the toggle is never decorative.
 */
const val FEATURE_GPU: String = "gpu"
