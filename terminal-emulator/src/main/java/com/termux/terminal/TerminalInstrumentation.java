package com.termux.terminal;

/**
 * Phase M0-A — the vendored terminal module's local copy of the capture flag.
 *
 * The app module cannot be referenced from `:terminal-emulator`, so the
 * app's `TermuxPtySession` (the sanctioned terminal adapter) mirrors
 * `DebugCapture.isEnabled()` here when a session is created and on each
 * output batch. Defaults to false: a terminal session constructed without any
 * app wiring emits no trace sections.
 *
 * This only controls `android.os.Trace` sections. The per-session byte /
 * handler-message / screen-update counters are plain long increments and are
 * always maintained (three fields, no allocation) so an E7 report can describe
 * the session even when tracing was never enabled.
 */
public final class TerminalInstrumentation {

    private static volatile boolean enabled;

    private TerminalInstrumentation() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
