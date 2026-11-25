package bg.evolution.callrecorder.ui.player

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.data.db.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    sealed interface TrimState {
        data object Idle : TrimState
        data class Working(val replace: Boolean) : TrimState
        data class Done(val id: Long, val replaced: Boolean) : TrimState
        data object Failed : TrimState
    }

    private var cleared = false
    private val _trim = MutableStateFlow<TrimState>(TrimState.Idle)
    val trimState: StateFlow<TrimState> = _trim.asStateFlow()

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

    /** Keeps only [startMs]..[endMs]: as a new recording, or in place of this one ([replace]). */
    fun trim(startMs: Long, endMs: Long, replace: Boolean) {
        val r = current ?: return
        if (_trim.value is TrimState.Working) return
        val total = player.state.value.durationMs.takeIf { it > 0 } ?: r.durationMs
        _trim.value = TrimState.Working(replace)
        // The player must let go of a file that is about to be rewritten.
        if (replace) player.release() else player.pause()
        val suffix = graph.app.getString(R.string.trim_file_suffix)
        // Runs on the app's scope, so leaving the screen can't leave a half-finished file behind.
        graph.scope.launch {
            val result = runCatching {
                repo.trim(r, startMs, endMs, total, replace, graph.settings.current.storageTreeUri, suffix)
            }
            withContext(Dispatchers.Main) {
                if (cleared) return@withContext
                result.onSuccess { id ->
                    if (replace) {
                        val updated = repo.get(id)
                        loadedUri = r.uri
                        player.load(r.uri, updated?.durationMs ?: (endMs - startMs))
                    }
                    _trim.value = TrimState.Done(id, replace)
                }.onFailure { e ->
                    Logx.e("Trimming failed", e)
                    if (replace) player.load(r.uri, r.durationMs)
                    _trim.value = TrimState.Failed
                }
            }
        }
    }

    fun trimHandled() {
        if (_trim.value !is TrimState.Working) _trim.value = TrimState.Idle
    }

    override fun onCleared() {
        cleared = true
        player.release()
        super.onCleared()
    }
}
