package com.fem.linexa.domain.resource

/**
 * Workspace membership from the two signals the app already has:
 *
 * 1. `ppid` descendants of the known workspace roots (the normal tree);
 * 2. the absolute rootfs path in `/proc/<pid>/cmdline`, which PRoot writes into
 *    the host argv of every traced exec.
 *
 * The second signal is not a new discovery mechanism. It is the same rule
 * `PRootContainerRuntime.killRootfsProcesses()` already uses for its teardown
 * sweep. It is what includes a daemon started outside the workspace's root
 * tree, a reparented orphan, and a process detached with `setsid`, while the
 * path match keeps another workspace's processes out.
 */
object ProcessAttribution {

    /**
     * Extract the workspace rootfs path from a PRoot command line.
     *
     * `/proc/<pid>/cmdline` is NUL-separated. Besides the explicit
     * `-r <path>` / `--rootfs <path>` forms, a traced guest argv can contain a
     * host path such as `/…/rootfs/usr/bin/bash`; the prefix ending at
     * `/rootfs` is recovered in that case.
     */
    fun rootfsMarkerFromCmdline(rawCmdline: String?): String? {
        if (rawCmdline.isNullOrBlank()) return null
        val args = cmdlineArgs(rawCmdline)
        for (index in args.indices) {
            val arg = args[index]
            when {
                (arg == "-r" || arg == "--rootfs") && index + 1 < args.size ->
                    normalizeMarker(args[index + 1])?.let { return it }
                arg.startsWith("--rootfs=") ->
                    normalizeMarker(arg.removePrefix("--rootfs="))?.let { return it }
                arg.startsWith("-r") && arg.length > 2 ->
                    normalizeMarker(arg.removePrefix("-r"))?.let { return it }
            }
        }
        // Fallback: a guest-hosted argv token such as /…/rootfs/usr/bin/bash.
        for (arg in args) {
            val index = arg.indexOf("/rootfs/")
            if (index >= 0) {
                normalizeMarker(arg.substring(0, index + "/rootfs".length))?.let { return it }
            }
        }
        return null
    }

    /**
     * Union of [roots]' descendant trees and every visible process whose argv
     * contains one of [rootfsMarkers] as a path prefix.
     *
     * [cmdlines] holds raw NUL-separated command lines keyed by pid. Missing
     * entries are normal (a process exited between the stat and cmdline reads)
     * and simply contribute no marker match.
     */
    fun workspacePids(
        roots: Set<Int>,
        parents: Map<Int, Int>,
        cmdlines: Map<Int, String>,
        rootfsMarkers: Set<String>
    ): Set<Int> {
        val result = LinkedHashSet<Int>()
        for (root in roots) {
            if (root > 0) result += ProcessTree.descendantsOf(root, parents)
        }
        if (rootfsMarkers.isNotEmpty()) {
            for ((pid, cmdline) in cmdlines) {
                if (rootfsMarkers.any { matchesMarker(cmdline, it) }) result += pid
            }
        }
        return result
    }

    /**
     * True when [rawCmdline] is a PRoot supervisor for [marker].
     *
     * Every terminal session gets its own independent proot process, so the
     * workspace root pid alone is not the complete set of supervisors. Their
     * rows must be protected from the task-manager kill action just like the
     * initial workspace root.
     */
    fun isRootfsSupervisor(rawCmdline: String?, marker: String): Boolean {
        if (rawCmdline.isNullOrBlank() || marker.length <= 1) return false
        val args = cmdlineArgs(rawCmdline)
        val launchesProot = args.any { arg ->
            val executable = arg.substringAfterLast('/')
            executable == "proot" || executable == "libproot.so" ||
                executable.startsWith("proot-")
        }
        return launchesProot && matchesMarker(rawCmdline, marker)
    }

    /** True when [rawCmdline] has an argv token equal to or below [marker]. */
    fun matchesMarker(rawCmdline: String, marker: String): Boolean {
        if (marker.length <= 1) return false
        return cmdlineArgs(rawCmdline).any { arg ->
            arg == marker || arg.startsWith("$marker/")
        }
    }

    private fun cmdlineArgs(raw: String): List<String> =
        if (raw.contains('\u0000')) {
            raw.split('\u0000').filter { it.isNotEmpty() }
        } else {
            raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }

    private fun normalizeMarker(raw: String): String? {
        val marker = raw.trim()
        if (!marker.startsWith("/") || marker.length <= 1) return null
        return marker.trimEnd('/')
    }
}
