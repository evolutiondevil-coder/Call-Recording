package bg.evolution.callrecorder.detect

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.core.Logx

/** Receives cellular call state changes (also when the app isn't running). */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val monitor = AppGraph.get(context).callMonitor
        when (intent.action) {
            @Suppress("DEPRECATION")
            Intent.ACTION_NEW_OUTGOING_CALL -> monitor.onOutgoingCall(intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER))
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                monitor.onPhoneState(state, number)
            }
        }
    }
}

/**
 * Accessibility service. It reads nothing from the screen; its presence is what allows
 * the microphone to be recorded during calls on Android 10 and newer. It also tells us
 * which app is in front, which helps attribute internet calls.
 */
class RecorderAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Logx.i("Accessibility service connected")
        AppGraph.get(this).callMonitor.onAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        AppGraph.get(this).callMonitor.onForegroundApp(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AppGraph.get(this).callMonitor.onAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AppGraph.get(this).callMonitor.onAccessibilityConnected(false)
        super.onDestroy()
    }
}

/** Detects internet calls by the "ongoing call" notifications messengers show. */
class CallNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        val active = runCatching { activeNotifications?.mapNotNull(::toCall) }.getOrNull().orEmpty()
        Logx.i("Notification listener connected (${active.size} call notifications)")
        AppGraph.get(this).callMonitor.onListenerConnected(true, active)
    }

    override fun onListenerDisconnected() {
        AppGraph.get(this).callMonitor.onListenerConnected(false, emptyList())
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val monitor = AppGraph.get(this).callMonitor
        val call = toCall(sbn)
        if (call != null) monitor.onCallNotificationPosted(call) else monitor.onNotificationRemoved(sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        AppGraph.get(this).callMonitor.onNotificationRemoved(sbn.key)
    }

    private fun toCall(sbn: StatusBarNotification): CallNotification? = runCatching {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification ?: return null
        if (!looksLikeCall(sbn.packageName, n, sbn.isOngoing)) return null
        val extras = n.extras
        val callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) extras.getInt(EXTRA_CALL_TYPE, 0) else 0
        val personName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)?.name
            } else {
                @Suppress("DEPRECATION")
                (extras.getParcelable<android.app.Person>(Notification.EXTRA_CALL_PERSON))?.name
            }
        } else null
        val appLabel = VoipApps.label(this, sbn.packageName)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val name = personName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: title?.takeIf { it.isNotEmpty() && !it.equals(appLabel, ignoreCase = true) }
            ?: text?.takeIf { it.isNotEmpty() && it.length <= 40 && !it.equals(appLabel, ignoreCase = true) }
        CallNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            title = name,
            ongoing = sbn.isOngoing,
            incoming = callType == CALL_TYPE_INCOMING || n.fullScreenIntent != null,
            postedAt = sbn.postTime,
        )
    }.getOrNull()

    private fun looksLikeCall(pkg: String, n: Notification, ongoing: Boolean): Boolean {
        val extras = n.extras
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val callStyle = template.contains("CallStyle") ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && extras.containsKey(EXTRA_CALL_TYPE))
        val isCall = n.category == Notification.CATEGORY_CALL || callStyle ||
            (VoipApps.isKnown(pkg) && ongoing && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        // Missed-call notifications also use the call category, but they are never ongoing.
        return isCall && (ongoing || n.fullScreenIntent != null)
    }

    private companion object {
        const val CALL_TYPE_INCOMING = 1

        /** Notification.EXTRA_CALL_TYPE: public since API 34, but CallStyle sets it since API 31. */
        const val EXTRA_CALL_TYPE = "android.callType"
    }
}
