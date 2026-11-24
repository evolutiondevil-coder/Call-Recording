package bg.evolution.callrecorder.detect

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallInfo
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.core.PhoneNumbers
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.data.contacts.ContactLookup
import bg.evolution.callrecorder.data.settings.SettingsRepository
import bg.evolution.callrecorder.service.CallFilter
import bg.evolution.callrecorder.service.CallRecorderControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A notification that looks like an ongoing or ringing call of some app. */
data class CallNotification(
    val key: String,
    val packageName: String,
    val title: String?,
    val ongoing: Boolean,
    val incoming: Boolean,
    val postedAt: Long,
)

/**
 * Watches cellular calls (phone state broadcasts) and internet calls (call notifications +
 * the audio mode) and tells [RecordingController] when to start and stop.
 *
 * Every method must be called on the main thread.
 */
class CallMonitor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val controller: CallRecorderControl,
    private val contacts: ContactLookup,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---- service connection status (shown in the UI) -------------------------------------
    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()
    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    // ---- cellular --------------------------------------------------------------------------
    private var phoneState = TelephonyManager.CALL_STATE_IDLE
    private var ringing = false
    private var callNumber: String? = null
    private var outgoingNumber: String? = null
    private var phoneSession = 0L

    val inPhoneCall: Boolean get() = phoneState != TelephonyManager.CALL_STATE_IDLE

    fun onOutgoingCall(number: String?) {
        PhoneNumbers.normalize(number)?.let { outgoingNumber = it }
    }

    fun onPhoneState(state: String?, rawNumber: String?) {
        val number = PhoneNumbers.normalize(rawNumber)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // A second call ringing during an active one (call waiting) changes nothing here:
                // the running recording simply continues.
                if (phoneState == TelephonyManager.CALL_STATE_OFFHOOK) return
                if (phoneState == TelephonyManager.CALL_STATE_IDLE) {
                    ringing = true
                    callNumber = null
                }
                if (number != null) callNumber = number
                phoneState = TelephonyManager.CALL_STATE_RINGING
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (phoneState != TelephonyManager.CALL_STATE_OFFHOOK) {
                    phoneState = TelephonyManager.CALL_STATE_OFFHOOK
                    if (!ringing) callNumber = number ?: outgoingNumber
                    else if (callNumber == null) callNumber = number
                    startPhoneRecording()
                } else if (number != null && callNumber == null) {
                    // Android delivers the broadcast twice; the second copy carries the number.
                    callNumber = number
                    val session = phoneSession
                    if (session != 0L) controller.updateInfo(session) { it.copy(number = number, displayName = it.displayName ?: contacts.nameFor(number)) }
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (phoneState != TelephonyManager.CALL_STATE_IDLE) {
                    phoneState = TelephonyManager.CALL_STATE_IDLE
                    if (phoneSession != 0L) controller.stop(phoneSession)
                    phoneSession = 0
                }
                ringing = false
                callNumber = null
                outgoingNumber = null
                scheduleEvaluate()
            }
        }
    }

    private fun startPhoneRecording() {
        if (phoneSession != 0L) return
        val s = settings.current
        if (!s.autoRecord || !s.recordPhoneCalls) return
        val number = callNumber
        val name = contacts.nameFor(number)
        if (number != null && !CallFilter.shouldKeep(s, number, isContact = name != null)) {
            Logx.i("Not recording this call (filter)")
            return
        }
        if (voipSession != 0L) return
        val info = CallInfo(
            source = CallSource.PHONE,
            number = number,
            displayName = name,
            direction = if (ringing) CallDirection.INCOMING else CallDirection.OUTGOING,
        )
        phoneSession = controller.start(info, inPhoneCall = true, askFirst = s.recordMode == RecordMode.ASK)
    }

    // ---- internet calls -------------------------------------------------------------------
    private val callNotifications = LinkedHashMap<String, CallNotification>()
    private val incomingSeen = HashMap<String, Long>()
    private var foregroundPkg: String? = null
    private var foregroundAt = 0L
    private var voipSession = 0L
    private var voipPkg: String? = null
    /** When the call audio mode was first seen (0 = not in that mode). Used to debounce. All times are elapsedRealtime. */
    private var communicationSince = 0L
    private var inactiveSince = 0L
    private var ticking = false
    private var modeListener: Any? = null

    fun onAccessibilityConnected(connected: Boolean) {
        _accessibilityConnected.value = connected
        if (connected) registerModeListener()
        scheduleEvaluate()
    }

    fun onListenerConnected(connected: Boolean, active: List<CallNotification>) {
        _listenerConnected.value = connected
        callNotifications.clear()
        active.forEach { callNotifications[it.key] = it }
        if (connected) registerModeListener()
        scheduleEvaluate()
    }

    fun onCallNotificationPosted(n: CallNotification) {
        callNotifications[n.key] = n
        if (n.incoming) incomingSeen[n.packageName] = SystemClock.elapsedRealtime()
        // The contact name often only appears once the call is connected.
        if (voipSession != 0L && n.packageName == voipPkg) {
            val title = n.title
            if (title != null) controller.updateInfo(voipSession) { it.copy(displayName = title) }
        }
        scheduleEvaluate()
    }

    fun onNotificationRemoved(key: String) {
        if (callNotifications.remove(key) != null) scheduleEvaluate()
    }

    fun onForegroundApp(pkg: String) {
        if (pkg == "com.android.systemui" || pkg == context.packageName) return
        foregroundPkg = pkg
        foregroundAt = SystemClock.elapsedRealtime()
        if (VoipApps.isKnown(pkg)) scheduleEvaluate()
    }

    private fun registerModeListener() {
        if (modeListener != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val l = AudioManager.OnModeChangedListener { scheduleEvaluate() }
        runCatching { audioManager.addOnModeChangedListener(context.mainExecutor, l) }
            .onSuccess { modeListener = l }
    }

    private val tick = Runnable {
        ticking = false
        evaluate()
    }

    private fun scheduleEvaluate() {
        main.removeCallbacks(tick)
        ticking = false
        evaluate()
    }

    private fun isCommunicationMode(): Boolean {
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return mode == AudioManager.MODE_IN_COMMUNICATION ||
            (mode == AudioManager.MODE_IN_CALL && phoneState == TelephonyManager.CALL_STATE_IDLE)
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val communication = isCommunicationMode()
        communicationSince = if (communication) (communicationSince.takeIf { it != 0L } ?: now) else 0L
        if (voipSession != 0L) {
            // Wait until the call mode has been gone for a moment so short glitches don't split a call.
            if (!communication || inPhoneCall) {
                if (inactiveSince == 0L) inactiveSince = now
                if (now - inactiveSince >= STOP_DEBOUNCE_MS) stopVoip()
            } else {
                inactiveSince = 0L
            }
        } else {
            maybeStartVoip(communication, now)
        }
        val keepWatching = voipSession != 0L || communication || callNotifications.isNotEmpty() ||
            (VoipApps.isKnown(foregroundPkg) && now - foregroundAt < 10 * 60_000)
        if (keepWatching && !ticking) {
            ticking = true
            main.postDelayed(tick, TICK_MS)
        }
    }

    private fun maybeStartVoip(communication: Boolean, now: Long) {
        val s = settings.current
        if (!communication || !s.autoRecord || !s.recordVoip || inPhoneCall || controller.isBusy) return
        val pkg = pickCallingApp() ?: return
        if (!s.isAppEnabled(pkg) || (!VoipApps.isKnown(pkg) && !s.recordOtherApps)) return
        // Require the call mode for a moment before starting, to skip voice-message playback blips.
        if (now - communicationSince < START_DEBOUNCE_MS) return
        startVoip(pkg)
    }

    /** Which app is in a call right now, or null if we can't tell. */
    private fun pickCallingApp(): String? {
        val fromNotifications = callNotifications.values
            .filter { !VoipApps.isDialer(context, it.packageName) }
            .maxWithOrNull(compareBy<CallNotification> { it.ongoing && !it.incoming }.thenBy { it.postedAt })
        if (fromNotifications != null) return fromNotifications.packageName
        // Without notification access, fall back to the app in front if something is
        // actually capturing the microphone for voice communication.
        if (!_listenerConnected.value) {
            val fg = foregroundPkg
            if (VoipApps.isKnown(fg) && SystemClock.elapsedRealtime() - foregroundAt < 10 * 60_000 && voiceCaptureActive()) {
                return fg
            }
        }
        return null
    }

    private fun voiceCaptureActive(): Boolean = runCatching {
        audioManager.activeRecordingConfigurations.any {
            it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
    }.getOrDefault(false)

    private fun startVoip(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val notif = callNotifications.values.filter { it.packageName == pkg }.maxByOrNull { it.postedAt }
        val incoming = (incomingSeen[pkg] ?: 0L) > now - 3 * 60_000
        val direction = when {
            incoming -> CallDirection.INCOMING
            foregroundPkg == pkg && now - foregroundAt < 10 * 60_000 -> CallDirection.OUTGOING
            else -> CallDirection.UNKNOWN
        }
        val info = CallInfo(
            source = CallSource.VOIP,
            appPackage = pkg,
            displayName = notif?.title,
            direction = direction,
        )
        val session = controller.start(info, inPhoneCall = false, askFirst = settings.current.recordMode == RecordMode.ASK)
        if (session != 0L) {
            voipSession = session
            voipPkg = pkg
            inactiveSince = 0L
            incomingSeen.remove(pkg)
            Logx.i("Internet call detected in $pkg")
        }
    }

    private fun stopVoip() {
        controller.stop(voipSession)
        voipSession = 0
        voipPkg = null
        inactiveSince = 0L
    }

    private companion object {
        const val TICK_MS = 500L
        const val START_DEBOUNCE_MS = 1_000L
        const val STOP_DEBOUNCE_MS = 1_200L
    }
}
