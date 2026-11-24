package bg.evolution.callrecorder.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
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
)

/** Small MediaPlayer wrapper with audio focus, speed control and a position ticker. */
class AudioPlayer(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var pendingSeek = -1L
    private var playWhenReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    fun load(uri: String, knownDurationMs: Long) {
        release()
        _state.value = PlayerState(durationMs = knownDurationMs, speed = _state.value.speed)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attributes)
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
                abandonFocus()
                stopTicker()
                _state.update { s -> s.copy(playing = false, positionMs = s.durationMs) }
            }
            mp.setOnErrorListener { _, what, extra ->
                Logx.w("MediaPlayer error $what/$extra")
                _state.update { s -> s.copy(error = true, playing = false) }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Logx.e("Cannot open recording", e)
            _state.update { it.copy(error = true) }
        }
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
            if (s.positionMs >= s.durationMs - 50 && s.durationMs > 0) mp.seekTo(0)
            mp.playbackParams = mp.playbackParams.setSpeed(s.speed)
            mp.start()
            _state.update { it.copy(playing = true) }
            startTicker()
        } catch (e: Exception) {
            Logx.w("play() failed", e)
        }
    }

    fun pause() {
        playWhenReady = false
        val mp = player ?: return
        runCatching { if (mp.isPlaying) mp.pause() }
        stopTicker()
        abandonFocus()
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

    private fun abandonFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    fun release() {
        stopTicker()
        abandonFocus()
        player?.let { runCatching { it.release() } }
        player = null
        pendingSeek = -1
        playWhenReady = false
    }
}
