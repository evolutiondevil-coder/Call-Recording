package bg.evolution.callrecorder.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.BuildConfig
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.service.Notifications

/** Receives the result of the install session started by [UpdateManager.install]. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = AppGraph.get(context).updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android shows its "Do you want to update this app?" screen.
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    updates.onInstallFailed()
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Logx.e("Cannot show install confirmation", e)
                    updates.onInstallFailed()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Logx.i("Update installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.onInstallFailed(cancelled = true)
            else -> {
                Logx.w("Update install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                updates.onInstallFailed()
            }
        }
    }
}

/** Runs in the new version right after an update: tidy up and tell the user. */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = AppGraph.get(context)
        graph.updates.cleanup(keep = null)
        Notifications.showUpdated(context, BuildConfig.VERSION_NAME)
    }
}
