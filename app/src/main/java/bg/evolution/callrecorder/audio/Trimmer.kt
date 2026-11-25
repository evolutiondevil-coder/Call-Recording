package bg.evolution.callrecorder.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import bg.evolution.callrecorder.core.AudioFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts a part out of a recording without decoding and encoding it again, so the sound stays
 * exactly as it was recorded: WAV is cut by bytes, MP3 and AMR by their frames, and M4A by its
 * AAC samples. Cuts land on frame boundaries (20-70 ms), which can't be heard in speech.
 */
object Trimmer {
    /** The shortest part that can be kept. */
    const val MIN_LENGTH_MS = 1_000L

    /** Writes [startMs]..[endMs] of [input] to [output]. Returns the new length in milliseconds. */
    fun trim(input: File, output: File, format: AudioFormat, startMs: Long, endMs: Long): Long {
        require(startMs >= 0 && endMs - startMs >= MIN_LENGTH_MS) { "Bad range $startMs..$endMs" }
        output.delete()
        val ms = try {
            when (format) {
                AudioFormat.WAV -> trimWav(input, output, startMs, endMs)
                AudioFormat.MP3 -> trimMp3(input, output, startMs, endMs)
                AudioFormat.AMR_NB, AudioFormat.AMR_WB -> trimAmr(input, output, startMs, endMs)
                AudioFormat.M4A -> trimMp4(input, output, startMs, endMs)
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        if (ms <= 0 || !output.exists() || output.length() == 0L) {
            output.delete()
            throw IOException("Nothing was kept")
        }
        return ms
    }

    /** The part of a stored waveform that belongs to [startMs]..[endMs] of a [durationMs] long recording. */
    fun sliceWaveform(waveform: ByteArray?, durationMs: Long, startMs: Long, endMs: Long): ByteArray? {
        if (waveform == null || waveform.isEmpty() || durationMs <= 0) return waveform
        val from = (waveform.size * startMs / durationMs).toInt().coerceIn(0, waveform.size - 1)
        val to = ((waveform.size * endMs + durationMs - 1) / durationMs).toInt().coerceIn(from + 1, waveform.size)
        return waveform.copyOfRange(from, to)
    }

    // ---- WAV ------------------------------------------------------------------------------------

    internal fun trimWav(input: File, output: File, startMs: Long, endMs: Long): Long {
        RandomAccessFile(input, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") {
                throw IOException("Not a WAV file")
            }
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var blockAlign = 0
            var dataStart = -1L
            var dataSize = 0L
            val chunk = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val bodyStart = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        channels = b.getShort(2).toInt()
                        sampleRate = b.getInt(4)
                        blockAlign = b.getShort(12).toInt()
                        bits = b.getShort(14).toInt()
                    }
                    "data" -> {
                        dataStart = bodyStart
                        // An interrupted recording may claim more data than the file holds.
                        dataSize = minOf(size, raf.length() - bodyStart)
                        break
                    }
                }
                raf.seek(bodyStart + size + (size and 1))
            }
            if (dataStart < 0 || sampleRate <= 0 || blockAlign <= 0) throw IOException("Unsupported WAV file")
            val totalFrames = dataSize / blockAlign
            val first = (startMs * sampleRate / 1000).coerceIn(0, totalFrames)
            val last = (endMs * sampleRate / 1000).coerceIn(first, totalFrames)
            val bytes = (last - first) * blockAlign
            BufferedOutputStream(output.outputStream(), 256 * 1024).use { out ->
                out.write(WavEncoder.header(bytes, sampleRate, channels, bits))
                raf.seek(dataStart + first * blockAlign)
                copy(raf, out, bytes)
            }
            return (last - first) * 1000 / sampleRate
        }
    }

    private fun copy(raf: RandomAccessFile, out: java.io.OutputStream, count: Long) {
        val buf = ByteArray(256 * 1024)
        var left = count
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException()
            out.write(buf, 0, n)
            left -= n
        }
    }

    // ---- AMR ------------------------------------------------------------------------------------

    private const val AMR_NB_MAGIC = "#!AMR\n"
    private const val AMR_WB_MAGIC = "#!AMR-WB\n"
    private const val AMR_FRAME_MS = 20L

    /** Frame size in bytes (with its header byte) for each frame type of AMR-NB and AMR-WB. */
    private val AMR_NB_SIZES = intArrayOf(13, 14, 16, 18, 20, 21, 27, 32, 6, 1, 1, 1, 1, 1, 1, 1)
    private val AMR_WB_SIZES = intArrayOf(18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 1, 1, 1, 1, 1, 1)

    internal fun trimAmr(input: File, output: File, startMs: Long, endMs: Long): Long {
        BufferedInputStream(input.inputStream(), 64 * 1024).use { inp ->
            val head = ByteArray(9)
            val got = inp.readNBytesCompat(head, 9)
            val magic = when {
                got >= 9 && String(head, 0, 9, Charsets.US_ASCII) == AMR_WB_MAGIC -> AMR_WB_MAGIC
                got >= 6 && String(head, 0, 6, Charsets.US_ASCII) == AMR_NB_MAGIC -> AMR_NB_MAGIC
                else -> throw IOException("Not an AMR file")
            }
            val sizes = if (magic == AMR_WB_MAGIC) AMR_WB_SIZES else AMR_NB_SIZES
            // The NB header is shorter than the 9 bytes read: those already belong to the first frame.
            val pending = ArrayDeque<Byte>()
            for (i in magic.length until got) pending.addLast(head[i])
            fun next(): Int = if (pending.isNotEmpty()) pending.removeFirst().toInt() and 0xFF else inp.read()

            val firstFrame = startMs / AMR_FRAME_MS
            val endFrame = endMs / AMR_FRAME_MS
            var index = 0L
            var kept = 0L
            val frame = ByteArray(64)
            BufferedOutputStream(output.outputStream(), 64 * 1024).use { out ->
                out.write(magic.toByteArray(Charsets.US_ASCII))
                while (index < endFrame) {
                    val toc = next()
                    if (toc < 0) break
                    val size = sizes[(toc shr 3) and 0x0F]
                    frame[0] = toc.toByte()
                    var complete = true
                    for (i in 1 until size) {
                        val b = next()
                        if (b < 0) { complete = false; break }
                        frame[i] = b.toByte()
                    }
                    if (!complete) break
                    if (index >= firstFrame) {
                        out.write(frame, 0, size)
                        kept++
                    }
                    index++
                }
            }
            return kept * AMR_FRAME_MS
        }
    }

    // ---- MP3 ------------------------------------------------------------------------------------

    private val MP3_BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MP3_BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val MP3_RATES = arrayOf(intArrayOf(11025, 12000, 8000), intArrayOf(0, 0, 0), intArrayOf(22050, 24000, 16000), intArrayOf(44100, 48000, 32000))

    /** Size, sample rate and samples of one MPEG Layer III frame; null if [h] is not a frame header. */
    internal data class Mp3Frame(val size: Int, val sampleRate: Int, val samples: Int)

    internal fun mp3Frame(h: Int): Mp3Frame? {
        if ((h ushr 21) and 0x7FF != 0x7FF) return null
        val version = (h ushr 19) and 3 // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
        val layer = (h ushr 17) and 3 // 1 = Layer III
        val bitrateIndex = (h ushr 12) and 0xF
        val rateIndex = (h ushr 10) and 3
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val mpeg1 = version == 3
        val bitrate = (if (mpeg1) MP3_BITRATES_V1 else MP3_BITRATES_V2)[bitrateIndex] * 1000
        val rate = MP3_RATES[version][rateIndex]
        val padding = (h ushr 9) and 1
        val size = (if (mpeg1) 144 else 72) * bitrate / rate + padding
        return Mp3Frame(size, rate, if (mpeg1) 1152 else 576)
    }

    internal fun trimMp3(input: File, output: File, startMs: Long, endMs: Long): Long {
        BufferedInputStream(input.inputStream(), 256 * 1024).use { inp ->
            skipId3v2(inp)
            var samples = 0L // position in the source, in samples
            var keptSamples = 0L
            var rate = 0
            var firstFrame = true
            val frame = ByteArray(4096)
            BufferedOutputStream(output.outputStream(), 256 * 1024).use { out ->
                var h = 0
                var have = 0
                while (true) {
                    // Find the next frame header, skipping anything that isn't audio (e.g. a tag).
                    val b = inp.read()
                    if (b < 0) break
                    h = (h shl 8) or b
                    if (++have < 4) continue
                    val f = mp3Frame(h) ?: continue
                    frame[0] = (h ushr 24).toByte(); frame[1] = (h ushr 16).toByte()
                    frame[2] = (h ushr 8).toByte(); frame[3] = h.toByte()
                    if (f.size < 4 || f.size > frame.size) { have = 0; continue }
                    if (inp.readNBytesCompat(frame, f.size - 4, offset = 4) < f.size - 4) break
                    have = 0
                    if (firstFrame) {
                        firstFrame = false
                        // A Xing/Info header frame describes the whole file and holds no sound.
                        if (isInfoFrame(frame, f.size)) continue
                    }
                    rate = f.sampleRate
                    val startT = samples * 1000 / rate
                    val midT = (samples + f.samples / 2) * 1000 / rate
                    samples += f.samples
                    if (startT >= endMs) break
                    if (midT >= startMs && midT < endMs) {
                        out.write(frame, 0, f.size)
                        keptSamples += f.samples
                    }
                }
            }
            return if (rate > 0) keptSamples * 1000 / rate else 0
        }
    }

    private fun isInfoFrame(frame: ByteArray, size: Int): Boolean {
        val text = String(frame, 0, minOf(size, 64), Charsets.ISO_8859_1)
        return "Xing" in text || "Info" in text || "VBRI" in text
    }

    private fun skipId3v2(inp: BufferedInputStream) {
        inp.mark(10)
        val head = ByteArray(10)
        if (inp.readNBytesCompat(head, 10) == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val size = (head[6].toInt() and 0x7F shl 21) or (head[7].toInt() and 0x7F shl 14) or
                (head[8].toInt() and 0x7F shl 7) or (head[9].toInt() and 0x7F)
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            var left = (size + footer).toLong()
            while (left > 0) {
                val n = inp.skip(left)
                if (n <= 0) break
                left -= n
            }
        } else {
            inp.reset()
        }
    }

    private fun InputStream.readNBytesCompat(buf: ByteArray, count: Int, offset: Int = 0): Int {
        var total = 0
        while (total < count) {
            val n = read(buf, offset + total, count - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    // ---- M4A (AAC) ------------------------------------------------------------------------------

    private fun trimMp4(input: File, output: File, startMs: Long, endMs: Long): Long {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 16_000
            val frameUs = 1024L * 1_000_000 / rate
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(64 * 1024))
            val startUs = startMs * 1000
            val endUs = endMs * 1000

            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val outTrack = mux.addTrack(format)
            mux.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var first = -1L
            var last = -1L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t >= endUs) break
                if (t + frameUs / 2 >= startUs) {
                    if (first < 0) first = t
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, t - first, flags)
                    mux.writeSampleData(outTrack, buffer, info)
                    last = t - first
                }
                if (!extractor.advance()) break
            }
            if (first < 0) throw IOException("Nothing in range")
            mux.stop()
            return (last + frameUs) / 1000
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
