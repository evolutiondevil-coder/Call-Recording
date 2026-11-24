package bg.evolution.callrecorder.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    private fun sine(rate: Int, freq: Double, seconds: Double, amp: Double = 12_000.0) =
        ShortArray((rate * seconds).toInt()) { i -> (sin(2 * PI * freq * i / rate) * amp).toInt().toShort() }

    private fun rms(a: ShortArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until a.size) sum += a[i].toDouble() * a[i]
        return sqrt(sum / (a.size - from))
    }

    /** Dominant frequency estimated from zero crossings. */
    private fun zeroCrossFreq(a: ShortArray, rate: Int, from: Int): Double {
        var crossings = 0
        for (i in from + 1 until a.size) if ((a[i - 1] < 0) != (a[i] < 0)) crossings++
        return crossings / 2.0 / ((a.size - from).toDouble() / rate)
    }

    @Test
    fun resamplerDownsamplesKeepsToneAndLength() {
        val input = sine(48_000, 440.0, 2.0)
        val r = Resampler(48_000, 16_000)
        // Feed in odd-sized chunks to exercise the streaming state.
        val out = ArrayList<Short>()
        var i = 0
        val chunks = intArrayOf(1, 777, 1920, 333, 4096)
        var c = 0
        while (i < input.size) {
            val n = minOf(chunks[c++ % chunks.size], input.size - i)
            r.process(input.copyOfRange(i, i + n), n).forEach { out.add(it) }
            i += n
        }
        val result = out.toShortArray()
        assertThat(result.size.toDouble()).isWithin(3.0).of(input.size / 3.0)
        assertThat(zeroCrossFreq(result, 16_000, 1600)).isWithin(5.0).of(440.0)
        assertThat(rms(result, 1600)).isWithin(rms(input) * 0.05).of(rms(input))
    }

    @Test
    fun resamplerRemovesFrequenciesAboveNewNyquist() {
        // 7 kHz is above the 4 kHz Nyquist limit of an 8 kHz output and must be attenuated.
        val input = sine(48_000, 7_000.0, 1.0)
        val out = Resampler(48_000, 8_000).process(input, input.size)
        assertThat(rms(out, 800)).isLessThan(rms(input) * 0.3)
    }

    @Test
    fun resamplerUpsamples() {
        val input = sine(16_000, 300.0, 1.0)
        val out = Resampler(16_000, 32_000).process(input, input.size)
        assertThat(out.size.toDouble()).isWithin(3.0).of(32_000.0)
        assertThat(zeroCrossFreq(out, 32_000, 100)).isWithin(3.0).of(300.0)
    }

    @Test
    fun resamplerPassThroughCopies() {
        val input = shortArrayOf(1, 2, 3, 4)
        val r = Resampler(16_000, 16_000)
        val out = r.process(input, 3)
        assertThat(out.toList()).containsExactly(1.toShort(), 2.toShort(), 3.toShort()).inOrder()
        input[0] = 99
        assertThat(out[0]).isEqualTo(1.toShort())
    }

    @Test
    fun limiterNeverClipsAndIsTransparentBelowKnee() {
        assertThat(GainProcessor.limit(1000.0)).isEqualTo(1000)
        assertThat(GainProcessor.limit(-20_000.0)).isEqualTo(-20_000)
        var last = 0
        var v = 0.0
        while (v < 1_000_000) {
            val l = GainProcessor.limit(v)
            assertThat(l).isAtMost(32_767)
            assertThat(l).isAtLeast(last) // monotonic
            assertThat(GainProcessor.limit(-v)).isAtLeast(-32_767)
            last = l
            v += 97.0
        }
    }

    @Test
    fun gainBoostsQuietAudio() {
        val buf = ShortArray(100) { 1000 }
        val peak = GainProcessor(12).process(buf, buf.size)
        // +12 dB is ~3.98x
        assertThat(peak).isIn(3900..4000)
        assertThat(buf[50].toInt()).isEqualTo(peak)
    }

    @Test
    fun waveformStaysBoundedForLongRecordings() {
        val wb = WaveformBuilder(sampleRate = 8_000, maxPoints = 256)
        val chunk = ShortArray(800) { (if (it % 2 == 0) 16_000 else -16_000).toShort() }
        repeat(10_000) { wb.add(chunk, chunk.size) } // 1000 seconds
        val bytes = wb.toByteArray()
        assertThat(bytes.size).isAtMost(257)
        assertThat(bytes.size).isAtLeast(128)
        val level = bytes[10].toInt() and 0xFF
        assertThat(abs(level - (sqrt(16_000 / 32_768.0) * 255).toInt())).isAtMost(2)
    }

    @Test
    fun barsResampleToRequestedCount() {
        val wave = ByteArray(1000) { (it % 256).toByte() }
        val bars = WaveformBuilder.bars(wave, 60)
        assertThat(bars.size).isEqualTo(60)
        bars.forEach { assertThat(it).isIn(com.google.common.collect.Range.closed(0.04f, 1f)) }
        assertThat(WaveformBuilder.bars(null, 10).size).isEqualTo(10)
        assertThat(WaveformBuilder.bars(ByteArray(3) { 100 }, 10).size).isEqualTo(10)
    }
}
