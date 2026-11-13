package com.fem.linexa.service.manager

import com.fem.linexa.service.distro.DistroSourceResolverRegistry
import com.fem.linexa.service.distro.LinuxContainersResolver
import com.fem.linexa.service.installer.InstallRecoveryManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.72 — Roadmap Item 6: the singleton [LinexaManager] implementation. It
 * only forwards to the injected instances — grouping, no behavior. All four
 * grouped classes keep their own `@Singleton` scopes and constructor
 * injection, so existing direct injection sites are unaffected.
 */
@Singleton
class DefaultLinexaManager @Inject constructor(
    override val bootstrapManager: BootstrapManager,
    override val installRecoveryManager: InstallRecoveryManager,
    override val distroResolverRegistry: DistroSourceResolverRegistry,
    override val linuxContainersResolver: LinuxContainersResolver
) : LinexaManager
