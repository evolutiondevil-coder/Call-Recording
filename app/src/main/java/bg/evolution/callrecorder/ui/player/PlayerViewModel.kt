package bg.evolution.callrecorder.ui.player

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.data.db.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(private val graph: AppGraph, private val recordingId: Long) : ViewModel() {
    private val repo = graph.repository
    val player = AudioPlayer(graph.app, viewModelScope)

    sealed interface Load {
        data object Loading : Load
        data class Found(val recording: Recording) : Load
        data object Missing : Load
    }

    val load: StateFlow<Load> = repo.observe(recordingId)
        .map { if (it == null) Load.Missing else Load.Found(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Load.Loading)

    private val current: Recording? get() = (load.value as? Load.Found)?.recording

    private var loadedUri: String? = null

    init {
        viewModelScope.launch {
            load.collect { l ->
                val r = (l as? Load.Found)?.recording
                if (r != null && r.uri != loadedUri) {
                    loadedUri = r.uri
                    player.load(r.uri, r.durationMs)
                    if (!r.listened) repo.markListened(r.id)
                }
            }
        }
    }

    fun toggleFavorite() {
        val r = current ?: return
        viewModelScope.launch { repo.setFavorite(r.id, !r.favorite) }
    }

    fun saveNote(note: String) {
        viewModelScope.launch { repo.setNote(recordingId, note) }
    }

    fun rename(name: String) {
        viewModelScope.launch { repo.setName(recordingId, name) }
    }

    /** Deletes the recording; the screen closes itself when [load] turns into [Load.Missing]. */
    fun delete() {
        player.release()
        graph.scope.launch { repo.delete(listOf(recordingId)) }
    }

    fun excludeNumber() {
        val number = current?.phoneNumber ?: return
        viewModelScope.launch { graph.settings.addExcludedNumber(number) }
    }

    fun shareIntent(): Intent? {
        val r = current ?: return null
        return graph.storage.shareIntent(listOf(r.uri), r.format.mimeType)
    }

    fun export(target: Uri, onResult: (Boolean) -> Unit) {
        val r = current ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { graph.storage.exportTo(r.uri, target) }
            onResult(ok)
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
