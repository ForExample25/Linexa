package com.fem.linexa.service.runtime.graphics

import java.io.File

/**
 * Pure (JVM-testable) provisioning logic for the Workspace **GPU** feature:
 * the host-side VirGL test server (`virgl_test_server_android`, vtest
 * protocol over a Unix socket) and the guest-side Mesa environment that
 * connects to it, 2026-09 GPU work.
 *
 * Same convention as [com.fem.linexa.service.runtime.features.AudioBridgeProvisioner]
 * and `XvncProvisioner`: deterministic string/path work only (no Android, no
 * coroutines, no process spawning), so the availability probe, the host
 * command line and both halves of the socket path are asserted in plain JUnit
 * tests. The suspend orchestration and process lifecycle live in
 * [WorkspaceGpuSupervisor].
 *
 * Architecture (device-verified 2026-09-01 on MT6899 / Mali-G720, Android 16):
 *
 * - **Host side**: a native `virgl_test_server_android` process, exec'd from
 *   `nativeLibraryDir` (the only SELinux-executable location for an app —
 *   `apk_data_file` label, the same rule `PRootLauncher.binaryPath` records).
 *   It creates an EGL/GLES context through the device's own GPU driver stack
 *   (ANGLE → Vulkan → Mali) and serves the virgl vtest protocol on a Unix
 *   socket placed INSIDE the workspace rootfs, so the guest reaches it through
 *   the existing R-5 `/run` bind — **no new bind mount, no patched proot, no
 *   DRM node, no root**.
 * - **Guest side**: stock Mesa with the `virpipe` (virgl-over-vtest) driver.
 *   The launch env carries `GALLIUM_DRIVER=virpipe` and
 *   `VTEST_SOCKET_NAME=<guest socket path>`; a guest EGL context then renders
 *   on the device GPU. Verified renderer string on the validation device:
 *   `virgl (ANGLE (ARM, Vulkan 1.3.278 (Mali-G720 MC7 …)))`.
 * - **Why not Turnip/Zink or direct DRM**: the validation device is
 *   MediaTek/ARM Mali (not Adreno — Turnip does not support it), the DRM
 *   render node is `system:system 0660` under SELinux Enforcing (unusable by
 *   an app), and `/dev/mali0` has no open-source driver. VirGL is the only
 *   path that renders through the *Android* GPU driver stack, which every
 *   device already has.
 * - **Why the binaries are not bundled in this build**: the Termux
 *   `virglrenderer-android 1.3.0` build resolves its ANGLE backend through a
 *   COMPILED-IN path (`/data/data/com.termux/files/usr/opt/angle-android/gl/`,
 *   verified by an on-device dlopen-failure log) and never falls back to plain
 *   soname resolution — so inside the Linexa app sandbox the backend cannot
 *   load. Bundling unverifiable-at-runtime binaries against Linexa's
 *   provenance standards is worse than not shipping them; the supervisor
   *  probes for the triplet and reports a precise Degraded state when absent.
 *   See `docs/investigations/GPU_ACCELERATION_IMPLEMENTATION_REPORT.md`.
 *
 * Naming: the binaries follow the existing `lib__*__.so` jniLibs convention
 * (`AGP` packages only `lib*.so`; see `BINARY-MANIFEST.md`) so a future build
 * with a fixed ANGLE resolution drops in without code changes.
 */
object VirglHostServer {

    /** jniLibs file name of the host vtest server binary. */
    const val BINARY_FILE: String = "lib__bin__virgl_test_server__.so"

    /** jniLibs file name of the virglrenderer library it links against. */
    const val LIB_VIRGL_FILE: String = "lib__lib__libvirglrenderer.so__.so"

    /** jniLibs file name of libepoxy, the other link-time dependency. */
    const val LIB_EPOXY_FILE: String = "lib__lib__libepoxy.so__.so"

    /**
     * The socket path as the GUEST resolves it. The host socket lives at
     * `{rootfs}/run/linexa-gpu/virgl.sock`; the existing R-5 bind
     * (`{rootfs}/run:/run`) makes the two views the same directory, which is
     * what keeps this feature at zero new bind mounts.
     */
    const val GUEST_SOCKET_PATH: String = "/run/linexa-gpu/virgl.sock"

    /** Socket file name inside [hostSocketDir]; must match [GUEST_SOCKET_PATH]. */
    const val SOCKET_FILE_NAME: String = "virgl.sock"

    /** Host socket dir relative to the rootfs root; under the R-5 `/run` bind. */
    const val HOST_DIR_UNDER_ROOTFS: String = "run/linexa-gpu"

    /** How long [WorkspaceGpuSupervisor] waits for the server to bind its socket. */
    const val SOCKET_WAIT_MS: Long = 5_000L

    /** Guest-side Mesa driver selection for virgl-over-vtest (device-verified). */
    const val GALLIUM_DRIVER_VALUE: String = "virpipe"

    /** Backend flag passed to the server: ANGLE over the device's GLES stack. */
    const val BACKEND_FLAG: String = "--angle-gl"

    /** The result of probing `nativeLibraryDir` for the GPU binary triplet. */
    data class Availability(
        val binaryPath: String?,
        val missing: List<String>
    ) {
        val isAvailable: Boolean get() = binaryPath != null && missing.isEmpty()
    }

    /**
     * Probe the app's `nativeLibraryDir` for the server binary and its two
     * link-time libraries. Pure stat work; deterministic on the shipped APK.
     */
    fun probe(nativeLibraryDir: String): Availability {
        val dir = File(nativeLibraryDir)
        val binary = File(dir, BINARY_FILE)
        val missing = buildList {
            if (!binary.isFile) add(BINARY_FILE)
            if (!File(dir, LIB_VIRGL_FILE).isFile) add(LIB_VIRGL_FILE)
            if (!File(dir, LIB_EPOXY_FILE).isFile) add(LIB_EPOXY_FILE)
        }
        return Availability(
            binaryPath = if (binary.isFile) binary.absolutePath else null,
            missing = missing
        )
    }

    /**
     * The HOST directory the server creates its socket in. Under the rootfs's
     * own `/run` (created 1777 by `GuestRootfsInitializer.ensureGuestRun`), so
     * the R-5 bind exposes it to the guest unchanged.
     */
    fun hostSocketDir(rootfsPath: String): File = File(File(rootfsPath), HOST_DIR_UNDER_ROOTFS)

    /**
     * The host argv for one server instance: ANGLE-GLES backend, socket at an
     * exact path (`--socket-path` — the server's own default is a
     * user-independent constant we deliberately do not rely on).
     */
    fun buildCommand(binaryPath: String, socketDir: File): List<String> = listOf(
        binaryPath,
        BACKEND_FLAG,
        "--socket-path",
        File(socketDir, SOCKET_FILE_NAME).absolutePath
    )

    /**
     * Host-side env for the server process. Full-replacement env discipline
     * (same shape as every other launch path): the bionic linker resolves
     * `libvirglrenderer.so`/`libepoxy.so` from `nativeLibraryDir`.
     */
    fun buildHostEnv(nativeLibraryDir: String): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to nativeLibraryDir
    )

    /**
     * The guest env that points stock Mesa at the host server. Returned only
     * when the server is confirmed running, so a failed GPU bring-up leaves
     * the guest on software rendering instead of a broken virpipe lookup.
     */
    fun guestLaunchEnv(): Map<String, String> = mapOf(
        "GALLIUM_DRIVER" to GALLIUM_DRIVER_VALUE,
        "VTEST_SOCKET_NAME" to GUEST_SOCKET_PATH
    )
}
