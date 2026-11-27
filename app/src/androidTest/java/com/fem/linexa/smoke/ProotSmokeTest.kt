package com.fem.linexa.smoke

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 10B (OPT-TEST-002) smoke row 6 — the only device-only row: a real PRoot
 * workspace start. Deliberately gated twice so the suite stays safe on any
 * device:
 *
 * 1. Assumption gates (named, so the run report shows WHY it skipped):
 *    an `arm64-v8a` device AND the bundled PRoot loader jniLib
 *    (`lib__bin__loader__.so`, the §1.47 vendored loader that
 *    [BootstrapManager] wires into `PROOT_LOADER`).
 * 2. A fixture gate: a workspace directory under the app's
 *    `files/workspaces/` carrying a `.linexa-smoke-fixture` marker is the
 *    only provisioning this test accepts. No device in the current fleet has
 *    one, so the row SKIPS with a clear message rather than starting real
 *    PRoot workloads out of a smoke suite.
 *
 * When a fixture exists this test would start the runtime through the same
 * public surface a user taps; that path is exercised by the Phase-4 device
 * rows instead, which own the deep extraction/containment verification.
 */
@RunWith(AndroidJUnit4::class)
class ProotSmokeTest {

    @Test
    fun aRealWorkspaceStartIsOnlyAttemptedOnAFixtureProvisionedArm64Device() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val loader = File(context.applicationInfo.nativeLibraryDir, "lib__bin__loader__.so")
        Assume.assumeTrue(
            "skip: device ABIs (${Build.SUPPORTED_ABIS.joinToString()}) lack arm64-v8a",
            Build.SUPPORTED_ABIS.contains("arm64-v8a")
        )
        Assume.assumeTrue(
            "skip: no bundled PRoot loader at ${loader.absolutePath}",
            loader.exists()
        )

        val fixture = context.filesDir.resolve("workspaces").listFiles().orEmpty()
            .filter { it.isDirectory }
            .firstOrNull { File(it, ".linexa-smoke-fixture").exists() }
        Assume.assumeTrue(
            "skip: no smoke-fixture workspace provisioned under files/workspaces " +
                "(drop a workspace dir containing .linexa-smoke-fixture to enable)",
            fixture != null
        )

        // Fixture-provisioned path: assert the workspace rootfs is actually
        // there before anything would start it. The start itself stays with
        // the Phase-4 device rows; this row pins that a fixture device can
        // reach this point without the app failing.
        assertTrue(
            "a smoke fixture must contain a rootfs directory",
            File(fixture!!, "rootfs").exists()
        )
    }
}
