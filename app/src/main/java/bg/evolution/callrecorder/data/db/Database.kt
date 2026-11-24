package bg.evolution.callrecorder.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallSource
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recordings", indices = [Index("startedAt")])
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** file:// URI for app storage or content:// URI for a user chosen folder. */
    val uri: String,
    val fileName: String,
    val format: AudioFormat,
    val sizeBytes: Long,
    val startedAt: Long,
    val durationMs: Long,
    val source: CallSource,
    val appPackage: String? = null,
    val direction: CallDirection = CallDirection.UNKNOWN,
    val phoneNumber: String? = null,
    val contactName: String? = null,
    val favorite: Boolean = false,
    val note: String? = null,
    val listened: Boolean = false,
    val waveform: ByteArray? = null,
    /** True when nothing but silence was captured (usually a permission/setup problem). */
    val silent: Boolean = false,
) {
    val title: String? get() = contactName?.takeIf { it.isNotBlank() } ?: phoneNumber

    // ByteArray breaks data-class equality; compare by identity of the row content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Recording) return false
        return id == other.id && uri == other.uri && fileName == other.fileName && format == other.format &&
            sizeBytes == other.sizeBytes && startedAt == other.startedAt && durationMs == other.durationMs &&
            source == other.source && appPackage == other.appPackage && direction == other.direction &&
            phoneNumber == other.phoneNumber && contactName == other.contactName && favorite == other.favorite &&
            note == other.note && listened == other.listened && silent == other.silent &&
            (waveform?.contentEquals(other.waveform) ?: (other.waveform == null))
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + uri.hashCode()
        h = 31 * h + favorite.hashCode()
        h = 31 * h + (note?.hashCode() ?: 0)
        h = 31 * h + listened.hashCode()
        h = 31 * h + (contactName?.hashCode() ?: 0)
        return h
    }
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Query("SELECT * FROM recordings WHERE id IN (:ids)")
    suspend fun getAll(ids: Collection<Long>): List<Recording>

    @Query("SELECT * FROM recordings")
    suspend fun all(): List<Recording>

    @Query("SELECT * FROM recordings WHERE favorite = 0 AND startedAt < :before")
    suspend fun olderThan(before: Long): List<Recording>

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Query("UPDATE recordings SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE recordings SET listened = 1 WHERE id = :id")
    suspend fun markListened(id: Long)

    @Query("UPDATE recordings SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("UPDATE recordings SET contactName = :name WHERE id = :id")
    suspend fun setName(id: Long, name: String?)

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM recordings")
    fun observeTotalSize(): Flow<Long>
}

@Database(entities = [Recording::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "recordings.db").build()
    }
}
