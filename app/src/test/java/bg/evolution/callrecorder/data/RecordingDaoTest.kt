package bg.evolution.callrecorder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.data.db.AppDatabase
import bg.evolution.callrecorder.data.db.Recording
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.recordings()

    @After
    fun close() = db.close()

    private fun rec(startedAt: Long, favorite: Boolean = false) = Recording(
        uri = "file:///x/$startedAt.m4a", fileName = "$startedAt.m4a", format = AudioFormat.M4A, sizeBytes = 1000,
        startedAt = startedAt, durationMs = 5000, source = CallSource.VOIP, appPackage = "com.viber.voip",
        direction = CallDirection.INCOMING, contactName = "Maria", favorite = favorite, waveform = byteArrayOf(1, 2, 3),
    )

    @Test
    fun insertObserveUpdateDelete() = runBlocking<Unit> {
        val a = dao.insert(rec(1_000))
        val b = dao.insert(rec(2_000))
        val all = dao.observeAll().first()
        assertThat(all.map { it.id }).containsExactly(b, a).inOrder() // newest first
        assertThat(all.first().waveform!!.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(all.first().format).isEqualTo(AudioFormat.M4A)

        dao.setFavorite(a, true)
        dao.setNote(a, "important")
        dao.markListened(a)
        val updated = dao.get(a)!!
        assertThat(updated.favorite).isTrue()
        assertThat(updated.note).isEqualTo("important")
        assertThat(updated.listened).isTrue()

        assertThat(dao.observeTotalSize().first()).isEqualTo(2000)
        dao.delete(listOf(a, b))
        assertThat(dao.observeAll().first()).isEmpty()
    }

    @Test
    fun cleanupQueryKeepsFavorites() = runBlocking<Unit> {
        dao.insert(rec(1_000, favorite = true))
        val old = dao.insert(rec(2_000))
        dao.insert(rec(10_000))
        assertThat(dao.olderThan(5_000).map { it.id }).containsExactly(old)
    }
}
