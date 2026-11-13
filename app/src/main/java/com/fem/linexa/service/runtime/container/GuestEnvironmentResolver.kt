package com.fem.linexa.service.runtime.container

import com.fem.linexa.service.rootfs.RootFsMetadata

/**
 * Issue #4 (2026-09-03) — resolves the guest environment a workspace launches
 * into: the effective `HOME`, the terminal's startup directory (the
 * **workspace**), and the identity name, from the declared RootFS metadata.
 *
 * ## Why this exists
 *
 * The terminal used to start in `/root` unconditionally: `PRootLauncher`'s
 * default `-w /root` + `HOME=/root`, with no notion of a user workspace.
 * Starting in `/root` was never technically required — the PTY child's HOST
 * cwd is the app's `filesDir` (a guest path there would kill the fork before
 * proot exists), so the guest cwd was free to be anything. It was simply the
 * proot-distro historical default. This resolver makes the startup directory
 * a declared, deterministic property of the image:
 *
 * - `linexa.environment.home` → `HOME` (default `/home/linexa` — the
 *   normal-user home; issue #1, 2026-09-03);
 * - the terminal's `-w` → the HOME itself, so the prompt reads `~`
 *   (issue #2, 2026-09-04: a declared `linexa.workspace.directory` is NO
 *   LONGER honored for the startup directory — it used to open existing
 *   workspaces in `~/linexa`, a "linexa" folder inside the linexa home,
 *   which the requirement explicitly forbids. The terminal AND the desktop
 *   always open in `/home/linexa`);
 * - `linexa.environment.user` → `USER`/`LOGNAME` (default `linexa` — the
 *   account [com.fem.linexa.service.installer.GuestRootfsInitializer.ensureGuestUser]
 *   guarantees in `/etc/passwd` before any launch).
 *
 * ## Failure policy
 *
 * Pure and total: every input produces a usable environment, because a launch
 * must never fail over a declaration that is absent or even malformed (the
 * metadata was already validated fail-hard at install; a WorkspaceRootFsMetadata
 * read at launch is fail-open and may return null). The only caller-side
 * residual risk — the directory not existing inside the rootfs at exec time —
 * is handled by the runtime falling back to [GuestEnvironment.home] when
 * directory creation failed.
 */
object GuestEnvironmentResolver {

    /** The resolved launch environment for one workspace. */
    data class GuestEnvironment(
        /** Guest `HOME` — absolute, no trailing slash. */
        val home: String,
        /**
         * The directory interactive terminals open in — ALWAYS the home
         * since issue #2 (2026-09-04), so the prompt reads `~`.
         * Absolute, no trailing slash; guaranteed safe to pass to `proot -w`
         * after the caller has run the workspace ensure step.
         */
        val startupDirectory: String,
        /** Identity name exposed via `USER`. */
        val user: String,
        /**
         * True when the image declared a `linexa.workspace.directory`.
         * PURELY informational since issue #2 (2026-09-04): the declaration
         * no longer moves the terminal's startup directory.
         */
        val workspaceDeclared: Boolean
    )

    /**
     * Resolves from [metadata] (the installed workspace's declaration, or
     * null when undeclared/unreadable).
     *
     * Issue #1 (2026-09-03): the defaults are the **normal-user**
     * environment (`linexa`, `/home/linexa`), not root — the bundled
     * Debian/Ubuntu profiles declare the same values explicitly, so this
     * only changes what an UNDECLARED image launches as. The account
     * itself is guaranteed on disk by
     * [com.fem.linexa.service.installer.GuestRootfsInitializer.ensureGuestUser]
     * before the launch spec is built; here only names/paths are resolved.
     *
     * Issue #2 (2026-09-04): the startup directory is ALWAYS the home —
     * `linexa@localhost:~$` with `pwd` answering `/home/linexa`. The
     * previously-honored `linexa.workspace.directory` declaration is what
     * kept existing workspaces opening in `/home/linexa/linexa` (their
     * persisted config.yaml still carried the old value long after the
     * bundled profile changed), so it is deliberately ignored here; the
     * stale `~/linexa` folder itself is cleaned up by
     * [com.fem.linexa.service.installer.GuestRootfsInitializer] at launch.
     */
    fun resolve(metadata: RootFsMetadata?): GuestEnvironment {
        val env = metadata?.environment
        val home = env?.home?.takeIf { it.isNotBlank() && it.startsWith("/") }
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() && it != "/" }
            ?: GuestIdentity.DEFAULT_USER_HOME
        val user = env?.user?.takeIf { it.isNotBlank() } ?: GuestIdentity.DEFAULT_USER_NAME
        // The flag keeps its historical meaning — a WELL-FORMED declaration
        // exists (absolute, non-root) — even though the declaration no longer
        // moves the startup directory.
        val usableDeclaration = metadata?.workspace?.directory
            ?.takeIf { it.isNotBlank() && it.startsWith("/") }
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() && it != "/" }
        return GuestEnvironment(
            home = home,
            // Issue #2: the home itself — never a subdirectory of it.
            startupDirectory = home,
            user = user,
            workspaceDeclared = usableDeclaration != null
        )
    }
}
