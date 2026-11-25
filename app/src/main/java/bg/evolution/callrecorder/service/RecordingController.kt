package bg.evolution.callrecorder.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import bg.evolution.callrecorder.audio.AudioCapture
import bg.evolution.callrecorder.audio.RecorderEngine
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallInfo
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.core.RecordFilterMode
import bg.evolution.callrecorder.data.RecordingRepository
import bg.evolution.callrecorder.data.contacts.ContactLookup
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.data.settings.AppSettings
import bg.evolution.callrecorder.data.settings.SettingsRepository
import bg.evolution.callrecorder.data.storage.RecordingStorage
import bg.evolution.callrecorder.detect.VoipApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the recorder is doing right now (observed by the UI, the notification and the tile). */
sealed interface RecorderState {
    data object Idle : RecorderState

    data class Active(
        val sessionId: Long,
        val info: CallInfo,
        /** [SystemClock.elapsedRealtime] when recording started, for live timers. */
        val startedElapsed: Long,
        val paused: Boolean,
        val appLabel: String?,
        /**
         * "Ask before recording" mode: audio is already captured (so the start of the call
         * isn't lost) but it is only kept if the user taps "Record".
         */
        val awaitingConfirmation: Boolean = false,
    ) : RecorderState {
        val title: String? get() = info.displayName ?: info.number
    }
}

/** The part of [RecordingController] that call detection needs (an interface so it can be faked in tests). */
interface CallRecorderControl {
    val isBusy: Boolean
    fun start(callInfo: CallInfo, inPhoneCall: Boolean, askFirst: Boolean = false): Long
    fun stop(session: Long = 0, discard: Boolean = false)
    fun updateInfo(session: Long, transform: (CallInfo) -> CallInfo)
}

/**
 * Decides whether a call should be recorded/kept, given what we know about it.
 * With [final] = false (call start) unknown details give the call the benefit of the doubt;
 * with [final] = true (after the call, when the call log was read) they don't.
 */
object CallFilter {
    fun shouldKeep(s: AppSettings, number: String?, isContact: Boolean, name: String? = null, final: Boolean = false): Boolean {
        if (s.isNumberExcluded(number)) return false
        return when (s.filterMode) {
            RecordFilterMode.ALL -> true
            RecordFilterMode.CONTACTS_ONLY -> number == null || isContact
            RecordFilterMode.UNKNOWN_ONLY -> !isContact
            RecordFilterMode.SELECTED_ONLY ->
                if (number == null && name == null) !final else s.isSelected(number, name)
        }
    }

    /** Internet calls only have the contact's name (from the call notification). */
    fun shouldKeepVoip(s: AppSettings, name: String?, final: Boolean): Boolean = when (s.filterMode) {
        RecordFilterMode.SELECTED_ONLY -> if (name == null) !final else s.isSelected(null, name)
        else -> true
    }
}

/**
 * Central place that starts, stops and finalizes recordings. All heavy work runs on a
 * single serial background dispatcher, so requests are handled strictly in order and
 * the main thread is never blocked.
 */
class RecordingController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val storage: RecordingStorage,
    private val repository: RecordingRepository,
    private val contacts: ContactLookup,
    private val scope: CoroutineScope,
) : CallRecorderControl {
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val ids = AtomicLong(0)

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Session that was requested but whose recording hasn't been stopped yet (set synchronously). */
    @Volatile private var pendingSession: Long = 0
    @Volatile private var engine: RecorderEngine? = null
    private var engineSession: Long = 0
    private var info: CallInfo? = null
    private var awaitingConfirmation = false
    private var wakeLock: PowerManager.WakeLock? = null

    override val isBusy: Boolean get() = pendingSession != 0L

    /** Recorded audio length of the running session (pauses excluded). */
    fun recordedMs(): Long = engine?.recordedMs ?: 0L

    /** Temp files being written or finalized right now, so crash recovery never touches them. */
    private val filesInUse = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isFileInUse(file: File): Boolean = file.absolutePath in filesInUse

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts recording. Returns a session id (0 if another recording is already running).
     * [inPhoneCall] tells the audio source picker whether a cellular call is active.
     * With [askFirst] the user is asked whether to keep the recording (see [confirm]/[decline]).
     */
    override fun start(callInfo: CallInfo, inPhoneCall: Boolean, askFirst: Boolean): Long {
        val session: Long
        synchronized(this) {
            if (pendingSession != 0L) return 0
            session = ids.incrementAndGet()
            pendingSession = session
        }
        scope.launch(serial) { doStart(session, callInfo, inPhoneCall, askFirst) }
        return session
    }

    private fun doStart(session: Long, callInfo: CallInfo, inPhoneCall: Boolean, askFirst: Boolean) {
        if (pendingSession != session) return // stopped before it even started
        if (!hasMicPermission()) {
            Logx.w("RECORD_AUDIO not granted")
            Notifications.showProblem(context, Notifications.Problem.NO_MIC_PERMISSION)
            finishSession(session)
            return
        }
        val s = settings.current
        val sources = AudioCapture.sourceChain(s.audioSource, callInfo.source, inPhoneCall)
        val e = RecorderEngine(
            format = s.format,
            quality = s.quality,
            sources = sources,
            gainDb = s.gainDb,
            fileFor = { f -> storage.newTempFile(f, callInfo.startedAt).also { filesInUse.add(it.absolutePath) } },
            listener = object : RecorderEngine.Listener {
                override fun onLevel(level: Float) { _level.value = level }
                override fun onFatalError(error: Throwable) {
                    Logx.e("Recording error, saving what we have", error)
                    stop(session)
                }
            },
        )
        val ok = runCatching { e.start() }.onFailure { Logx.e("Engine start crashed", it) }.getOrDefault(false)
        if (!ok) {
            Notifications.showProblem(context, Notifications.Problem.START_FAILED)
            finishSession(session)
            return
        }
        engine = e
        engineSession = session
        info = callInfo
        awaitingConfirmation = askFirst
        acquireWakeLock()
        val active = RecorderState.Active(
            sessionId = session,
            info = callInfo,
            startedElapsed = SystemClock.elapsedRealtime(),
            paused = false,
            appLabel = VoipApps.label(context, callInfo.appPackage),
            awaitingConfirmation = askFirst,
        )
        _state.value = active
        Logx.i("Recording started: ${callInfo.source} ${callInfo.appPackage ?: ""} ask=$askFirst")
        RecordingService.start(context)
        if (askFirst) Notifications.showAsk(context, active)
    }

    /** "Ask" mode: the user wants this call recorded. */
    fun confirm(session: Long = 0) {
        scope.launch(serial) {
            if (engine == null || (session != 0L && session != engineSession)) return@launch
            Notifications.cancelAsk(context)
            if (!awaitingConfirmation) return@launch
            awaitingConfirmation = false
            (_state.value as? RecorderState.Active)?.let {
                _state.value = it.copy(awaitingConfirmation = false)
                Notifications.updateRecording(context, _state.value)
            }
        }
    }

    /** "Ask" mode: the user doesn't want this call recorded; everything captured so far is dropped. */
    fun decline(session: Long = 0) = stop(session, discard = true)

    /** Learns details while the call is running (e.g. the number arrives in a later broadcast). */
    override fun updateInfo(session: Long, transform: (CallInfo) -> CallInfo) {
        scope.launch(serial) {
            if (engineSession != session) return@launch
            val updated = transform(info ?: return@launch)
            info = updated
            (_state.value as? RecorderState.Active)?.let { cur ->
                _state.value = cur.copy(info = updated)
                Notifications.updateRecording(context, _state.value)
            }
        }
    }

    fun pause() = setPaused(true)
    fun resume() = setPaused(false)

    private fun setPaused(paused: Boolean) {
        scope.launch(serial) {
            val e = engine ?: return@launch
            if (paused) e.pause() else e.resume()
            (_state.value as? RecorderState.Active)?.let {
                _state.value = it.copy(paused = paused)
                Notifications.updateRecording(context, _state.value)
            }
        }
    }

    /** Stops the session (0 = whatever is running). With [discard] the audio is thrown away. */
    override fun stop(session: Long, discard: Boolean) {
        val target = if (session == 0L) pendingSession else session
        if (target == 0L || target != pendingSession) return
        scope.launch(serial) {
            val e = engine
            if (e == null || engineSession != target) {
                finishSession(target)
                return@launch
            }
            val callInfo = info ?: CallInfo(CallSource.MANUAL)
            // Never keep audio the user hasn't agreed to in "ask" mode.
            val drop = discard || awaitingConfirmation
            if (awaitingConfirmation) Logx.i("Recording not confirmed, discarding")
            Notifications.cancelAsk(context)
            val result = runCatching { e.stop() }.onFailure { Logx.e("Engine stop crashed", it) }.getOrNull()
            engine = null
            engineSession = 0
            info = null
            awaitingConfirmation = false
            _level.value = 0f
            releaseWakeLock()
            _state.value = RecorderState.Idle
            finishSession(target)
            RecordingService.stop(context)
            if (result != null) {
                scope.launch(serial) {
                    try {
                        finalize(callInfo, result, drop)
                    } finally {
                        filesInUse.remove(result.file.absolutePath)
                    }
                }
            }
        }
    }

    private fun finishSession(session: Long) {
        synchronized(this) { if (pendingSession == session) pendingSession = 0 }
    }

    private suspend fun finalize(call: CallInfo, result: RecorderEngine.Result, discard: Boolean) {
        val s = settings.current
        if (discard || !result.playable) {
            if (!discard) Logx.w("Recording produced no playable audio")
            result.file.delete()
            return
        }
        if (call.source != CallSource.MANUAL && result.durationMs < s.minDurationSec * 1000L) {
            Logx.i("Recording shorter than ${s.minDurationSec}s, discarded")
            result.file.delete()
            return
        }

        var number = call.number
        var direction = call.direction
        var name = call.displayName
        if (call.source == CallSource.PHONE) {
            // The system writes the call log right after the call ends.
            delay(1500)
            contacts.latestCallSince(call.startedAt)?.let { entry ->
                if (number == null || entry.number != null) number = entry.number ?: number
                if (entry.direction != CallDirection.UNKNOWN) direction = entry.direction
                if (name == null) name = entry.name
            }
            if (name == null) name = contacts.nameFor(number)
            if (!CallFilter.shouldKeep(s, number, isContact = name != null, name = name, final = true)) {
                Logx.i("Call filtered out by settings, discarded")
                result.file.delete()
                return
            }
        }

        if (call.source == CallSource.VOIP && !CallFilter.shouldKeepVoip(s, name, final = true)) {
            Logx.i("Internet call filtered out by settings, discarded")
            result.file.delete()
            return
        }

        val appLabel = if (call.source == CallSource.VOIP) VoipApps.label(context, call.appPackage) else null
        val fileName = storage.fileNameFor(call.startedAt, name ?: number, appLabel, result.format)
        val treeUri = s.storageTreeUri?.takeIf { storage.hasTreePermission(it) }
        val stored = try {
            storage.commit(result.file, fileName, result.format, treeUri)
        } catch (e: Exception) {
            Logx.e("Saving the recording failed", e)
            Notifications.showProblem(context, Notifications.Problem.SAVE_FAILED)
            return
        }
        val recording = Recording(
            uri = stored.uri.toString(),
            fileName = stored.fileName,
            format = result.format,
            sizeBytes = stored.sizeBytes,
            startedAt = call.startedAt,
            durationMs = result.durationMs,
            source = call.source,
            appPackage = call.appPackage,
            direction = direction,
            phoneNumber = number,
            contactName = name,
            waveform = result.waveform,
            silent = result.peak == 0,
        )
        val id = repository.insert(recording)
        Logx.i("Saved ${stored.fileName} (${result.durationMs} ms)")
        if (recording.silent) {
            Notifications.showProblem(context, Notifications.Problem.SILENT, recordingId = id)
        } else if (s.notifyAfterCall && call.source != CallSource.MANUAL) {
            Notifications.showSaved(context, recording.copy(id = id), appLabel)
        }
        runCatching { repository.cleanup(s.autoDeleteDays) }
    }


    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallRecorder:recording").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** Intent used by notification buttons. */
    fun actionIntent(action: String): Intent =
        Intent(context, NotificationActionReceiver::class.java).setAction(action)
}
