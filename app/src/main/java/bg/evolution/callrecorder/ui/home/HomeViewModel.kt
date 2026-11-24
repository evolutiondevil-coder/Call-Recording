package bg.evolution.callrecorder.ui.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.core.CallInfo
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.service.RecorderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface HomeFilter {
    data object All : HomeFilter
    data object Favorites : HomeFilter
    data object Phone : HomeFilter
    data object Manual : HomeFilter
    data class App(val packageName: String) : HomeFilter
}

data class DaySection(val day: LocalDate, val items: List<Recording>)

data class HomeUiState(
    val loading: Boolean = true,
    val sections: List<DaySection> = emptyList(),
    val filter: HomeFilter = HomeFilter.All,
    val filters: List<HomeFilter> = listOf(HomeFilter.All),
    val query: String = "",
    val totalCount: Int = 0,
    val totalDurationMs: Long = 0,
    val totalSize: Long = 0,
    val hasAnyRecording: Boolean = false,
    val selection: Set<Long> = emptySet(),
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val repo = graph.repository
    private val filter = MutableStateFlow<HomeFilter>(HomeFilter.All)
    private val query = MutableStateFlow("")
    private val pendingDelete = MutableStateFlow<Set<Long>>(emptySet())
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    val recorderState: StateFlow<RecorderState> = graph.controller.state
    val level: StateFlow<Float> = graph.controller.level
    val settings = graph.settings.settings
    val updates = graph.updates

    val state: StateFlow<HomeUiState> = combine(
        repo.recordings, filter, query, pendingDelete, selection,
    ) { all, f, q, pending, sel ->
        val visible = all.filter { it.id !in pending }
        val apps = visible.mapNotNull { it.appPackage }.distinct().sorted()
        val filters = buildList {
            add(HomeFilter.All)
            if (visible.any { it.favorite }) add(HomeFilter.Favorites)
            if (visible.any { it.source == CallSource.PHONE }) add(HomeFilter.Phone)
            apps.forEach { add(HomeFilter.App(it)) }
            if (visible.any { it.source == CallSource.MANUAL }) add(HomeFilter.Manual)
        }
        val activeFilter = if (f in filters) f else HomeFilter.All
        val needle = q.trim()
        val shown = visible.filter { r ->
            when (activeFilter) {
                HomeFilter.All -> true
                HomeFilter.Favorites -> r.favorite
                HomeFilter.Phone -> r.source == CallSource.PHONE
                HomeFilter.Manual -> r.source == CallSource.MANUAL
                is HomeFilter.App -> r.appPackage == activeFilter.packageName
            }
        }.filter { r -> needle.isEmpty() || r.matches(needle) }
        val zone = ZoneId.systemDefault()
        val sections = shown
            .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .map { (day, items) -> DaySection(day, items) }
            .sortedByDescending { it.day }
        HomeUiState(
            loading = false,
            sections = sections,
            filter = activeFilter,
            filters = filters,
            query = q,
            totalCount = visible.size,
            totalDurationMs = visible.sumOf { it.durationMs },
            totalSize = visible.sumOf { it.sizeBytes },
            hasAnyRecording = visible.isNotEmpty(),
            selection = sel.intersect(visible.map { it.id }.toSet()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun Recording.matches(q: String): Boolean {
        val digits = q.filter(Char::isDigit)
        return contactName?.contains(q, ignoreCase = true) == true ||
            note?.contains(q, ignoreCase = true) == true ||
            fileName.contains(q, ignoreCase = true) ||
            (digits.length >= 3 && phoneNumber?.filter(Char::isDigit)?.contains(digits) == true) ||
            (appPackage?.contains(q, ignoreCase = true) == true)
    }

    fun setFilter(f: HomeFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }

    fun toggleFavorite(r: Recording) {
        viewModelScope.launch { repo.setFavorite(r.id, !r.favorite) }
    }

    // ---- selection ---------------------------------------------------------------------
    fun toggleSelection(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun selectAll() { selection.value = state.value.sections.flatMap { s -> s.items.map { it.id } }.toSet() }
    fun clearSelection() { selection.value = emptySet() }

    fun selectedRecordings(): List<Recording> {
        val sel = selection.value
        return state.value.sections.flatMap { it.items }.filter { it.id in sel }
    }

    // ---- delete with undo ----------------------------------------------------------------
    /** Hides the recordings now; they are really deleted by [commitDelete] unless [undoDelete] is called. */
    fun requestDelete(ids: Set<Long>): Set<Long> {
        pendingDelete.update { it + ids }
        selection.value = emptySet()
        return ids
    }

    fun undoDelete(ids: Set<Long>) = pendingDelete.update { it - ids }

    fun commitDelete(ids: Set<Long> = pendingDelete.value) {
        if (ids.isEmpty()) return
        graph.scope.launch {
            repo.delete(ids)
            pendingDelete.update { it - ids }
        }
    }

    override fun onCleared() {
        commitDelete()
        super.onCleared()
    }

    // ---- recording -------------------------------------------------------------------------
    fun startManual() {
        graph.controller.start(CallInfo(source = CallSource.MANUAL), inPhoneCall = graph.callMonitor.inPhoneCall)
    }

    fun recordedMs(): Long = graph.controller.recordedMs()
    fun confirmRecording() = graph.controller.confirm()
    fun declineRecording() = graph.controller.decline()
    fun stopRecording() = graph.controller.stop()
    fun pauseRecording() = graph.controller.pause()
    fun resumeRecording() = graph.controller.resume()

    fun setAutoRecord(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setAutoRecord(enabled) }
    }

    fun shareIntent(recordings: List<Recording>): Intent? {
        if (recordings.isEmpty()) return null
        val mime = recordings.map { it.format.mimeType }.distinct().singleOrNull() ?: "audio/*"
        return graph.storage.shareIntent(recordings.map { it.uri }, mime)
    }
}
