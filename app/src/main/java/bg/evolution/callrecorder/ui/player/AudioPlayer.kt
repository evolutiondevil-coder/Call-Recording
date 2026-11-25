package bg.evolution.callrecorder.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import bg.evolution.callrecorder.core.EarpiecePlayback
import bg.evolution.callrecorder.core.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: Boolean = false,
    /** Play quietly through the earpiece, like a phone call, instead of the loudspeaker. */
    val earpiece: Boolean = false,
)

/** Small MediaPlayer wrapper with audio focus, speed control, earpiece mode and a position ticker. */
class AudioPlayer(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var source: Pair<String, Long>? = null
    private var ticker: Job? = null
    private var pendingSeek = -1L
    private var playWhenReady = false
    private var routedToEarpiece = false
    private var proximityLock: PowerManager.WakeLock? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun attributes(earpiece: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest = buildFocusRequest(false)

    private fun buildFocusRequest(earpiece: Boolean) = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes(earpiece))
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    fun load(uri: String, knownDurationMs: Long) {
        val earpiece = _state.value.earpiece
        release()
        source = uri to knownDurationMs
        _state.value = PlayerState(durationMs = knownDurationMs, speed = _state.value.speed, earpiece = earpiece)
        focusRequest = buildFocusRequest(earpiece)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attributes(earpiece))
            mp.setDataSource(context, Uri.parse(uri))
            mp.setOnPreparedListener {
                val d = it.duration.toLong().takeIf { ms -> ms > 0 } ?: knownDurationMs
                _state.update { s -> s.copy(ready = true, durationMs = d) }
                if (pendingSeek >= 0) {
                    it.seekTo(pendingSeek.toInt())
                    pendingSeek = -1
                }
                if (playWhenReady) play()
            }
            mp.setOnCompletionListener {
                stopOutput()
                _state.update { s -> s.copy(playing = false, positionMs = s.durationMs) }
            }
            mp.setOnErrorListener { _, what, extra ->
                Logx.w("MediaPlayer error $what/$extra")
                stopOutput()
                _state.update { s -> s.copy(error = true, playing = false) }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Logx.e("Cannot open recording", e)
            _state.update { it.copy(error = true) }
        }
    }

    /** Switches between loudspeaker and earpiece, keeping the position (and playing on if it was). */
    fun setEarpiece(on: Boolean) {
        if (_state.value.earpiece == on) return
        val wasPlaying = _state.value.playing
        val position = _state.value.positionMs
        _state.update { it.copy(earpiece = on) }
        val src = source ?: return
        load(src.first, src.second)
        seekTo(position)
        if (wasPlaying) play()
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        val mp = player ?: return
        val s = _state.value
        if (!s.ready) {
            playWhenReady = true
            return
        }
        playWhenReady = false
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        try {
            if (s.earpiece) routeToEarpiece()
            if (s.positionMs >= s.durationMs - 50 && s.durationMs > 0) mp.seekTo(0)
            mp.playbackParams = mp.playbackParams.setSpeed(s.speed)
            mp.start()
            _state.update { it.copy(playing = true) }
            startTicker()
        } catch (e: Exception) {
            Logx.w("play() failed", e)
            stopOutput()
        }
    }

    fun pause() {
        playWhenReady = false
        val mp = player ?: return
        runCatching { if (mp.isPlaying) mp.pause() }
        stopOutput()
        _state.update { it.copy(playing = false, positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(it.positionMs)) }
    }

    fun seekTo(ms: Long) {
        val s = _state.value
        val target = ms.coerceIn(0, s.durationMs.coerceAtLeast(0))
        _state.update { it.copy(positionMs = target) }
        val mp = player ?: return
        if (!s.ready) {
            pendingSeek = target
            return
        }
        runCatching { mp.seekTo(target, MediaPlayer.SEEK_CLOSEST) }
    }

    fun skip(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    /** Changing speed while paused would start playback on some devices, so it's applied on play(). */
    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        val mp = player ?: return
        if (_state.value.playing) runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    /**
     * Earpiece output, as in a phone call: communication mode, the built-in earpiece as the
     * output device, and the screen switched off while the phone is held to the ear.
     */
    private fun routeToEarpiece() {
        if (routedToEarpiece) return
        routedToEarpiece = true
        EarpiecePlayback.active = true
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?.let { runCatching { audioManager.setCommunicationDevice(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "CallRecorder:earpiece").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        }
    }

    /** Undoes everything [play] set up: ticker, audio focus and earpiece routing. */
    private fun stopOutput() {
        stopTicker()
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        if (!routedToEarpiece) return
        routedToEarpiece = false
        runCatching { proximityLock?.takeIf { it.isHeld }?.release() }
        proximityLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        EarpiecePlayback.active = false
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                val pos = runCatching { mp.currentPosition.toLong() }.getOrNull() ?: break
                _state.update { it.copy(positionMs = pos) }
                delay(50)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    fun release() {
        stopOutput()
        player?.let { runCatching { it.release() } }
        player = null
        pendingSeek = -1
        playWhenReady = false
    }
}
