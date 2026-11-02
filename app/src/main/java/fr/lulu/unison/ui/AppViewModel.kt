package fr.lulu.unison.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.lulu.unison.UnisonApp
import fr.lulu.unison.data.Profile
import fr.lulu.unison.deezer.UnisonNotificationListener
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.SessionHandle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

sealed interface Busy {
    data object Idle : Busy
    data class Creating(val kind: TransportKind) : Busy
    data class Joining(val hostName: String) : Busy
    data class Failed(val message: String, val retry: (() -> Unit)? = null) : Busy
}

sealed interface LyricsUi {
    data object Idle : LyricsUi
    data class Loading(val trackId: Long) : LyricsUi
    data class Ready(val trackId: Long, val lyrics: fr.lulu.unison.lyrics.Lyrics) : LyricsUi
    data class Missing(val trackId: Long) : LyricsUi
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as UnisonApp).graph
    val deezer = graph.deezer
    val sessions = graph.sessions

    val profile: StateFlow<Profile?> = graph.profiles.profile.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val active: StateFlow<SessionHandle?> = sessions.active
    val deezerState = deezer.state
    val listenHere = sessions.listenHere
    val theme: StateFlow<fr.lulu.unison.ui.theme.UiStyle> =
        graph.profiles.theme.stateIn(viewModelScope, SharingStarted.Eagerly, fr.lulu.unison.ui.theme.UiStyle.Glass)

    fun setTheme(style: fr.lulu.unison.ui.theme.UiStyle) = viewModelScope.launch { graph.profiles.setTheme(style) }

    private val _busy = MutableStateFlow<Busy>(Busy.Idle)
    val busy: StateFlow<Busy> = _busy.asStateFlow()

    private val _listenerGranted = MutableStateFlow(UnisonNotificationListener.isGranted(app))
    val listenerGranted: StateFlow<Boolean> = _listenerGranted.asStateFlow()

    // Recherche
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<Track>>(emptyList())
    val results: StateFlow<List<Track>> = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()
    private var searchJob: Job? = null

    /** Titres ajoutés depuis la recherche (pour l'animation « coché »). */
    private val _added = MutableStateFlow<Set<Long>>(emptySet())
    val added: StateFlow<Set<Long>> = _added.asStateFlow()

    /** Paroles du titre en cours, préchargées dès qu'il change. */
    val lyrics: StateFlow<LyricsUi> = active
        .flatMapLatest { h -> h?.snapshot?.map { it.anchor.item?.track } ?: flowOf(null) }
        .distinctUntilChangedBy { it?.id }
        .transformLatest { track ->
            if (track == null) { emit(LyricsUi.Idle); return@transformLatest }
            emit(LyricsUi.Loading(track.id))
            val found = graph.lyrics.lyricsFor(track)
            emit(if (found != null) LyricsUi.Ready(track.id, found) else LyricsUi.Missing(track.id))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsUi.Idle)

    fun onResume() {
        _listenerGranted.value = UnisonNotificationListener.isGranted(getApplication())
        deezer.refresh()
    }

    fun saveProfile(name: String, color: Int) = viewModelScope.launch { graph.profiles.save(name, color) }

    fun create(kind: TransportKind) {
        _busy.value = Busy.Creating(kind)
        viewModelScope.launch {
            _busy.value = try {
                sessions.create(kind); Busy.Idle
            } catch (t: Throwable) {
                Busy.Failed(t.message ?: "Impossible de créer la session") { create(kind) }
            }
        }
    }

    fun join(info: JoinInfo) {
        if (_busy.value is Busy.Joining) return
        if (active.value?.joinInfo?.sessionId == info.sessionId) return
        _busy.value = Busy.Joining(info.hostName)
        viewModelScope.launch {
            _busy.value = try {
                sessions.join(info); Busy.Idle
            } catch (t: Throwable) {
                Busy.Failed(t.message ?: "Impossible de rejoindre la session") { join(info) }
            }
        }
    }

    fun dismissBusy() { _busy.value = Busy.Idle }

    fun leave() = viewModelScope.launch { sessions.leave() }

    fun send(cmd: Cmd) = sessions.send(cmd)

    fun setListenHere(v: Boolean) = sessions.setListenHere(v)

    fun addTrack(track: Track, playNext: Boolean = false) {
        send(Cmd.AddTrack(track, playNext))
        _added.value = _added.value + track.id
    }

    fun setQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.isBlank()) { _results.value = emptyList(); _searching.value = false; _searchError.value = null; return }
        searchJob = viewModelScope.launch {
            delay(280)
            _searching.value = true
            try {
                _results.value = graph.api.search(q)
                _searchError.value = null
            } catch (t: Throwable) {
                _searchError.value = "Recherche impossible : vérifie ta connexion"
            } finally {
                _searching.value = false
            }
        }
    }

    fun resetSearch() {
        _added.value = emptySet()
    }
}
