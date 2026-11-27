package com.fem.linexa.data.hostinfo

/**
 * Read `BatteryManager.EXTRA_PRESENT` without assuming a single encoding.
 *
 * The platform writes this extra as a **Boolean** (`BatteryService` calls
 * `putExtra(EXTRA_PRESENT, boolean)`), while some SDK documentation describes
 * it as an integer. Reading it with `Intent.getIntExtra` therefore throws a
 * `ClassCastException` that `BaseBundle` swallows and logs as
 * "Attempt to cast generated internal exception" — once per host-info tick
 * (every two seconds on the device), while presence silently stayed unknown.
 *
 * Accept both encodings and treat anything else as unknown rather than
 * guessing. `null` means "the broadcast did not say" (the caller's honest
 * unknown), which matches the previous `-1` sentinel behavior.
 */
internal fun batteryPresentExtra(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is Int -> value != 0
    else -> null
}
