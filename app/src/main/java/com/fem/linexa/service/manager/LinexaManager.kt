package com.fem.linexa.service.manager

import com.fem.linexa.service.distro.DistroSourceResolverRegistry
import com.fem.linexa.service.distro.LinuxContainersResolver
import com.fem.linexa.service.installer.InstallRecoveryManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager

/**
 * §1.72 — Roadmap Item 6: the injectable "Linexa Manager" seam that groups
 * [BootstrapManager], [InstallRecoveryManager], and the distro-catalog
 * resolvers ([DistroSourceResolverRegistry] / [LinuxContainersResolver])
 * under ONE thing to inject instead of several — the seam future Manager-UI
 * code (distro browsing, bootstrap-update status, backup/restore) will use.
 *
 * Purely organizational (Item 6's scope guard): no business logic lives here,
 * none was moved out of the grouped classes, and their public APIs are
 * untouched. Existing direct injection sites (`ImportService`,
 * `RuntimeService`, `DownloadService`, `LinexaApplication`, …) keep their
 * current dependencies exactly as they are — this facade is additive, not a
 * forced migration.
 */
interface LinexaManager {
    /** On-device bootstrap layout owner (§1.45/§1.71). */
    val bootstrapManager: BootstrapManager

    /** Interrupted-install detection and safe cleanup (§1.20). */
    val installRecoveryManager: InstallRecoveryManager

    /** Dispatches `DistroSource.Resolved.resolverId` to the bound resolvers. */
    val distroResolverRegistry: DistroSourceResolverRegistry

    /** The linuxcontainers.org image-server resolver (Fedora). */
    val linuxContainersResolver: LinuxContainersResolver
}
