package com.fem.linexa.service.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import com.fem.linexa.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The status of an install session Android reported back through
 * [UpdateInstallStatusReceiver]. Everything the coordinator needs to move
 * its state — including the confirmation activity Android wants shown
 * (STATUS_PENDING_USER_ACTION), which THIS app then starts so the platform's
 * normal installation dialog appears.
 */
sealed interface InstallStatus {
    data class PendingUserAction(val confirmationIntent: Intent?) : InstallStatus
    data object Success : InstallStatus
    data class Failure(val message: String?) : InstallStatus
}

/**
 * The one-process bus between the manifest-declared
 * [UpdateInstallStatusReceiver] (the installer session's status target) and
 * [AppUpdateCoordinator], which collects it to move the update card's state.
 */
object InstallStatusBus {
    private val _status = kotlinx.coroutines.flow.MutableSharedFlow<InstallStatus>(
        replay = 0, extraBufferCapacity = 16
    )
    val status: kotlinx.coroutines.flow.SharedFlow<InstallStatus> = _status

    fun publish(value: InstallStatus): Boolean = _status.tryEmit(value)
}

/**
 * The host install path — Android's OFFICIAL `PackageInstaller` session flow,
 * launched only on the user's explicit tap, never silently.
 *
 * Mechanics (the documented self-update dance):
 *
 *   1. `createSession(MODE_FULL_INSTALL)` — a normal install session.
 *   2. The verified APK is streamed into the session from the app's private
 *      storage — no FileProvider, no storage permission, no grant dance.
 *   3. `commit()` with a MUTABLE PendingIntent targeting
 *      [UpdateInstallStatusReceiver] — mutable because the SYSTEM writes the
 *      status extras into the broadcast intent; immutable would break the
 *      status callback.
 *   4. Android answers on that receiver: PENDING_USER_ACTION carries the
 *      confirmation activity to start (the platform's own installer UI),
 *      SUCCESS/FAILURE carry the outcome.
 *
 * Nothing here claims success on its own — only the platform's SUCCESS
 * status does. A signature mismatch (this device holds Linexa signed with a
 * different key) is refused by the platform with
 * INSTALL_FAILED_UPDATE_INCOMPATIBLE and surfaces as [InstallStatus.Failure];
 * nothing is bypassed.
 */
interface UpdateInstaller {

    /** Whether Android lets this app request package installs right now. */
    fun canRequestPackageInstalls(): Boolean

    /** Open the per-app "install unknown apps" system settings page. */
    fun openInstallPermissionSettings()

    /** Hand [apkFile] to Android's installer. The result arrives on the bus. */
    fun installApk(apkFile: File): LaunchResult

    enum class LaunchResult { STARTED, FAILED_PERMISSION, FAILED_IO, FAILED_GENERIC }
}

@Singleton
class AndroidUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) : UpdateInstaller {

    override fun canRequestPackageInstalls(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        false
    }

    override fun openInstallPermissionSettings() = try {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        // No such activity on some OEM builds — the coordinator's state still
        // tells the user what to do; nothing is silently half-opened.
        Logger.w(TAG, "install-permission settings page did not open: ${e.message}")
    }

    override fun installApk(apkFile: File): UpdateInstaller.LaunchResult {
        val packageInstaller = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            try {
                session.openWrite("linexa-update.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { input -> input.copyTo(out, STREAM_BUFFER_SIZE) }
                    session.fsync(out)
                }
                val statusIntent = Intent(UpdateInstallStatusReceiver.ACTION_UPDATE_INSTALL_STATUS).setPackage(context.packageName)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(pendingIntent.intentSender)
            } finally {
                session.close()
            }
            return UpdateInstaller.LaunchResult.STARTED
        } catch (e: SecurityException) {
            Logger.w(TAG, "install session refused: ${e.message}")
            abandonCreatedSession(packageInstaller, sessionId)
            return UpdateInstaller.LaunchResult.FAILED_PERMISSION
        } catch (e: java.io.IOException) {
            Logger.w(TAG, "install session I/O failed: ${e.message}")
            abandonCreatedSession(packageInstaller, sessionId)
            return UpdateInstaller.LaunchResult.FAILED_IO
        } catch (e: Exception) {
            Logger.w(TAG, "install session failed: ${e.message}")
            abandonCreatedSession(packageInstaller, sessionId)
            return UpdateInstaller.LaunchResult.FAILED_GENERIC
        }
    }

    /**
     * Sweep the session THIS call created and failed before commit. Only that
     * one — a committed session waiting on the user's confirmation dialog
     * (from an earlier attempt) is left alone, or the sweep would cancel an
     * install the user is in the middle of approving.
     */
    private fun abandonCreatedSession(packageInstaller: PackageInstaller, sessionId: Int) {
        if (sessionId < 0) return
        runCatching { packageInstaller.abandonSession(sessionId) }
    }

    companion object {
        private const val TAG = "AndroidUpdateInstaller"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
    }
}

/**
 * The manifest-declared target of the install session's status PendingIntent.
 * Exported=false — the broadcast runs with THIS app's identity (it fires a
 * PendingIntent the app itself created), so nothing external can reach it.
 */
class UpdateInstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java
                )?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Start Android's confirmation UI directly from here — the
                // app is foreground at this point (the user just tapped
                // Install), so the launch is permitted.
                if (confirmation != null) {
                    runCatching { context.startActivity(confirmation) }
                        .onFailure { Logger.w(TAG, "confirmation activity did not start: ${it.message}") }
                }
                InstallStatusBus.publish(InstallStatus.PendingUserAction(confirmation))
            }
            PackageInstaller.STATUS_SUCCESS -> InstallStatusBus.publish(InstallStatus.Success)
            else -> InstallStatusBus.publish(
                InstallStatus.Failure(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)
                        ?: "install status $status"
                )
            )
        }
    }

    companion object {
        private const val TAG = "UpdateInstallStatus"
        const val ACTION_UPDATE_INSTALL_STATUS = "com.fem.linexa.action.UPDATE_INSTALL_STATUS"
    }
}
