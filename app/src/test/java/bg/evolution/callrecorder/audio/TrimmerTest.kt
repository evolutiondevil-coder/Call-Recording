package bg.evolution.callrecorder.audio

import bg.evolution.callrecorder.core.AudioFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TrimmerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tone(rate: Int, seconds: Int) =
        ShortArray(rate * seconds) { i -> (sin(2 * PI * 440 * i / rate) * 10_000).toInt().toShort() }

    private fun wav(rate: Int, seconds: Int): File {
        val f = tmp.newFile()
        val enc = WavEncoder(f, rate)
        val pcm = tone(rate, seconds)
        enc.encode(pcm, pcm.size)
        enc.finish()
        return f
    }

    @Test
    fun wavIsCutByteExact() {
        val src = wav(16_000, 10)
        val out = tmp.newFile("cut.wav")
        val ms = Trimmer.trim(src, out, AudioFormat.WAV, 2_000, 5_500)
        assertThat(ms).isEqualTo(3_500)
        val bytes = 3_500 * 16 * 2
        assertThat(out.length()).isEqualTo(44L + bytes)
        val h = ByteBuffer.wrap(out.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(h.getInt(24)).isEqualTo(16_000)
        assertThat(h.getInt(40)).isEqualTo(bytes)
        val original = src.readBytes()
        val from = 44 + 2_000 * 16 * 2
        assertThat(out.readBytes().copyOfRange(44, out.length().toInt())).isEqualTo(original.copyOfRange(from, from + bytes))
    }

    @Test
    fun wavWithExtraChunksAndAWrongSizeIsHandled() {
        val pcm = ByteArray(8_000 * 2 * 3) { (it % 251).toByte() } // 3 s at 8 kHz
        val list = "LIST".toByteArray() + le(5) + "abcde".toByteArray() + byteArrayOf(0) // odd size + pad byte
        val fmt = WavEncoder.header(0, 8_000).copyOfRange(12, 36)
        val data = "data".toByteArray() + le(Int.MAX_VALUE) // an interrupted file claims far too much
        val body = "WAVE".toByteArray() + list + fmt + data + pcm
        val src = tmp.newFile().apply { writeBytes("RIFF".toByteArray() + le(body.size) + body) }
        val out = tmp.newFile()
        assertThat(Trimmer.trim(src, out, AudioFormat.WAV, 1_000, 10_000)).isEqualTo(2_000)
        assertThat(out.readBytes().copyOfRange(44, out.length().toInt())).isEqualTo(pcm.copyOfRange(16_000, pcm.size))
    }

    private fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /** An AMR stream with a mix of speech, comfort-noise and empty frames. */
    private fun amr(wideband: Boolean, frames: Int): Pair<File, List<ByteArray>> {
        val sizes = if (wideband) mapOf(8 to 61, 2 to 33, 9 to 6, 15 to 1) else mapOf(7 to 32, 4 to 20, 8 to 6, 15 to 1)
        val types = sizes.keys.toList()
        val list = (0 until frames).map { i ->
            val ft = types[i % types.size]
            ByteArray(sizes.getValue(ft)) { j -> if (j == 0) (ft shl 3 or 0x04).toByte() else (i + j).toByte() }
        }
        val out = ByteArrayOutputStream()
        out.write((if (wideband) "#!AMR-WB\n" else "#!AMR\n").toByteArray())
        list.forEach(out::write)
        return tmp.newFile().apply { writeBytes(out.toByteArray()) } to list
    }

    @Test
    fun amrIsCutOnFrames() {
        for (wb in listOf(false, true)) {
            val (src, frames) = amr(wb, 500) // 10 s
            val out = tmp.newFile()
            val format = if (wb) AudioFormat.AMR_WB else AudioFormat.AMR_NB
            assertThat(Trimmer.trim(src, out, format, 1_000, 3_000)).isEqualTo(2_000)
            val expected = ByteArrayOutputStream().apply {
                write((if (wb) "#!AMR-WB\n" else "#!AMR\n").toByteArray())
                frames.subList(50, 150).forEach(::write)
            }.toByteArray()
            assertThat(out.readBytes()).isEqualTo(expected)
        }
    }

    @Test
    fun amrStopsCleanlyAtATruncatedLastFrame() {
        val (full, _) = amr(false, 100)
        val src = tmp.newFile().apply { writeBytes(full.readBytes().dropLast(3).toByteArray()) }
        val out = tmp.newFile()
        val ms = Trimmer.trim(src, out, AudioFormat.AMR_NB, 0, 60_000)
        // The last frame (1 byte) and part of the one before it are missing: 98 whole frames remain.
        assertThat(ms).isEqualTo(98 * 20L)
    }

    private fun mp3(rate: Int, kbps: Int, seconds: Int): File {
        val f = tmp.newFile()
        val enc = Mp3Encoder(f, rate, kbps)
        val pcm = tone(rate, seconds)
        pcm.toList().chunked(4096).forEach { enc.encode(it.toShortArray(), it.size) }
        enc.finish()
        return f
    }

    /** Every byte of the file belongs to a whole frame; returns the frames' total length in ms. */
    private fun mp3Duration(file: File): Long {
        val b = file.readBytes()
        var pos = 0
        var samples = 0L
        var rate = 0
        while (pos + 4 <= b.size) {
            val h = ByteBuffer.wrap(b, pos, 4).int
            val f = Trimmer.mp3Frame(h) ?: error("No frame at $pos")
            samples += f.samples
            rate = f.sampleRate
            pos += f.size
        }
        assertThat(pos).isEqualTo(b.size)
        return samples * 1000 / rate
    }

    @Test
    fun mp3IsCutOnFrames() {
        for ((rate, kbps) in listOf(16_000 to 32_000, 44_100 to 128_000)) {
            val src = mp3(rate, kbps, 10)
            val out = tmp.newFile()
            val ms = Trimmer.trim(src, out, AudioFormat.MP3, 2_000, 6_000)
            val frameMs = (if (rate >= 32_000) 1152 else 576) * 1000L / rate
            assertThat(abs(ms - 4_000)).isAtMost(frameMs)
            assertThat(mp3Duration(out)).isEqualTo(ms)
        }
    }

    @Test
    fun mp3TagsAreLeftOut() {
        val audio = mp3(16_000, 32_000, 4).readBytes()
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 20) + ByteArray(20) { 0x55 }
        val id3v1 = "TAG".toByteArray() + ByteArray(125) { 0x41 }
        val src = tmp.newFile().apply { writeBytes(id3 + audio + id3v1) }
        val out = tmp.newFile()
        val ms = Trimmer.trim(src, out, AudioFormat.MP3, 0, 60_000)
        assertThat(mp3Duration(out)).isEqualTo(ms)
        assertThat(abs(ms - 4_000)).isAtMost(150)
    }

    @Test
    fun tooShortOrEmptyPartsAreRefused() {
        val src = wav(8_000, 2)
        assertThrows(IllegalArgumentException::class.java) { Trimmer.trim(src, tmp.newFile(), AudioFormat.WAV, 500, 1_200) }
        val out = File(tmp.root, "none.wav")
        assertThrows(Exception::class.java) { Trimmer.trim(src, out, AudioFormat.WAV, 5_000, 9_000) }
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun waveformIsSlicedToTheKeptPart() {
        val wave = ByteArray(100) { it.toByte() }
        val part = Trimmer.sliceWaveform(wave, 10_000, 2_000, 5_000)!!
        assertThat(part.first()).isEqualTo(20.toByte())
        assertThat(part.size).isEqualTo(30)
        assertThat(Trimmer.sliceWaveform(null, 10_000, 0, 1_000)).isNull()
        assertThat(Trimmer.sliceWaveform(wave, 10_000, 9_990, 10_000)!!.size).isEqualTo(1)
    }
}
