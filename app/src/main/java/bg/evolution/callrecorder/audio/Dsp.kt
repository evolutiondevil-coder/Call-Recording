package bg.evolution.callrecorder.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Second order low-pass filter (RBJ cookbook), used as anti-aliasing filter before decimation. */
internal class BiquadLowPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.7071) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (1.0 - cosW0) / 2.0 / a0
        b1 = (1.0 - cosW0) / a0
        b2 = b0
        a1 = -2.0 * cosW0 / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

/**
 * Streaming mono sample-rate converter (linear interpolation, with a 4th order
 * anti-aliasing low-pass when down-sampling). State is kept between calls, so
 * consecutive chunks join seamlessly.
 */
class Resampler(val inRate: Int, val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val filters: List<BiquadLowPass> =
        if (outRate < inRate) List(2) { BiquadLowPass(inRate, outRate * 0.45) } else emptyList()
    private var t = 0.0
    private var last = 0.0
    private var work = DoubleArray(0)

    val isPassThrough: Boolean get() = inRate == outRate

    fun process(input: ShortArray, length: Int): ShortArray {
        if (isPassThrough) return input.copyOf(length)
        if (length <= 0) return ShortArray(0)
        if (work.size < length) work = DoubleArray(length)
        for (i in 0 until length) {
            var v = input[i].toDouble()
            for (f in filters) v = f.process(v)
            work[i] = v
        }
        val estimate = ((length - 1 - t) / step).toInt().coerceAtLeast(0) + 2
        val out = ShortArray(estimate)
        var count = 0
        while (t < length - 1) {
            val i0 = floor(t).toInt()
            val frac = t - i0
            val s0 = if (i0 < 0) last else work[i0]
            val s1 = work[i0 + 1]
            val v = s0 + (s1 - s0) * frac
            if (count < out.size) out[count++] = clamp(v)
            t += step
        }
        t -= length
        last = work[length - 1]
        return if (count == out.size) out else out.copyOf(count)
    }

    private fun clamp(v: Double): Short = when {
        v > Short.MAX_VALUE -> Short.MAX_VALUE
        v < Short.MIN_VALUE -> Short.MIN_VALUE
        else -> v.toInt().toShort()
    }
}

/**
 * Applies a fixed gain with a soft-knee limiter, so that boosting quiet calls
 * never produces harsh digital clipping.
 */
class GainProcessor(gainDb: Int) {
    private val factor = 10.0.pow(gainDb / 20.0)
    val isUnity: Boolean = gainDb == 0

    /** Processes [length] samples in place and returns the absolute peak after processing. */
    fun process(buf: ShortArray, length: Int): Int {
        var peak = 0
        for (i in 0 until length) {
            val out = if (isUnity) buf[i].toInt() else limit(buf[i] * factor)
            buf[i] = out.toShort()
            val a = abs(out)
            if (a > peak) peak = a
        }
        return peak
    }

    companion object {
        private const val KNEE = 24_000.0
        private const val MAX = 32_767.0

        /** Linear below [KNEE], then smoothly approaches [MAX] without ever exceeding it. */
        fun limit(v: Double): Int {
            val a = abs(v)
            if (a <= KNEE) return v.toInt()
            val over = a - KNEE
            val shaped = KNEE + over / (1.0 + over / (MAX - KNEE))
            val r = shaped.coerceAtMost(MAX).toInt()
            return if (v < 0) -r else r
        }
    }
}

/**
 * Collects a compact amplitude envelope (max [maxPoints] values) while recording.
 * When the buffer fills up, neighbouring points are merged, so arbitrarily long
 * calls still produce a bounded waveform.
 */
class WaveformBuilder(sampleRate: Int, private val maxPoints: Int = 2048) {
    private var samplesPerPoint = (sampleRate / 20).coerceAtLeast(1)
    private var values = IntArray(maxPoints)
    private var count = 0
    private var currentPeak = 0
    private var currentCount = 0

    fun add(buf: ShortArray, length: Int) {
        for (i in 0 until length) {
            val a = abs(buf[i].toInt())
            if (a > currentPeak) currentPeak = a
            if (++currentCount >= samplesPerPoint) push()
        }
    }

    private fun push() {
        if (count == maxPoints) {
            val half = maxPoints / 2
            for (i in 0 until half) values[i] = maxOf(values[2 * i], values[2 * i + 1])
            count = half
            samplesPerPoint *= 2
            // Keep the partially accumulated point: it now spans a longer window.
            if (currentCount < samplesPerPoint) return
        }
        values[count++] = currentPeak
        currentPeak = 0
        currentCount = 0
    }

    /** Peaks mapped to 0..255 with a square-root curve that makes speech visible. */
    fun toByteArray(): ByteArray {
        val total = count + if (currentCount > 0) 1 else 0
        val out = ByteArray(total)
        for (i in 0 until count) out[i] = scale(values[i])
        if (currentCount > 0) out[count] = scale(currentPeak)
        return out
    }

    private fun scale(peak: Int): Byte {
        val norm = (peak / 32768.0).coerceIn(0.0, 1.0)
        return (sqrt(norm) * 255).toInt().coerceIn(0, 255).toByte()
    }

    companion object {
        /** Resamples a stored waveform to exactly [bars] values in 0f..1f for drawing. */
        fun bars(waveform: ByteArray?, bars: Int): FloatArray {
            if (bars <= 0) return FloatArray(0)
            if (waveform == null || waveform.isEmpty()) return FloatArray(bars) { 0.08f }
            val out = FloatArray(bars)
            val ratio = waveform.size.toDouble() / bars
            for (b in 0 until bars) {
                val start = floor(b * ratio).toInt().coerceIn(0, waveform.size - 1)
                val end = floor((b + 1) * ratio).toInt().coerceIn(start + 1, waveform.size)
                var max = 0
                for (i in start until end) max = maxOf(max, waveform[i].toInt() and 0xFF)
                out[b] = (max / 255f).coerceIn(0.04f, 1f)
            }
            return out
        }
    }
}
