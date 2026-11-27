package com.fem.linexa.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fem.linexa.R
import com.fem.linexa.domain.runtime.PhantomLimitGuidance
import com.fem.linexa.domain.runtime.PhantomLimitGuidanceText
import com.fem.linexa.domain.runtime.PhantomLimitPolicy
import com.fem.linexa.domain.runtime.PhantomLimitStatus
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.isIgnoringBatteryOptimizations
import com.fem.linexa.ui.components.requestIgnoreBatteryOptimizations
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI/UX Phase 4 (1) — the device-level access settings Linexa needs, in **Global**
 * Settings: the three app-wide permission rows plus the process-limit status row.
 *
 * The three permission rows were introduced in §1.104 on the *Workspace* Settings tab,
 * which was wrong in a way that only becomes visible once you ask what a row does: none
 * of them is per-workspace. "All files access", the battery-optimization whitelist and
 * the notification permission are granted to the **application** by the platform —
 * turning one on inside workspace A turns it on for workspace B, and for a user with
 * no workspaces at all there was no screen on which they existed. Worse, the runtime's
 * foreground-service notification (the only way to stop a workspace, §1.100) and the
 * wake-lock path both depend on grants that could only be reached by first entering a
 * workspace, i.e. by first starting the thing the permission exists to keep alive.
 *
 * So this is a *move*, not a copy: [com.fem.linexa.ui.screens.workspace.SettingsTab]
 * no longer renders an Access group, and the API-gating predicates moved here with the
 * rows rather than being duplicated (two copies of a `Build.VERSION.SDK_INT >= 33`
 * threshold is exactly how one of them ends up wrong).
 *
 * The §1.105 crash guard moved with the code and is load-bearing: the resume observer
 * binds to **compose-ui's** `LocalLifecycleOwner`
 * (`androidx.compose.ui.platform`), not to `androidx.lifecycle.compose`'s
 * identically-named-but-different CompositionLocal, which compose-ui 1.6.x
 * (BOM 2024.06.00) never provides — reading it threw "CompositionLocal
 * LocalLifecycleOwner not present" and took the whole Settings screen down. Do not
 * "simplify" this back to `LifecycleResumeEffect`.
 */
@Composable
fun AccessSettingsGroup() {
    val context = LocalContext.current

    // The states are cheap system queries rather than observable values — the platform
    // offers no change notification for any of these settings — so they are recomputed
    // whenever the activity resumes. The three permission rows launch a system screen
    // that pauses us; the process-limit row does not, but returning here still refreshes
    // it after an adb change without a manual refresh.
    var accessRefreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fileAccessGranted = remember(accessRefreshKey) { isAllFilesAccessGranted() }
    val batteryOptimizationDisabled =
        remember(accessRefreshKey) { isIgnoringBatteryOptimizations(context) }
    val notificationsEnabled = remember(accessRefreshKey) { notificationsGranted(context) }
    var phantomLimitStatus by remember {
        mutableStateOf(PhantomLimitPolicy.assess(Build.VERSION.SDK_INT, null))
    }
    LaunchedEffect(accessRefreshKey) {
        phantomLimitStatus = withContext(Dispatchers.IO) {
            PhantomLimitPolicy.assess(
                sdkInt = Build.VERSION.SDK_INT,
                rawSetting = readPhantomLimitRaw(context)
            )
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Nothing to do: the row's state refreshes via accessRefreshKey on resume.
    }

    com.fem.linexa.ui.components.haven.HCard(tight = true) {
        AccessPermissionItem(
            iconRes = com.fem.linexa.R.drawable.ic_haven_folder,
            title = stringResource(R.string.settings_file_access),
            subtitle = stringResource(R.string.settings_file_access_desc),
            granted = fileAccessGranted,
            onClick = { openFileAccessSettings(context) }
        )
        com.fem.linexa.ui.components.haven.HHairline()
        AccessPermissionItem(
            iconRes = com.fem.linexa.R.drawable.ic_haven_power,
            title = stringResource(R.string.settings_battery_optimization),
            subtitle = stringResource(R.string.settings_battery_optimization_desc),
            granted = batteryOptimizationDisabled,
            onClick = { requestIgnoreBatteryOptimizations(context) }
        )
        com.fem.linexa.ui.components.haven.HHairline()
        AccessPermissionItem(
            iconRes = com.fem.linexa.R.drawable.ic_haven_bell,
            title = stringResource(R.string.settings_notifications),
            subtitle = stringResource(R.string.settings_notifications_desc),
            granted = notificationsEnabled,
            onClick = {
                // API 33+ needs an explicit POST_NOTIFICATIONS grant for the runtime's
                // foreground notification; otherwise (or once granted) jump to the
                // app's notification settings, which is where it can be turned back on.
                if (notificationsPermissionRequired(Build.VERSION.SDK_INT) &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppNotificationSettings(context)
                }
            }
        )
        com.fem.linexa.ui.components.haven.HHairline()
        PhantomLimitAccessItem(status = phantomLimitStatus)
    }
}

/**
 * One access row: a Haven row trailing the real current state as a status pill.
 *
 * The status is On/Off rather than a switch because the app cannot grant these — the
 * tap always hands over to a system screen, and a switch that bounced back would read
 * as a failure.
 */
@Composable
private fun AccessPermissionItem(
    iconRes: Int,
    title: String,
    subtitle: String,
    granted: Boolean,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    val h = com.fem.linexa.ui.theme.haven.HavenTheme.colors
    com.fem.linexa.ui.components.haven.HRow(
        title = title,
        description = subtitle,
        onClick = onClick,
        leading = { com.fem.linexa.ui.components.haven.HRic(iconRes) },
        trailing = {
            com.fem.linexa.ui.components.haven.HPill(
                text = stringResource(
                    if (granted) R.string.setting_status_on else R.string.setting_status_off
                ),
                color = if (granted) h.primary else h.muted,
                container = if (granted) h.tint else h.surface2
            )
        }
    )
}

@Composable
private fun PhantomLimitAccessItem(status: PhantomLimitStatus) {
    val h = HavenTheme.colors
    val sdkInt = Build.VERSION.SDK_INT
    val statusText = when (phantomLimitStatusText(status)) {
        PhantomLimitStatusText.MONITOR_ON ->
            stringResource(R.string.settings_phantom_limit_status_on)
        PhantomLimitStatusText.MONITOR_OFF ->
            stringResource(R.string.settings_phantom_limit_status_off)
        PhantomLimitStatusText.UNKNOWN ->
            stringResource(R.string.settings_phantom_limit_status_unknown)
        PhantomLimitStatusText.NOT_APPLICABLE ->
            stringResource(R.string.settings_phantom_limit_status_not_applicable)
    }
    val statusColor = when (status) {
        PhantomLimitStatus.MONITORING_ON -> h.amber
        PhantomLimitStatus.MONITORING_OFF -> h.primary
        PhantomLimitStatus.UNKNOWN, PhantomLimitStatus.NOT_APPLICABLE -> h.muted
    }
    val statusContainer = when (status) {
        PhantomLimitStatus.MONITORING_ON -> h.amberTint
        PhantomLimitStatus.MONITORING_OFF -> h.tint
        PhantomLimitStatus.UNKNOWN, PhantomLimitStatus.NOT_APPLICABLE -> h.surface2
    }
    val description = when (phantomLimitDescriptionText(sdkInt, status)) {
        PhantomLimitDescriptionText.ON ->
            stringResource(R.string.settings_phantom_limit_desc_on)
        PhantomLimitDescriptionText.OFF ->
            stringResource(R.string.settings_phantom_limit_desc_off)
        PhantomLimitDescriptionText.UNKNOWN_ANDROID_12 ->
            stringResource(R.string.settings_phantom_limit_desc_unknown_android_12)
        PhantomLimitDescriptionText.UNKNOWN_ANDROID_12L_13 ->
            stringResource(R.string.settings_phantom_limit_desc_unknown_android_12l_13)
        PhantomLimitDescriptionText.UNKNOWN_MODERN ->
            stringResource(R.string.settings_phantom_limit_desc_unknown_modern)
        PhantomLimitDescriptionText.NOT_APPLICABLE ->
            stringResource(R.string.settings_phantom_limit_desc_not_applicable)
    }

    com.fem.linexa.ui.components.haven.HRow(
        title = stringResource(R.string.settings_phantom_limit_title),
        description = description,
        leading = { com.fem.linexa.ui.components.haven.HRic(R.drawable.ic_haven_cpu) },
        trailing = {
            com.fem.linexa.ui.components.haven.HPill(
                text = statusText,
                color = statusColor,
                container = statusContainer
            )
        }
    )

    if (phantomLimitShowsRuntimeCaveat(status)) {
        Text(
            text = stringResource(R.string.settings_phantom_limit_caveat),
            style = HavenType.desc,
            color = h.muted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp)
        )
    }

    val guidance = PhantomLimitPolicy.guidanceFor(sdkInt, status)
    if (guidance != null) {
        PhantomLimitGuidanceBlock(guidance)
    }
}

@Composable
private fun PhantomLimitGuidanceBlock(guidance: PhantomLimitGuidance) {
    val h = HavenTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val command = guidance.command
    val guidanceText = when (guidance.text) {
        PhantomLimitGuidanceText.ANDROID_12 ->
            stringResource(R.string.settings_phantom_limit_guidance_android_12)
        PhantomLimitGuidanceText.MONITOR_ON ->
            stringResource(R.string.settings_phantom_limit_guidance_on)
        PhantomLimitGuidanceText.UNKNOWN ->
            stringResource(R.string.settings_phantom_limit_guidance_unknown)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
    ) {
        Text(
            text = guidanceText,
            style = HavenType.desc,
            color = h.text2
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = command,
            style = HavenType.descMono,
            color = h.text
        )
        Spacer(modifier = Modifier.height(10.dp))
        HButton(
            label = stringResource(R.string.settings_phantom_limit_copy_command),
            onClick = {
                clipboard.setText(AnnotatedString(command))
                Toast.makeText(context, R.string.common_copied, Toast.LENGTH_SHORT).show()
            },
            variant = HavenButtonVariant.SECONDARY,
            size = HavenButtonSize.SMALL
        )
    }
}

// ── Access state queries + settings launchers ───────────────────────────────

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) exists only on API 30+.
 * Pure, so the API-gating logic is unit-testable on the JVM.
 */
// Lint cannot infer the SDK gate from a helper that takes `sdkInt` as a
// parameter, so the all-files access call below was reported as a NewApi
// error even though it only runs behind this check. The annotation is the
// supported way to describe that contract: it names the parameter that
// carries the API level, so static analysis sees the same guard the caller does.
@ChecksSdkIntAtLeast(parameter = 0, api = Build.VERSION_CODES.R)
internal fun fileAccessSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

/**
 * POST_NOTIFICATIONS is a runtime permission only on API 33+; on older releases the
 * platform auto-grants it. Pure, so the API-gating logic is unit-testable on the JVM.
 */
internal fun notificationsPermissionRequired(sdkInt: Int): Boolean = sdkInt >= 33

/**
 * Reports granted on pre-R, where the concept does not exist: the legacy storage
 * permissions this app declares are granted at install time there, so "Off" would be a
 * false negative pointing at a screen that cannot be opened.
 */
private fun isAllFilesAccessGranted(): Boolean =
    if (fileAccessSupported(Build.VERSION.SDK_INT)) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

private fun notificationsGranted(context: Context): Boolean =
    !notificationsPermissionRequired(Build.VERSION.SDK_INT) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun readPhantomLimitRaw(context: Context): String? {
    if (Build.VERSION.SDK_INT < PhantomLimitPolicy.READABLE_API) return null
    return runCatching {
        Settings.Global.getString(context.contentResolver, PhantomLimitPolicy.SETTING_KEY)
    }.getOrNull()
}

// §1.191: isIgnoringBatteryOptimizations / requestIgnoreBatteryOptimizations moved
// to ui/components/BatteryOptimizationAccess.kt — the per-terminal SIGKILL notice
// offers the same exemption as a remedy, and two copies of a platform-intent
// fallback chain is how one of them ends up wrong.

/** Open the "All files access" screen for this app (API 30+), else app details. */
private fun openFileAccessSettings(context: Context) {
    if (fileAccessSupported(Build.VERSION.SDK_INT)) {
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    } else {
        openAppDetails(context)
    }
}

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        )
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }
}
