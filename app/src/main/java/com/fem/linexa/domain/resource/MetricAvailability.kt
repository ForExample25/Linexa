package com.fem.linexa.domain.resource

/**
 * Whether a metric is a real reading, is still waiting for the second sample a
 * delta metric needs, or cannot be read at all.
 *
 * Null is the value-level "unknown"; this enum carries the reason. The UI must
 * not collapse [PENDING] and [UNAVAILABLE] into the same dash: one resolves on
 * the next tick, the other does not resolve on this device without a different
 * data source.
 */
enum class MetricAvailability {
    AVAILABLE,
    PENDING,
    UNAVAILABLE
}
