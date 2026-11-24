package bg.evolution.callrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import bg.evolution.callrecorder.AppGraph
import kotlinx.coroutines.launch

/** Handles the buttons of the recording / "saved" notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.get(context)
        val controller = graph.controller
        when (intent.action) {
            ACTION_STOP -> controller.stop()
            ACTION_DISCARD -> controller.stop(discard = true)
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_CONFIRM -> controller.confirm()
            ACTION_DECLINE -> controller.decline()
            ACTION_DELETE -> {
                val id = intent.getLongExtra(EXTRA_ID, 0)
                if (id > 0) {
                    Notifications.cancelSaved(context, id)
                    val pending = goAsync()
                    graph.scope.launch {
                        try {
                            graph.repository.delete(listOf(id))
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "bg.evolution.callrecorder.STOP"
        const val ACTION_DISCARD = "bg.evolution.callrecorder.DISCARD"
        const val ACTION_PAUSE = "bg.evolution.callrecorder.PAUSE"
        const val ACTION_RESUME = "bg.evolution.callrecorder.RESUME"
        const val ACTION_DELETE = "bg.evolution.callrecorder.DELETE"
        const val ACTION_CONFIRM = "bg.evolution.callrecorder.CONFIRM"
        const val ACTION_DECLINE = "bg.evolution.callrecorder.DECLINE"
        const val EXTRA_ID = "id"
    }
}
