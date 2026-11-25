package bg.evolution.callrecorder.data

import android.media.MediaMetadataRetriever
import bg.evolution.callrecorder.audio.Trimmer
import bg.evolution.callrecorder.audio.WavEncoder
import bg.evolution.callrecorder.core.FileNames
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.data.db.RecordingDao
import bg.evolution.callrecorder.data.storage.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class RecordingRepository(
    private val dao: RecordingDao,
    private val storage: RecordingStorage,
) {
    val recordings: Flow<List<Recording>> = dao.observeAll()
    val totalSize: Flow<Long> = dao.observeTotalSize()

    fun observe(id: Long): Flow<Recording?> = dao.observe(id)
    suspend fun get(id: Long): Recording? = dao.get(id)
    suspend fun insert(r: Recording): Long = dao.insert(r)
    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)
    suspend fun markListened(id: Long) = dao.markListened(id)
    suspend fun setNote(id: Long, note: String?) = dao.setNote(id, note?.trim()?.takeIf { it.isNotEmpty() })
    suspend fun setName(id: Long, name: String?) = dao.setName(id, name?.trim()?.takeIf { it.isNotEmpty() })

    /** Deletes the audio files and their database rows. Returns how many were removed. */
    suspend fun delete(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val rows = dao.getAll(ids)
        val removed = rows.filter { storage.delete(it.uri) }.map { it.id }
        dao.delete(removed)
        removed.size
    }

    /**
     * Keeps only [startMs]..[endMs] of a recording ([totalMs] long), without re-encoding.
     * With [replace] the original file is overwritten; otherwise the part becomes a new recording
     * next to it, named after the original plus [nameSuffix]. Returns the id of the result.
     */
    suspend fun trim(
        r: Recording,
        startMs: Long,
        endMs: Long,
        totalMs: Long,
        replace: Boolean,
        treeUri: String?,
        nameSuffix: String,
    ): Long = withContext(Dispatchers.IO) {
        val (source, sourceIsCopy) = storage.localFile(r.uri, r.format)
        val out = storage.cacheFile(r.format)
        try {
            val duration = Trimmer.trim(source, out, r.format, startMs, endMs)
            val waveform = Trimmer.sliceWaveform(r.waveform, totalMs, startMs, endMs)
            val size = out.length()
            if (replace) {
                storage.overwrite(r.uri, out)
                dao.update(r.copy(durationMs = duration, sizeBytes = size, waveform = waveform))
                r.id
            } else {
                val base = r.fileName.substringBeforeLast('.')
                val name = FileNames.sanitize("$base ($nameSuffix)", maxLength = 110) + "." + r.format.extension
                val stored = storage.commit(out, name, r.format, treeUri)
                dao.insert(
                    r.copy(
                        id = 0, uri = stored.uri.toString(), fileName = stored.fileName, sizeBytes = stored.sizeBytes,
                        durationMs = duration, waveform = waveform, startedAt = r.startedAt + startMs,
                        favorite = false, listened = true, silent = false,
                    ),
                )
            }
        } finally {
            out.delete()
            if (sourceIsCopy) source.delete()
        }
    }

    /** Applies the "delete recordings older than N days" setting. Favorites are always kept. */
    suspend fun cleanup(autoDeleteDays: Int) = withContext(Dispatchers.IO) {
        if (autoDeleteDays <= 0) return@withContext
        val before = System.currentTimeMillis() - autoDeleteDays * 24L * 3600 * 1000
        val old = dao.olderThan(before)
        if (old.isNotEmpty()) {
            Logx.i("Auto-deleting ${old.size} old recordings")
            delete(old.map { it.id })
        }
    }

    /** Removes rows whose file was deleted outside the app (e.g. with a file manager). */
    suspend fun pruneMissing() = withContext(Dispatchers.IO) {
        val missing = dao.all().filter { !storage.exists(it.uri) }.map { it.id }
        if (missing.isNotEmpty()) dao.delete(missing)
    }

    /**
     * Salvages temp files left behind when the process was killed in the middle of a call.
     * MP3/AMR are stream formats and stay playable; WAV only needs its header fixed.
     * Unfinished M4A files lack their index and can't be repaired, so they're removed.
     */
    suspend fun recoverOrphans(inUse: (File) -> Boolean, treeUri: String?) = withContext(Dispatchers.IO) {
        for (file in storage.orphanedTempFiles()) {
            if (inUse(file)) continue
            val format = when (file.extension.lowercase()) {
                "mp3" -> AudioFormat.MP3
                "amr" -> if (isAmrWb(file)) AudioFormat.AMR_WB else AudioFormat.AMR_NB
                "wav" -> AudioFormat.WAV
                else -> null
            }
            if (format == null || file.length() < 2048) {
                file.delete()
                continue
            }
            if (format == AudioFormat.WAV) WavEncoder.repair(file)
            val startedAt = file.name.removePrefix(RecordingStorage.TEMP_PREFIX).substringBefore('.').toLongOrNull()
                ?: file.lastModified()
            val duration = durationOf(file)
            if (duration <= 0) {
                file.delete()
                continue
            }
            runCatching {
                val name = storage.fileNameFor(startedAt, null, null, format)
                val stored = storage.commit(file, name, format, treeUri)
                dao.insert(
                    Recording(
                        uri = stored.uri.toString(), fileName = stored.fileName, format = format,
                        sizeBytes = stored.sizeBytes, startedAt = startedAt, durationMs = duration,
                        source = CallSource.MANUAL,
                    ),
                )
                Logx.i("Recovered interrupted recording ${stored.fileName}")
            }.onFailure { Logx.w("Recovery failed for ${file.name}", it) }
        }
    }

    private fun isAmrWb(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(9); it.read(b); String(b, Charsets.US_ASCII) == "#!AMR-WB\n" }
    }.getOrDefault(false)

    private fun durationOf(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }
}
