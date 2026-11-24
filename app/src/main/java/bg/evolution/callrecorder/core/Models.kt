package bg.evolution.callrecorder.core

/** Where a recorded conversation came from. */
enum class CallSource { PHONE, VOIP, MANUAL }

enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

/** Output container/codec chosen by the user. */
enum class AudioFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
) {
    M4A("m4a", "audio/mp4", "M4A (AAC)"),
    MP3("mp3", "audio/mpeg", "MP3"),
    AMR_WB("amr", "audio/amr", "AMR-WB"),
    AMR_NB("amr", "audio/amr", "AMR-NB"),
    WAV("wav", "audio/wav", "WAV"),
    ;

    /** AMR codecs only exist at one sample rate, everything else follows [Quality]. */
    fun sampleRate(quality: Quality): Int = when (this) {
        AMR_NB -> 8_000
        AMR_WB -> 16_000
        M4A -> when (quality) {
            Quality.LOW -> 16_000
            Quality.STANDARD -> 32_000
            Quality.HIGH -> 48_000
        }
        MP3 -> when (quality) {
            Quality.LOW -> 16_000
            Quality.STANDARD -> 32_000
            Quality.HIGH -> 44_100
        }
        WAV -> when (quality) {
            Quality.LOW -> 8_000
            Quality.STANDARD -> 16_000
            Quality.HIGH -> 44_100
        }
    }

    /** Bit rate in bits per second (approximate for WAV, which is uncompressed). */
    fun bitRate(quality: Quality): Int = when (this) {
        AMR_NB -> 12_200
        AMR_WB -> 23_850
        M4A -> when (quality) {
            Quality.LOW -> 32_000
            Quality.STANDARD -> 64_000
            Quality.HIGH -> 128_000
        }
        MP3 -> when (quality) {
            Quality.LOW -> 32_000
            Quality.STANDARD -> 64_000
            Quality.HIGH -> 128_000
        }
        WAV -> sampleRate(quality) * 16
    }

    /** Whether the quality preset changes anything for this format. */
    val supportsQuality: Boolean get() = this != AMR_NB && this != AMR_WB

    /** Rough size of one minute of audio, in bytes. */
    fun bytesPerMinute(quality: Quality): Long = bitRate(quality).toLong() * 60 / 8
}

enum class Quality { LOW, STANDARD, HIGH }

/** Which microphone/audio path to capture from. */
enum class AudioSourceOption { AUTO, VOICE_RECOGNITION, MIC, VOICE_COMMUNICATION, VOICE_CALL, CAMCORDER }

/** Record every call automatically, or ask the user each time a call starts. */
enum class RecordMode { ALWAYS, ASK }

/** Which phone calls should be recorded. */
enum class RecordFilterMode { ALL, CONTACTS_ONLY, UNKNOWN_ONLY }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Information gathered about a call while it happens. */
data class CallInfo(
    val source: CallSource,
    val appPackage: String? = null,
    val number: String? = null,
    val displayName: String? = null,
    val direction: CallDirection = CallDirection.UNKNOWN,
    val startedAt: Long = System.currentTimeMillis(),
)
