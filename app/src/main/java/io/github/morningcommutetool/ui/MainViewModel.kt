package io.github.morningcommutetool.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.morningcommutetool.bg.Scheduler
import io.github.morningcommutetool.data.ActiveWindow
import io.github.morningcommutetool.data.Departure
import io.github.morningcommutetool.data.Engine
import io.github.morningcommutetool.data.Entur
import io.github.morningcommutetool.data.Follow
import io.github.morningcommutetool.data.Settings
import io.github.morningcommutetool.data.Snapshot
import io.github.morningcommutetool.data.Station
import io.github.morningcommutetool.data.Store
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val hits: List<Entur.StationHit>) : SearchState
    data class Error(val message: String) : SearchState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store.get(app)

    val stations: StateFlow<List<Station>?> = store.stations.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val snapshot: StateFlow<Snapshot> = store.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, Snapshot())
    val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val follow: StateFlow<Follow?> = store.follow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    /** Station to scroll to after the app was opened from the widget or a notification. */
    private val _focus = MutableStateFlow<String?>(null)
    val focus = _focus.asStateFlow()

    val query = MutableStateFlow("")
    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search = _search.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query.debounce(300).collectLatest { q ->
                if (q.trim().length < 2) {
                    _search.value = SearchState.Idle
                    return@collectLatest
                }
                _search.value = SearchState.Loading
                _search.value = try {
                    SearchState.Results(Entur.searchStations(q.trim()))
                } catch (e: Exception) {
                    SearchState.Error(Engine.describe(e))
                }
            }
        }
    }

    fun focusStation(id: String) { _focus.value = id }
    fun focusHandled() { _focus.value = null }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try { Engine.refresh(getApplication()) } finally { _refreshing.value = false }
        }
    }

    /** Quiet refresh used by the on-screen auto-update. */
    fun refreshSilently() {
        viewModelScope.launch { runCatching { Engine.refresh(getApplication()) } }
    }

    fun addStation(hit: Entur.StationHit) = viewModelScope.launch {
        store.updateStations { list -> if (list.any { it.id == hit.id }) list else list + Station(hit.id, hit.name) }
        query.value = ""
        Engine.refresh(getApplication())
    }

    fun removeStation(id: String) = viewModelScope.launch {
        store.updateStations { list -> list.filterNot { it.id == id } }
        if (follow.value?.stationId == id) store.setFollow(null)
    }

    fun moveStation(id: String, by: Int) = viewModelScope.launch {
        store.updateStations { list ->
            val i = list.indexOfFirst { it.id == id }
            val j = (i + by).coerceIn(0, list.lastIndex)
            if (i < 0 || i == j) list else list.toMutableList().apply { add(j, removeAt(i)) }
        }
    }

    fun setFilter(id: String, lines: Set<String>, destinations: Set<String>) = viewModelScope.launch {
        store.updateStations { list -> list.map { if (it.id == id) it.copy(lines = lines, destinations = destinations) else it } }
        Engine.refresh(getApplication())
    }

    fun follow(station: Station, d: Departure) = viewModelScope.launch {
        store.setFollow(Follow(station.id, station.name, d.serviceJourneyId, d.date, d.aimed, d.line, d.destination))
        Scheduler.startLive(getApplication())
    }

    fun stopFollowing() = viewModelScope.launch {
        store.setFollow(null)
        Scheduler.startLive(getApplication())
    }

    fun updateSettings(f: (Settings) -> Settings) = viewModelScope.launch {
        store.updateSettings(f)
        Scheduler.sync(getApplication())
    }

    fun addWindow(start: Int, end: Int, days: Set<Int>) =
        updateSettings { it.copy(windows = it.windows + ActiveWindow(UUID.randomUUID().toString(), start, end, days)) }

    fun replaceWindow(w: ActiveWindow) =
        updateSettings { s -> s.copy(windows = s.windows.map { if (it.id == w.id) w else it }) }

    fun removeWindow(id: String) = updateSettings { s -> s.copy(windows = s.windows.filterNot { it.id == id }) }
}
