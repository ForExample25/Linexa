package com.fem.linexa.service.distro

import com.fem.linexa.domain.distro.DistroSourceResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches [com.fem.linexa.domain.distro.DistroSource.Resolved.resolverId]
 * to whichever [DistroSourceResolver] declares that [DistroSourceResolver.id].
 * Adding a new resolver (a new "kind of index" some future distro's source
 * needs) is a new class + one `@IntoSet` binding in `AppModule` — this
 * class doesn't change.
 */
@Singleton
class DistroSourceResolverRegistry @Inject constructor(
    resolvers: Set<@JvmSuppressWildcards DistroSourceResolver>
) {
    private val byId: Map<String, DistroSourceResolver> = resolvers.associateBy { it.id }

    fun resolverFor(id: String): DistroSourceResolver? = byId[id]
}
