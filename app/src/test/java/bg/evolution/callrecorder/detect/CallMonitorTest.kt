package bg.evolution.callrecorder.detect

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallInfo
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.data.contacts.ContactLookup
import bg.evolution.callrecorder.data.settings.AppSettings
import bg.evolution.callrecorder.data.settings.SettingsRepository
import bg.evolution.callrecorder.service.CallRecorderControl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

private class FakeRecorder : CallRecorderControl {
    data class Started(val id: Long, val info: CallInfo, val inPhoneCall: Boolean, val askFirst: Boolean)

    val started = mutableListOf<Started>()
    val stopped = mutableListOf<Long>()
    private var running = 0L
    private var next = 1L

    override val isBusy: Boolean get() = running != 0L

    override fun start(callInfo: CallInfo, inPhoneCall: Boolean, askFirst: Boolean): Long {
        if (running != 0L) return 0
        running = next++
        started += Started(running, callInfo, inPhoneCall, askFirst)
        return running
    }

    override fun stop(session: Long, discard: Boolean) {
        val target = if (session == 0L) running else session
        if (target == 0L || target != running) return
        stopped += target
        running = 0
    }

    override fun updateInfo(session: Long, transform: (CallInfo) -> CallInfo) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsRepository
    private lateinit var recorder: FakeRecorder
    private lateinit var monitor: CallMonitor
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before
    fun setUp() {
        settings = SettingsRepository(context, scope)
        runBlocking {
            settings.resetAll()
            awaitSettings { it == AppSettings() }
        }
        recorder = FakeRecorder()
        monitor = CallMonitor(context, settings, recorder, ContactLookup(context))
        audio.mode = AudioManager.MODE_NORMAL
    }

    @After
    fun tearDown() = scope.cancel()

    // Only a guard against hanging: on a busy CI machine (lint runs at the same time) the first
    // settings write after Robolectric starts can take several seconds.
    private fun awaitSettings(predicate: (AppSettings) -> Boolean) = runBlocking {
        withTimeout(30_000) { settings.settings.first(predicate) }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun incomingCallIsRecordedAndStopped() {
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, null)
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "+359 88 123 4567")
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, null)
        assertThat(recorder.started).hasSize(1)
        val s = recorder.started.single()
        assertThat(s.info.source).isEqualTo(CallSource.PHONE)
        assertThat(s.info.direction).isEqualTo(CallDirection.INCOMING)
        assertThat(s.info.number).isEqualTo("+359881234567")
        assertThat(s.inPhoneCall).isTrue()
        assertThat(s.askFirst).isFalse()

        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, "+359881234567")
        assertThat(recorder.stopped).containsExactly(s.id)
    }

    @Test
    fun outgoingCallUsesDialedNumber() {
        monitor.onOutgoingCall("0888 111 222")
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, null)
        val s = recorder.started.single()
        assertThat(s.info.direction).isEqualTo(CallDirection.OUTGOING)
        assertThat(s.info.number).isEqualTo("0888111222")
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertThat(recorder.stopped).containsExactly(s.id)
    }

    @Test
    fun missedCallIsNotRecorded() {
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "0888111222")
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertThat(recorder.started).isEmpty()
    }

    @Test
    fun callWaitingKeepsOneRecordingUntilTheEnd() {
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "0888111222")
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, null)
        // second call rings and is answered while the first one is still going
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "0888999999")
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, null)
        assertThat(recorder.started).hasSize(1)
        assertThat(recorder.stopped).isEmpty()
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_IDLE, null)
        assertThat(recorder.stopped).containsExactly(recorder.started.single().id)
        assertThat(recorder.isBusy).isFalse()
    }

    @Test
    fun nothingIsRecordedWhenAutoRecordIsOff() {
        runBlocking { settings.setAutoRecord(false) }
        awaitSettings { !it.autoRecord }
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "0888111222")
        assertThat(recorder.started).isEmpty()
    }

    @Test
    fun excludedNumberIsNotRecorded() {
        runBlocking { settings.addExcludedNumber("+359888111222") }
        awaitSettings { it.excludedNumbers.isNotEmpty() }
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_RINGING, "0888111222")
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, null)
        assertThat(recorder.started).isEmpty()
    }

    @Test
    fun askModeRequestsConfirmation() {
        runBlocking { settings.setRecordMode(RecordMode.ASK) }
        awaitSettings { it.recordMode == RecordMode.ASK }
        monitor.onPhoneState(TelephonyManager.EXTRA_STATE_OFFHOOK, "0888111222")
        assertThat(recorder.started.single().askFirst).isTrue()
    }

    private fun whatsappCall(key: String = "wa-call", incoming: Boolean = false) = CallNotification(
        key = key, packageName = "com.whatsapp", title = "Ivan Petrov", ongoing = true, incoming = incoming,
        postedAt = System.currentTimeMillis(),
    )

    @Test
    fun internetCallStartsAfterDebounceAndStopsWhenCallEnds() {
        monitor.onListenerConnected(true, emptyList())
        monitor.onCallNotificationPosted(whatsappCall(incoming = true))
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        idle(300)
        assertThat(recorder.started).isEmpty() // still debouncing
        idle(1_500)
        val s = recorder.started.single()
        assertThat(s.info.source).isEqualTo(CallSource.VOIP)
        assertThat(s.info.appPackage).isEqualTo("com.whatsapp")
        assertThat(s.info.displayName).isEqualTo("Ivan Petrov")
        assertThat(s.info.direction).isEqualTo(CallDirection.INCOMING)
        assertThat(s.inPhoneCall).isFalse()

        // A short glitch of the audio mode must not split the recording.
        audio.mode = AudioManager.MODE_NORMAL
        idle(500)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        idle(1_000)
        assertThat(recorder.stopped).isEmpty()

        audio.mode = AudioManager.MODE_NORMAL
        monitor.onNotificationRemoved("wa-call")
        idle(2_500)
        assertThat(recorder.stopped).containsExactly(s.id)
    }

    @Test
    fun audioModeAloneDoesNotStartRecording() {
        // e.g. a voice message played through the earpiece: no call notification.
        monitor.onListenerConnected(true, emptyList())
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        monitor.onForegroundApp("com.whatsapp")
        idle(3_000)
        assertThat(recorder.started).isEmpty()
    }

    @Test
    fun disabledAppIsNotRecorded() {
        runBlocking { settings.setAppEnabled("com.whatsapp", false) }
        awaitSettings { "com.whatsapp" in it.disabledApps }
        monitor.onListenerConnected(true, listOf(whatsappCall()))
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        monitor.onForegroundApp("com.whatsapp")
        idle(3_000)
        assertThat(recorder.started).isEmpty()
    }

    @Test
    fun dialerNotificationsAreNotTreatedAsInternetCalls() {
        monitor.onListenerConnected(true, emptyList())
        monitor.onCallNotificationPosted(whatsappCall().copy(key = "d", packageName = "com.google.android.dialer"))
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        idle(3_000)
        assertThat(recorder.started).isEmpty()
    }
}
