package bg.evolution.callrecorder.ui.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import bg.evolution.callrecorder.detect.CallNotificationListener
import bg.evolution.callrecorder.detect.RecorderAccessibilityService

/** Snapshot of everything the app needs, shown in the setup screen and the home status card. */
data class SetupStatus(
    val microphone: Boolean,
    val phone: Boolean,
    val callLog: Boolean,
    val contacts: Boolean,
    val notifications: Boolean,
    val accessibility: Boolean,
    val notificationAccess: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val runtimeGranted: Boolean get() = microphone && phone && callLog && contacts && notifications

    /** Accessibility is only strictly needed on Android 10+. */
    val accessibilityRequired: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Things without which recording does not work at all. */
    val essentialMissing: Int
        get() = listOf(microphone, phone, !accessibilityRequired || accessibility).count { !it }

    val allDone: Boolean get() = runtimeGranted && (accessibility || !accessibilityRequired) && notificationAccess && batteryUnrestricted
    val missingCount: Int
        get() = listOf(runtimeGranted, accessibility || !accessibilityRequired, notificationAccess, batteryUnrestricted).count { !it }
}

object Permissions {
    val runtime: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            add(Manifest.permission.PROCESS_OUTGOING_CALLS)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun status(context: Context): SetupStatus = SetupStatus(
        microphone = granted(context, Manifest.permission.RECORD_AUDIO),
        phone = granted(context, Manifest.permission.READ_PHONE_STATE),
        callLog = granted(context, Manifest.permission.READ_CALL_LOG),
        contacts = granted(context, Manifest.permission.READ_CONTACTS),
        notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
        accessibility = isAccessibilityEnabled(context),
        notificationAccess = isNotificationAccessEnabled(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(context),
    )

    private fun accessibilityComponent(context: Context) =
        ComponentName(context, RecorderAccessibilityService::class.java)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = accessibilityComponent(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun isNotificationAccessEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun accessibilitySettingsIntents(context: Context): List<Intent> = listOf(
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, accessibilityComponent(context).flattenToString()),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    fun notificationAccessIntents(context: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, CallNotificationListener::class.java).flattenToString(),
                ),
            )
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    @SuppressLint("BatteryLife")
    fun batteryIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Starts the first intent that some activity can handle. */
    fun launchFirst(context: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // try the next one
            }
        }
        return false
    }
}
