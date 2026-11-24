package bg.evolution.callrecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.core.AppLocale
import bg.evolution.callrecorder.core.Logx

/**
 * Foreground service shown while a recording runs. The audio itself is captured by
 * [RecordingController] in this process; the service gives the process foreground
 * priority and shows the ongoing notification with Stop / Pause / Discard buttons.
 *
 * It is started with a plain startService() and promotes itself with startForeground()
 * inside a try/catch: if the system refuses (background start restrictions), recording
 * continues anyway (the accessibility service keeps the process alive) and the
 * notification is posted as a regular one. Using startForegroundService() instead would
 * crash the app in that situation.
 */
class RecordingService : Service() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = AppGraph.get(this).controller
        val state = controller.state.value
        if (state !is RecorderState.Active) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = Notifications.buildRecording(this, state)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, Notifications.ID_RECORDING, notification, type)
        } catch (e: Exception) {
            Logx.w("startForeground refused, continuing as background service", e)
            Notifications.updateRecording(this, state)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                context.startService(Intent(context, RecordingService::class.java))
            } catch (e: Exception) {
                // Background start not allowed: show the notification without a service.
                Logx.w("Cannot start RecordingService", e)
                Notifications.updateRecording(context, AppGraph.get(context).controller.state.value)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
            Notifications.cancelRecording(context)
        }
    }
}
