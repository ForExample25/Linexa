package com.fem.linexa.domain.runtime

/**
 * Outcome of a one-shot, non-interactive command run inside a rootfs.
 *
 * A plain, carrier-only DTO: [exitCode], [output], [error] describe a
 * finished process and nothing more — there is no service-layer-specific
 * field here. It was originally declared inside
 * `service.runtime.container.ContainerRuntime` (next to the seam interface)
 * because that was the only place that produced it. The original placement
 * made it a *container-seam* type, so every domain class that consumed the
 * return value of `RuntimeBinder.execCommand(...)` (the 5 package-manager
 * providers, `WorkspaceFileManagerImpl`, and `PackageManagerDetector` — the
 * last two invisible to an `import` grep, the latter via local-variable type
 * inference alone) crossed the seam. That layering leak was the
 * `domainMustNotDependOnTheContainerSeam` rule's frozen debt in
 * `ImportBoundaryTest`, and is the reason this file exists.
 *
 * Lives in `domain.runtime` alongside [DEFAULT_TERMINAL_ID] /
 * [RuntimeState] / [TerminalState] for the same reason `DEFAULT_TERMINAL_ID`
 * does (Phase 6a precedent): it is a *runtime-domain-level value type*, not
 * part of the container seam. The practical consequence: every domain,
 * service, and ui class that consumes the result of a one-shot container
 * command can name it without importing from `service.runtime.container` —
 * the internal seam the UI is not supposed to reach into at all, and whose
 * concrete implementation the layering test forbids the UI from touching.
 *
 * Kept as a top-level `data class` under the same name so the move is
 * import-only at every one of its call sites — no mapping layer, no parallel
 * service DTO. The complexity a boundary-mapping type would add is not
 * justified here because `CommandResult` carries no service-layer-specific
 * field that the domain should not see.
 *
 * The producer side (`service.runtime.container.PRootContainerRuntime` and
 * friends) `import`s this type from here; the seam interface
 * (`service.runtime.container.ContainerRuntime.exec`/`execStreaming`) and
 * the runtime binder / guest-feature host surface declare it on their return
 * types from this same location.
 */
data class CommandResult(
    val exitCode: Int,
    val output: String,
    val error: String = ""
)
