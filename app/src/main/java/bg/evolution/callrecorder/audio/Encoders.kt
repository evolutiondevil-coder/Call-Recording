package bg.evolution.callrecorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.core.Quality
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Consumes mono 16-bit PCM and writes a finished audio file. */
interface PcmEncoder {
    val format: AudioFormat
    val sampleRate: Int

    fun encode(pcm: ShortArray, length: Int)

    /** Flushes everything and finalizes the file. Returns true when the file is playable. */
    fun finish(): Boolean

    /** Releases resources without finalizing (after a fatal error). Safe to call more than once. */
    fun release()
}

// ---------------------------------------------------------------------------------------------
// WAV
// ---------------------------------------------------------------------------------------------

class WavEncoder(file: File, override val sampleRate: Int) : PcmEncoder {
    override val format = AudioFormat.WAV
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var scratch = ByteArray(0)
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(header(0, sampleRate))
    }

    override fun encode(pcm: ShortArray, length: Int) {
        val bytes = length * 2
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        var j = 0
        for (i in 0 until length) {
            val s = pcm[i].toInt()
            scratch[j++] = (s and 0xFF).toByte()
            scratch[j++] = (s shr 8 and 0xFF).toByte()
        }
        raf.write(scratch, 0, bytes)
        dataBytes += bytes
    }

    override fun finish(): Boolean {
        if (closed) return false
        closed = true
        return try {
            raf.seek(0)
            raf.write(header(dataBytes, sampleRate))
            raf.close()
            dataBytes > 0
        } catch (e: IOException) {
            Logx.e("WAV finish failed", e)
            runCatching { raf.close() }
            false
        }
    }

    override fun release() {
        if (!closed) {
            closed = true
            runCatching { raf.close() }
        }
    }

    companion object {
        fun header(dataLength: Long, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
            val byteRate = sampleRate * channels * bits / 8
            val data = dataLength.coerceAtMost(0xFFFFFFFFL - 36)
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((36 + data).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16)
            b.putShort(1) // PCM
            b.putShort(channels.toShort())
            b.putInt(sampleRate)
            b.putInt(byteRate)
            b.putShort((channels * bits / 8).toShort())
            b.putShort(bits.toShort())
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(data.toInt())
            return b.array()
        }

        /** Rewrites the size fields of a WAV file whose recording was interrupted. */
        fun repair(file: File): Boolean = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() <= 44) return false
                val head = ByteArray(44)
                raf.readFully(head)
                val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val sampleRate = bb.getInt(24)
                raf.seek(0)
                raf.write(header(raf.length() - 44, sampleRate))
                true
            }
        }.getOrDefault(false)
    }
}

// ---------------------------------------------------------------------------------------------
// MP3 (LAME, pure Java port)
// ---------------------------------------------------------------------------------------------

class Mp3Encoder(file: File, override val sampleRate: Int, bitRate: Int) : PcmEncoder {
    override val format = AudioFormat.MP3
    private val out: OutputStream = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private var intBuf = IntArray(0)
    private var mp3Buf = ByteArray(0)
    private var bytesWritten = 0L
    private var closed = false

    init {
        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init()
        gfp.num_channels = 1
        gfp.in_samplerate = sampleRate
        gfp.out_samplerate = sampleRate
        gfp.mode = MPEGMode.MONO
        gfp.brate = bitRate / 1000
        gfp.quality = 5
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        // CBR stream without a Xing/Info header: duration is derived from the bit rate,
        // and the file stays valid even if recording is interrupted.
        gfp.bWriteVbrTag = false
        val rc = lame.lame_init_params(gfp)
        if (rc != 0) {
            runCatching { out.close() }
            throw IOException("LAME init failed: $rc")
        }
    }

    override fun encode(pcm: ShortArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = minOf(CHUNK, length - offset)
            if (intBuf.size < n) intBuf = IntArray(n)
            for (i in 0 until n) intBuf[i] = pcm[offset + i].toInt() shl 16
            val need = (1.25 * n).toInt() + 7200
            if (mp3Buf.size < need) mp3Buf = ByteArray(need)
            val r = lame.lame_encode_buffer_int(gfp, intBuf, intBuf, n, mp3Buf, 0, mp3Buf.size)
            if (r < 0) throw IOException("LAME encode error $r")
            if (r > 0) {
                out.write(mp3Buf, 0, r)
                bytesWritten += r
            }
            offset += n
        }
    }

    override fun finish(): Boolean {
        if (closed) return false
        closed = true
        return try {
            if (mp3Buf.size < 7200) mp3Buf = ByteArray(7200)
            val r = lame.lame_encode_flush(gfp, mp3Buf, 0, mp3Buf.size)
            if (r > 0) {
                out.write(mp3Buf, 0, r)
                bytesWritten += r
            }
            out.flush()
            out.close()
            bytesWritten > 0
        } catch (e: Exception) {
            Logx.e("MP3 finish failed", e)
            runCatching { out.close() }
            false
        } finally {
            runCatching { lame.lame_close(gfp) }
        }
    }

    override fun release() {
        if (!closed) {
            closed = true
            runCatching { out.close() }
            runCatching { lame.lame_close(gfp) }
        }
    }

    private companion object {
        const val CHUNK = 4096
    }
}

// ---------------------------------------------------------------------------------------------
// MediaCodec based encoders (AAC in MPEG-4, AMR-NB / AMR-WB raw files)
// ---------------------------------------------------------------------------------------------

abstract class MediaCodecEncoder(
    mime: String,
    final override val sampleRate: Int,
    bitRate: Int,
    extra: (MediaFormat) -> Unit = {},
) : PcmEncoder {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime)
    private val info = MediaCodec.BufferInfo()
    private var samplesQueued = 0L
    private var finished = false
    private var released = false

    init {
        val mf = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            extra(this)
        }
        try {
            codec.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    protected abstract fun onOutputFormat(format: MediaFormat)
    protected abstract fun onEncoded(buffer: ByteBuffer, info: MediaCodec.BufferInfo)

    /** Called after the codec drained everything. Returns true if the output is playable. */
    protected abstract fun onFinished(): Boolean
    protected abstract fun onRelease()

    override fun encode(pcm: ShortArray, length: Int) {
        var offset = 0
        var stalls = 0
        while (offset < length) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(endOfStream = false)
                if (++stalls > 300) throw IOException("Encoder stalled")
                continue
            }
            stalls = 0
            val buf = codec.getInputBuffer(idx) ?: throw IOException("No input buffer")
            buf.clear()
            val n = minOf(buf.remaining() / 2, length - offset)
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(idx, 0, n * 2, ptsUs(), 0)
            samplesQueued += n
            offset += n
            drain(endOfStream = false)
        }
    }

    private fun ptsUs(): Long = samplesQueued * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || System.nanoTime() > deadline) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormat(codec.outputFormat)
                idx >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig) {
                        codec.getOutputBuffer(idx)?.let { out ->
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            onEncoded(out, info)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun finish(): Boolean {
        if (finished || released) return false
        finished = true
        return try {
            var queued = false
            for (i in 0 until 100) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                    break
                }
                drain(endOfStream = false)
            }
            if (queued) drain(endOfStream = true)
            runCatching { codec.stop() }
            onFinished()
        } catch (e: Exception) {
            Logx.e("Codec finish failed", e)
            false
        } finally {
            release()
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { codec.release() }
        runCatching { onRelease() }
    }

    companion object {
        fun hasEncoder(mime: String): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}

class AacEncoder(private val file: File, sampleRate: Int, bitRate: Int) : MediaCodecEncoder(
    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, bitRate,
    { it.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) },
) {
    override val format = AudioFormat.M4A

    // Created when the codec reports its output format, so that a failure here goes through
    // the normal error path (which also releases the codec).
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var samples = 0
    private var muxerReleased = false

    override fun onOutputFormat(format: MediaFormat) {
        if (muxer != null) return
        val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = m
        track = m.addTrack(format)
        m.start()
    }

    override fun onEncoded(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        m.writeSampleData(track, buffer, info)
        samples++
    }

    override fun onFinished(): Boolean {
        val m = muxer ?: return false
        if (samples == 0) return false
        m.stop()
        return true
    }

    override fun onRelease() {
        if (muxerReleased) return
        muxerReleased = true
        // MediaMuxer.release() may throw if it was started but never stopped; that's fine here.
        runCatching { muxer?.release() }
    }
}

class AmrEncoder(file: File, private val wideband: Boolean) : MediaCodecEncoder(
    if (wideband) MediaFormat.MIMETYPE_AUDIO_AMR_WB else MediaFormat.MIMETYPE_AUDIO_AMR_NB,
    if (wideband) 16_000 else 8_000,
    if (wideband) 23_850 else 12_200,
) {
    override val format = if (wideband) AudioFormat.AMR_WB else AudioFormat.AMR_NB
    private val out = BufferedOutputStream(FileOutputStream(file), 16 * 1024)
    private var bytes = 0L
    private var scratch = ByteArray(0)
    private var closed = false

    init {
        out.write((if (wideband) "#!AMR-WB\n" else "#!AMR\n").toByteArray(Charsets.US_ASCII))
    }

    override fun onOutputFormat(format: MediaFormat) = Unit

    override fun onEncoded(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val n = buffer.remaining()
        if (scratch.size < n) scratch = ByteArray(n)
        buffer.get(scratch, 0, n)
        out.write(scratch, 0, n)
        bytes += n
    }

    override fun onFinished(): Boolean {
        out.flush()
        return bytes > 0
    }

    override fun onRelease() {
        if (closed) return
        closed = true
        runCatching { out.close() }
    }
}

object EncoderFactory {
    fun isSupported(format: AudioFormat): Boolean = when (format) {
        AudioFormat.M4A -> MediaCodecEncoder.hasEncoder(MediaFormat.MIMETYPE_AUDIO_AAC)
        AudioFormat.AMR_NB -> MediaCodecEncoder.hasEncoder(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
        AudioFormat.AMR_WB -> MediaCodecEncoder.hasEncoder(MediaFormat.MIMETYPE_AUDIO_AMR_WB)
        AudioFormat.MP3, AudioFormat.WAV -> true
    }

    fun create(format: AudioFormat, quality: Quality, file: File): PcmEncoder {
        val rate = format.sampleRate(quality)
        val bitRate = format.bitRate(quality)
        return when (format) {
            AudioFormat.M4A -> AacEncoder(file, rate, bitRate)
            AudioFormat.MP3 -> Mp3Encoder(file, rate, bitRate)
            AudioFormat.AMR_NB -> AmrEncoder(file, wideband = false)
            AudioFormat.AMR_WB -> AmrEncoder(file, wideband = true)
            AudioFormat.WAV -> WavEncoder(file, rate)
        }
    }
}
