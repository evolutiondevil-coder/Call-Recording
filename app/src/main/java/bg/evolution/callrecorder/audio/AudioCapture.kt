package bg.evolution.callrecorder.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import bg.evolution.callrecorder.core.AudioSourceOption
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.Logx
import android.media.AudioFormat as PlatformAudioFormat

/**
 * Wraps [AudioRecord]: walks through a chain of audio sources and sample rates until
 * one of them works, and can move on to the next source later (e.g. when the system
 * silences the current one during a call).
 */
class AudioCapture(
    private val sources: List<Int>,
    private val preferredRate: Int,
) {
    private var record: AudioRecord? = null
    private var index = -1

    var sampleRate: Int = preferredRate
        private set

    val currentSource: Int get() = sources.getOrElse(index) { -1 }
    val hasMoreSources: Boolean get() = index < sources.lastIndex

    /** Opens the first working source, starting at [fromIndex]. */
    fun open(fromIndex: Int = 0): Boolean {
        close()
        for (i in fromIndex until sources.size) {
            for (rate in candidateRates()) {
                val r = tryOpen(sources[i], rate) ?: continue
                record = r
                index = i
                sampleRate = rate
                Logx.i("Capture opened: source=${sourceName(sources[i])} rate=$rate")
                return true
            }
        }
        index = sources.size
        return false
    }

    fun switchToNextSource(): Boolean {
        if (!hasMoreSources) return false
        return open(index + 1)
    }

    /** Returns the number of samples read, or a negative AudioRecord error code. */
    fun read(buf: ShortArray): Int = record?.read(buf, 0, buf.size) ?: AudioRecord.ERROR_INVALID_OPERATION

    fun reopenCurrent(): Boolean = if (index in sources.indices) open(index) else false

    fun close() {
        record?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        record = null
    }

    private fun candidateRates(): List<Int> = listOf(preferredRate, 48_000, 44_100, 16_000, 8_000).distinct()

    @SuppressLint("MissingPermission")
    private fun tryOpen(source: Int, rate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            rate, PlatformAudioFormat.CHANNEL_IN_MONO, PlatformAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val size = maxOf(minBuf * 4, rate * 2) // at least one second of audio
        var r: AudioRecord? = null
        return try {
            r = AudioRecord(source, rate, PlatformAudioFormat.CHANNEL_IN_MONO, PlatformAudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return null
            }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                r.release()
                return null
            }
            r
        } catch (e: Exception) {
            Logx.w("Cannot open source ${sourceName(source)} @ $rate: ${e.message}")
            runCatching { r?.release() }
            null
        }
    }

    companion object {
        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            else -> "SOURCE_$source"
        }

        /**
         * Ordered list of sources to try. On Android 10+ ordinary apps cannot capture the
         * call downlink; with the accessibility service active, VOICE_RECOGNITION is the
         * source the system allows during calls.
         */
        fun sourceChain(option: AudioSourceOption, callSource: CallSource, inPhoneCall: Boolean): List<Int> {
            val auto: List<Int> = when {
                callSource == CallSource.MANUAL && !inPhoneCall -> listOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.CAMCORDER,
                )
                callSource == CallSource.VOIP -> listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.CAMCORDER,
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.CAMCORDER,
                )
                else -> listOf(
                    MediaRecorder.AudioSource.VOICE_CALL,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                )
            }
            val first = when (option) {
                AudioSourceOption.AUTO -> return auto
                AudioSourceOption.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                AudioSourceOption.MIC -> MediaRecorder.AudioSource.MIC
                AudioSourceOption.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                AudioSourceOption.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
                AudioSourceOption.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            }
            return (listOf(first) + auto).distinct()
        }
    }
}
