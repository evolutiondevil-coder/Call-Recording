package bg.evolution.callrecorder.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class EncodersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tone(rate: Int, seconds: Int) =
        ShortArray(rate * seconds) { i -> (sin(2 * PI * 440 * i / rate) * 10_000).toInt().toShort() }

    @Test
    fun wavHasCorrectHeaderAndSize() {
        val f = tmp.newFile("a.wav")
        val enc = WavEncoder(f, 16_000)
        val pcm = tone(16_000, 3)
        pcm.toList().chunked(1234).forEach { enc.encode(it.toShortArray(), it.size) }
        assertThat(enc.finish()).isTrue()
        assertThat(f.length()).isEqualTo(44L + pcm.size * 2)
        val h = ByteBuffer.wrap(f.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(String(f.readBytes().copyOfRange(0, 4))).isEqualTo("RIFF")
        assertThat(h.getInt(4)).isEqualTo(36 + pcm.size * 2)
        assertThat(h.getShort(20).toInt()).isEqualTo(1)
        assertThat(h.getShort(22).toInt()).isEqualTo(1)
        assertThat(h.getInt(24)).isEqualTo(16_000)
        assertThat(h.getInt(28)).isEqualTo(32_000)
        assertThat(h.getInt(40)).isEqualTo(pcm.size * 2)
    }

    @Test
    fun interruptedWavCanBeRepaired() {
        val f = tmp.newFile("b.wav")
        val enc = WavEncoder(f, 8_000)
        val pcm = tone(8_000, 1)
        enc.encode(pcm, pcm.size)
        enc.release() // simulate a crash: header still says 0 bytes
        assertThat(ByteBuffer.wrap(f.readBytes(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).int).isEqualTo(0)
        assertThat(WavEncoder.repair(f)).isTrue()
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(44).also { raf.readFully(it) }
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            assertThat(bb.getInt(40)).isEqualTo(pcm.size * 2)
            assertThat(bb.getInt(24)).isEqualTo(8_000)
        }
    }

    @Test
    fun mp3ProducesValidCbrStream() {
        for ((rate, kbps) in listOf(16_000 to 32_000, 32_000 to 64_000, 44_100 to 128_000)) {
            val f = tmp.newFile("t$rate.mp3")
            val enc = Mp3Encoder(f, rate, kbps)
            val pcm = tone(rate, 10)
            var i = 0
            while (i < pcm.size) {
                val n = minOf(rate / 25, pcm.size - i)
                enc.encode(pcm.copyOfRange(i, i + n), n)
                i += n
            }
            assertThat(enc.finish()).isTrue()
            val bytes = f.readBytes()
            // MPEG audio frame sync: 11 set bits.
            assertThat(bytes[0].toInt() and 0xFF).isEqualTo(0xFF)
            assertThat(bytes[1].toInt() and 0xE0).isEqualTo(0xE0)
            val expected = kbps / 8.0 * 10
            assertThat(bytes.size.toDouble()).isWithin(expected * 0.08).of(expected)
        }
    }

    @Test
    fun mp3FinishIsIdempotent() {
        val f = tmp.newFile("x.mp3")
        val enc = Mp3Encoder(f, 16_000, 32_000)
        enc.encode(tone(16_000, 1), 16_000)
        assertThat(enc.finish()).isTrue()
        assertThat(enc.finish()).isFalse()
        enc.release()
    }
}
