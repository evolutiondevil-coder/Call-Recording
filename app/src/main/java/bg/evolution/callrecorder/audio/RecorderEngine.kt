package bg.evolution.callrecorder.audio

import android.media.AudioRecord
import android.os.Process
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.core.Quality
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records audio on a dedicated capture thread and encodes it on a second thread,
 * connected by a queue, so that a slow encoder never makes the capture drop audio.
 *
 * Robustness features:
 *  - falls back through audio sources and sample rates until one works;
 *  - if the system silences a source (only exact zeros for the first seconds, which a
 *    real microphone never produces), it switches to the next source on the fly;
 *  - falls back to another output format if an encoder can't be created on this device;
 *  - recovers from AudioRecord errors (e.g. audio server restart) by re-opening.
 */
class RecorderEngine(
    private val format: AudioFormat,
    private val quality: Quality,
    private val sources: List<Int>,
    private val gainDb: Int,
    private val fileFor: (AudioFormat) -> File,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        /** Live input level in 0f..1f, called roughly 25 times per second from the capture thread. */
        fun onLevel(level: Float) {}

        /** Called once if recording can't continue (the result is still finalized on [stop]). */
        fun onFatalError(error: Throwable) {}
    }

    data class Result(
        val file: File,
        val format: AudioFormat,
        val durationMs: Long,
        val waveform: ByteArray,
        val peak: Int,
        val playable: Boolean,
        val sourceUsed: Int,
    )

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    @Volatile
    var isPaused: Boolean = false
        private set

    private lateinit var encoder: PcmEncoder
    private lateinit var capture: AudioCapture
    private lateinit var outputFile: File
    private lateinit var waveform: WaveformBuilder
    private val queue = LinkedBlockingQueue<ShortArray>(QUEUE_CAPACITY)
    private var captureThread: Thread? = null
    private var encoderThread: Thread? = null

    @Volatile private var samplesWritten = 0L
    @Volatile private var maxPeak = 0
    @Volatile private var encoderOk = true
    @Volatile private var lastSource = -1

    val actualFormat: AudioFormat get() = encoder.format

    /** Elapsed recorded time (excluding pauses). */
    val recordedMs: Long get() = if (::encoder.isInitialized) samplesWritten * 1000 / encoder.sampleRate else 0

    /** Prepares encoder + microphone and starts the threads. Returns false if nothing could be opened. */
    fun start(): Boolean {
        check(!running.get()) { "Already started" }
        if (!createEncoder()) return false
        capture = AudioCapture(sources, encoder.sampleRate)
        if (!capture.open()) {
            Logx.e("No audio source could be opened")
            encoder.release()
            outputFile.delete()
            return false
        }
        lastSource = capture.currentSource
        waveform = WaveformBuilder(encoder.sampleRate)
        running.set(true)
        encoderThread = Thread(::encodeLoop, "rec-encoder").apply { start() }
        captureThread = Thread(::captureLoop, "rec-capture").apply { start() }
        return true
    }

    private fun createEncoder(): Boolean {
        val order = listOf(format, AudioFormat.M4A, AudioFormat.MP3, AudioFormat.WAV).distinct()
        for (f in order) {
            val file = fileFor(f)
            try {
                encoder = EncoderFactory.create(f, quality, file)
                outputFile = file
                if (f != format) Logx.w("Format $format unavailable, using $f")
                return true
            } catch (e: Throwable) {
                Logx.w("Encoder $f failed: ${e.message}")
                file.delete()
            }
        }
        return false
    }

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val gain = GainProcessor(gainDb)
        var buf = ShortArray(capture.sampleRate / 25)
        var resampler = Resampler(capture.sampleRate, encoder.sampleRate)
        var sourceSamples = 0L
        var sourceHeard = false
        var probing = true
        var errors = 0
        var dropped = 0
        try {
            while (running.get()) {
                val n = capture.read(buf)
                if (n < 0) {
                    Logx.w("AudioRecord read error $n")
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || ++errors > 3) {
                        if (!reopen()) break
                        buf = ShortArray(capture.sampleRate / 25)
                        resampler = Resampler(capture.sampleRate, encoder.sampleRate)
                        sourceSamples = 0; sourceHeard = false; errors = 0
                    }
                    continue
                }
                if (n == 0) continue
                errors = 0

                // Silence watchdog: a source the system has muted delivers exact zeros.
                if (probing && !sourceHeard) {
                    for (i in 0 until n) if (buf[i].toInt() != 0) { sourceHeard = true; break }
                    sourceSamples += n
                    if (!sourceHeard && sourceSamples >= capture.sampleRate * SILENCE_PROBE_SECONDS) {
                        if (!capture.hasMoreSources) {
                            probing = false
                        } else {
                            Logx.w("Source ${AudioCapture.sourceName(capture.currentSource)} is muted, switching")
                            if (!capture.switchToNextSource()) {
                                // None of the remaining sources opens: go back to the first one and keep it.
                                probing = false
                                if (!capture.open(0)) break
                            }
                            lastSource = capture.currentSource
                            buf = ShortArray(capture.sampleRate / 25)
                            resampler = Resampler(capture.sampleRate, encoder.sampleRate)
                            sourceSamples = 0
                            continue
                        }
                    }
                }

                val chunk = resampler.process(buf, n)
                val peak = gain.process(chunk, chunk.size)
                listener.onLevel(if (isPaused) 0f else (peak / 32768f).coerceIn(0f, 1f))
                if (isPaused || chunk.isEmpty()) continue
                if (!queue.offer(chunk)) {
                    if (dropped++ % 50 == 0) Logx.w("Encoder queue full, dropping audio")
                }
            }
        } catch (t: Throwable) {
            Logx.e("Capture loop crashed", t)
            listener.onFatalError(t)
        } finally {
            capture.close()
            running.set(false)
            // Make sure the encoder thread wakes up and finishes.
            while (!queue.offer(END, 100, TimeUnit.MILLISECONDS)) queue.poll()
        }
    }

    private fun reopen(): Boolean {
        Thread.sleep(200)
        if (capture.reopenCurrent() || capture.switchToNextSource()) {
            lastSource = capture.currentSource
            return true
        }
        Logx.e("Unable to recover audio capture")
        listener.onFatalError(IllegalStateException("Audio capture lost"))
        return false
    }

    private fun encodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            while (true) {
                val chunk = queue.take()
                if (chunk === END) break
                if (!encoderOk) continue
                try {
                    encoder.encode(chunk, chunk.size)
                    waveform.add(chunk, chunk.size)
                    samplesWritten += chunk.size
                    for (s in chunk) {
                        val a = if (s < 0) -s.toInt() else s.toInt()
                        if (a > maxPeak) maxPeak = a
                    }
                } catch (t: Throwable) {
                    Logx.e("Encoder failed", t)
                    encoderOk = false
                    running.set(false)
                    listener.onFatalError(t)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Stops recording and finalizes the file. Blocking; call it from a background thread. */
    fun stop(): Result {
        if (!stopped.compareAndSet(false, true)) error("stop() called twice")
        running.set(false)
        captureThread?.join(3_000)
        if (captureThread?.isAlive == true) {
            // Capture thread is stuck in read(): force the encoder loop to end.
            queue.clear()
            queue.offer(END)
        }
        encoderThread?.join(20_000)
        val playable = encoder.finish()
        encoder.release()
        val durationMs = samplesWritten * 1000 / encoder.sampleRate
        Logx.i("Recording stopped: ${durationMs}ms, playable=$playable, peak=$maxPeak")
        return Result(
            file = outputFile,
            format = encoder.format,
            durationMs = durationMs,
            waveform = waveform.toByteArray(),
            peak = maxPeak,
            playable = playable && samplesWritten > 0,
            sourceUsed = lastSource,
        )
    }

    private companion object {
        val END = ShortArray(0)
        const val QUEUE_CAPACITY = 25 * 90 // ~90 seconds of 40 ms chunks
        const val SILENCE_PROBE_SECONDS = 2
    }
}
