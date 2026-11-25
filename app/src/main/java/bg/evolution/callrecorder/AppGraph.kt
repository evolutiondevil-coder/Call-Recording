package bg.evolution.callrecorder

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import bg.evolution.callrecorder.core.AppLocale
import bg.evolution.callrecorder.data.RecordingRepository
import bg.evolution.callrecorder.data.contacts.ContactLookup
import bg.evolution.callrecorder.data.db.AppDatabase
import bg.evolution.callrecorder.data.settings.SettingsRepository
import bg.evolution.callrecorder.data.storage.RecordingStorage
import bg.evolution.callrecorder.detect.CallMonitor
import bg.evolution.callrecorder.security.AppLock
import bg.evolution.callrecorder.service.Notifications
import bg.evolution.callrecorder.service.RecordingController
import bg.evolution.callrecorder.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple manual dependency container, one per process. */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.create(app) }
    val settings = SettingsRepository(app, scope)
    val storage = RecordingStorage(app)
    val contacts = ContactLookup(app)
    val repository: RecordingRepository by lazy { RecordingRepository(database.recordings(), storage) }
    val controller: RecordingController by lazy {
        RecordingController(app, settings, storage, repository, contacts, scope)
    }
    val callMonitor: CallMonitor by lazy { CallMonitor(app, settings, controller, contacts) }
    val updates: UpdateManager by lazy { UpdateManager(app, settings, scope) }
    /** Created with the process, so a lock that is on covers the app from its first frame. */
    val lock = AppLock(app)

    companion object {
        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}

class CallRecorderApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLocale.initialize(this)
        Notifications.createChannels(this)
        val graph = AppGraph.get(this)
        graph.scope.launch(Dispatchers.IO) {
            val s = graph.settings.current
            runCatching { graph.repository.recoverOrphans(graph.controller::isFileInUse, s.storageTreeUri) }
            runCatching { graph.repository.cleanup(s.autoDeleteDays) }
        }
    }
}
