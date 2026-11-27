package com.fem.linexa.smoke

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fem.linexa.service.importservice.ImportService
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finding 034 — the first real device-runtime rows beyond the six smoke tests.
 *
 * The PTY row is fixture-gated exactly like [ProotSmokeTest]: it only runs when
 * a workspace under `files/workspaces/` carries `.linexa-smoke-fixture`. That
 * keeps a normal install safe (no test terminals, no guest writes) while a
 * disposable fixture device gets a real PRoot shell, a real `echo` round-trip,
 * and a real close.
 *
 * The FGS row needs no fixture: it reads the installed manifest through the
 * package manager and pins the `specialUse` declaration that finding 012 added.
 *
 * Local-only note: this source is compile-verified in the JVM/Android-test
 * compile tasks. Executing it requires a device (`connectedDebugAndroidTest`),
 * which is deliberately out of scope for the local-only pass.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aFixtureWorkspaceOpensARealPtyEchoesAndCloses() = runBlocking {
        val fixture = fixtureWorkspace()
        assumeTrue(
            "skip: no workspace with .linexa-smoke-fixture under files/workspaces",
            fixture != null
        )
        val workspaceId = fixture!!.name

        val binder = bindRuntime()
        var terminalId: String? = null
        try {
            withTimeout(90_000) {
                terminalId = binder.openTerminal(workspaceId)
                val id = terminalId!!
                awaitState(binder, workspaceId, id) { it is com.fem.linexa.domain.runtime.TerminalState.Running }

                val pty = binder.getPtySession(workspaceId, id)
                assertNotNull("a Running terminal must expose its pty", pty)
                // Wait for the guest's own first bytes before typing, so this
                // row does not depend on a fixed startup delay.
                awaitOutput(pty!!, 30_000) { it.isNotBlank() }

                val token = "LINEXA_DEVICE_${System.currentTimeMillis()}"
                pty.write("echo $token\n")
                val transcript = awaitOutput(pty, 30_000) { it.contains(token) }
                assertTrue("the guest must echo $token, got: ${transcript.takeLast(400)}", transcript.contains(token))
                assertTrue("the pty must still be alive after the echo", pty.isAlive)
            }
        } finally {
            terminalId?.let { id ->
                runCatching { withTimeout(30_000) { binder.closeTerminal(workspaceId, id) } }
            }
            context.unbindService(connection)
        }
    }

    @Test
    fun importServiceDeclaresTheSpecialUseForegroundType() {
        assumeTrue(
            "PackageManager.getProperty requires API 31",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        )
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, ImportService::class.java),
            0
        )
        assertTrue(
            "ImportService must declare FOREGROUND_SERVICE_TYPE_SPECIAL_USE",
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0
        )
        val subtype = context.packageManager.getProperty(
            "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            ComponentName(context, ImportService::class.java)
        )?.string
        assertTrue(
            "the specialUse subtype must explain the import use case",
            !subtype.isNullOrBlank()
        )
    }

    private var connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? RuntimeBinder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    private var binder: RuntimeBinder? = null
    private val connected = CountDownLatch(1)

    private fun bindRuntime(): RuntimeBinder {
        val intent = Intent(context, RuntimeService::class.java)
        assertTrue(
            "RuntimeService must bind",
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        )
        assertTrue(
            "RuntimeService did not return its binder within 15 s",
            connected.await(15, TimeUnit.SECONDS)
        )
        return requireNotNull(binder) { "RuntimeService returned no RuntimeBinder" }
    }

    private fun fixtureWorkspace(): File? =
        File(context.filesDir, "workspaces").listFiles().orEmpty()
            .filter { it.isDirectory }
            .firstOrNull { File(it, ".linexa-smoke-fixture").exists() }

    private suspend fun awaitState(
        binder: RuntimeBinder,
        workspaceId: String,
        terminalId: String,
        predicate: (com.fem.linexa.domain.runtime.TerminalState?) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val state = binder.terminalStates.value[workspaceId]?.get(terminalId)
            if (predicate(state)) return
            delay(250)
        }
        error("terminal $workspaceId/$terminalId never reached the expected state")
    }

    private suspend fun awaitOutput(
        pty: com.fem.linexa.service.runtime.pty.PtySession,
        timeoutMs: Long,
        predicate: (String) -> Boolean
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var output = pty.recentOutput(64_000)
        while (System.currentTimeMillis() < deadline) {
            if (predicate(output)) return output
            delay(250)
            output = pty.recentOutput(64_000)
        }
        return output
    }
}
