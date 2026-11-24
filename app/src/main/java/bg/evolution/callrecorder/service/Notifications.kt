package bg.evolution.callrecorder.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.Formatters
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.ui.MainActivity

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_SAVED = "saved"
    const val CHANNEL_PROBLEMS = "problems"
    const val CHANNEL_ASK = "ask"

    const val ID_RECORDING = 1001
    private const val ID_PROBLEM = 1002
    private const val ID_ASK = 1003
    private const val ID_SAVED_BASE = 2000

    enum class Problem { NO_MIC_PERMISSION, START_FAILED, SAVE_FAILED, SILENT }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_recording_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_SAVED, context.getString(R.string.channel_saved), NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_saved_desc)
                    setSound(null, null)
                },
                NotificationChannel(
                    CHANNEL_PROBLEMS, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_problems_desc) },
                NotificationChannel(
                    CHANNEL_ASK, context.getString(R.string.channel_ask), NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_ask_desc)
                    // Pops up over the call screen, but silently: the phone is at the user's ear.
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            ),
        )
    }

    private fun canPost(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts a notification if the user allowed notifications; never throws. */
    @SuppressLint("MissingPermission") // checked by canPost()
    private fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openApp(context: Context, recordingId: Long = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (recordingId > 0) intent.putExtra(MainActivity.EXTRA_RECORDING_ID, recordingId)
        return PendingIntent.getActivity(
            context, recordingId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(context: Context, action: String, requestCode: Int, extraId: Long = 0): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
        if (extraId != 0L) intent.putExtra(NotificationActionReceiver.EXTRA_ID, extraId)
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun buildRecording(context: Context, state: RecorderState): Notification {
        val active = state as? RecorderState.Active
        val who = active?.title
        val title = when {
            active == null -> context.getString(R.string.notif_recording_title)
            active.awaitingConfirmation -> context.getString(R.string.notif_ask_title)
            active.paused -> context.getString(R.string.notif_paused_title)
            else -> context.getString(R.string.notif_recording_title)
        }
        val text = buildString {
            if (who != null) append(who) else append(
                when (active?.info?.source) {
                    CallSource.MANUAL -> context.getString(R.string.source_manual)
                    else -> context.getString(R.string.notif_recording_call)
                },
            )
            active?.appLabel?.let { append(" · ").append(it) }
        }
        val b = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(ContextCompat.getColor(context, R.color.brand_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
        if (active != null && !active.paused) {
            b.setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - active.startedElapsed))
                .setShowWhen(true)
        } else {
            b.setShowWhen(false)
        }
        if (active != null && active.awaitingConfirmation) {
            b.addAction(0, context.getString(R.string.action_record), action(context, NotificationActionReceiver.ACTION_CONFIRM, 5))
            b.addAction(0, context.getString(R.string.action_dont_record), action(context, NotificationActionReceiver.ACTION_DECLINE, 6))
        } else if (active != null) {
            b.addAction(0, context.getString(R.string.action_stop), action(context, NotificationActionReceiver.ACTION_STOP, 1))
            if (active.paused) {
                b.addAction(0, context.getString(R.string.action_resume), action(context, NotificationActionReceiver.ACTION_RESUME, 2))
            } else {
                b.addAction(0, context.getString(R.string.action_pause), action(context, NotificationActionReceiver.ACTION_PAUSE, 3))
            }
            b.addAction(0, context.getString(R.string.action_discard), action(context, NotificationActionReceiver.ACTION_DISCARD, 4))
        }
        return b.build()
    }

    fun updateRecording(context: Context, state: RecorderState) {
        if (!canPost(context) || state !is RecorderState.Active) return
        post(context, ID_RECORDING, buildRecording(context, state))
    }

    /** Heads-up question shown when a call starts in "ask before recording" mode. */
    fun showAsk(context: Context, state: RecorderState.Active) {
        if (!canPost(context)) return
        val who = state.title ?: context.getString(R.string.unknown_caller)
        val text = listOfNotNull(who, state.appLabel).joinToString(" · ")
        val n = NotificationCompat.Builder(context, CHANNEL_ASK)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(ContextCompat.getColor(context, R.color.brand_primary))
            .setContentTitle(context.getString(R.string.notif_ask_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + context.getString(R.string.notif_ask_hint)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setSilent(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_record), action(context, NotificationActionReceiver.ACTION_CONFIRM, 7))
            .addAction(0, context.getString(R.string.action_dont_record), action(context, NotificationActionReceiver.ACTION_DECLINE, 8))
            .build()
        post(context, ID_ASK, n)
    }

    fun cancelAsk(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_ASK)
    }

    fun cancelRecording(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RECORDING)
    }

    fun showSaved(context: Context, recording: Recording, appLabel: String?) {
        if (!canPost(context)) return
        val who = recording.title ?: context.getString(R.string.unknown_caller)
        val text = buildString {
            append(who)
            appLabel?.let { append(" · ").append(it) }
            append(" · ").append(Formatters.duration(recording.durationMs))
        }
        val id = ID_SAVED_BASE + (recording.id % 500).toInt()
        val n = NotificationCompat.Builder(context, CHANNEL_SAVED)
            .setSmallIcon(R.drawable.ic_stat_saved)
            .setColor(ContextCompat.getColor(context, R.color.brand_primary))
            .setContentTitle(context.getString(R.string.notif_saved_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(6 * 60 * 60 * 1000L)
            .setContentIntent(openApp(context, recording.id))
            .addAction(0, context.getString(R.string.action_listen), openApp(context, recording.id))
            .addAction(
                0, context.getString(R.string.action_delete),
                action(context, NotificationActionReceiver.ACTION_DELETE, 100 + (recording.id % 500).toInt(), recording.id),
            )
            .build()
        post(context, id, n)
    }

    fun cancelSaved(context: Context, recordingId: Long) {
        NotificationManagerCompat.from(context).cancel(ID_SAVED_BASE + (recordingId % 500).toInt())
    }

    fun showProblem(context: Context, problem: Problem, recordingId: Long = 0) {
        if (!canPost(context)) return
        val (title, text) = when (problem) {
            Problem.NO_MIC_PERMISSION -> R.string.problem_mic_title to R.string.problem_mic_text
            Problem.START_FAILED -> R.string.problem_start_title to R.string.problem_start_text
            Problem.SAVE_FAILED -> R.string.problem_save_title to R.string.problem_save_text
            Problem.SILENT -> R.string.problem_silent_title to R.string.problem_silent_text
        }
        val n = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(ContextCompat.getColor(context, R.color.brand_accent))
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, recordingId))
            .build()
        post(context, ID_PROBLEM, n)
    }
}
