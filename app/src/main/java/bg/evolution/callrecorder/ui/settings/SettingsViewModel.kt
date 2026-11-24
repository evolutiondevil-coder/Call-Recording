package bg.evolution.callrecorder.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.audio.EncoderFactory
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.AudioSourceOption
import bg.evolution.callrecorder.core.Quality
import bg.evolution.callrecorder.core.RecordFilterMode
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.core.ThemeMode
import bg.evolution.callrecorder.detect.VoipApp
import bg.evolution.callrecorder.detect.VoipApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val repo = graph.settings
    val settings = repo.settings
    val totalSize: StateFlow<Long> = graph.repository.totalSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Formats this device can actually encode (checked once, off the main thread). */
    private val _formats = MutableStateFlow(listOf(AudioFormat.M4A, AudioFormat.MP3, AudioFormat.WAV))
    val formats: StateFlow<List<AudioFormat>> = _formats

    private val _apps = MutableStateFlow<List<VoipApp>>(emptyList())
    val installedApps: StateFlow<List<VoipApp>> = _apps

    init {
        viewModelScope.launch {
            _formats.value = withContext(Dispatchers.Default) { AudioFormat.entries.filter(EncoderFactory::isSupported) }
        }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.Default) {
                VoipApps.installed(graph.app).distinctBy { it.label + it.packageName }
            }
        }
    }

    private fun set(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setAutoRecord(v: Boolean) = set { repo.setAutoRecord(v) }
    fun setRecordMode(v: RecordMode) = set { repo.setRecordMode(v) }
    fun setRecordPhone(v: Boolean) = set { repo.setRecordPhone(v) }
    fun setRecordVoip(v: Boolean) = set { repo.setRecordVoip(v) }
    fun setRecordOtherApps(v: Boolean) = set { repo.setRecordOtherApps(v) }
    fun setAppEnabled(pkg: String, v: Boolean) = set { repo.setAppEnabled(pkg, v) }
    fun setFormat(v: AudioFormat) = set { repo.setFormat(v) }
    fun setQuality(v: Quality) = set { repo.setQuality(v) }
    fun setAudioSource(v: AudioSourceOption) = set { repo.setAudioSource(v) }
    fun setGain(v: Int) = set { repo.setGainDb(v) }
    fun setFilterMode(v: RecordFilterMode) = set { repo.setFilterMode(v) }
    fun addExcluded(n: String) = set { repo.addExcludedNumber(n) }
    fun removeExcluded(n: String) = set { repo.removeExcludedNumber(n) }
    fun setMinDuration(v: Int) = set { repo.setMinDuration(v) }
    fun setAutoDelete(v: Int) = set {
        repo.setAutoDeleteDays(v)
        graph.repository.cleanup(v)
    }
    fun setNotifyAfterCall(v: Boolean) = set { repo.setNotifyAfterCall(v) }
    fun setTheme(v: ThemeMode) = set { repo.setThemeMode(v) }
    fun setDynamicColor(v: Boolean) = set { repo.setDynamicColor(v) }

    /**
     * Saves a folder picked with the system picker and keeps write access to it across reboots.
     * Access to a previously chosen folder is kept, because recordings saved there still need it.
     */
    fun setStorageFolder(uri: Uri?) = set {
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { graph.app.contentResolver.takePersistableUriPermission(uri, flags) }
        }
        repo.setStorageTreeUri(uri?.toString())
    }

    fun storageFolderName(uri: String?): String? = graph.storage.treeDisplayName(uri)
    fun storageFolderValid(uri: String?): Boolean = graph.storage.hasTreePermission(uri)
}
