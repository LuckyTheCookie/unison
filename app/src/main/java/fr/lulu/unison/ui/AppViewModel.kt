package fr.lulu.unison.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.lulu.unison.UnisonApp
import fr.lulu.unison.data.Profile
import fr.lulu.unison.deezer.AddOutcome
import fr.lulu.unison.deezer.BridgeException
import fr.lulu.unison.deezer.BridgeState
import fr.lulu.unison.deezer.DeezerApi
import fr.lulu.unison.deezer.DeezerPlaylist
import fr.lulu.unison.deezer.DeezerLinkParser
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

sealed interface PlaylistsUi {
    data object Idle : PlaylistsUi
    data object Loading : PlaylistsUi
    data class Ready(val items: List<DeezerPlaylist>) : PlaylistsUi
    data class Error(val message: String) : PlaylistsUi
}

enum class SearchMode { Tracks, Albums, Playlists }

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as UnisonApp).graph
    val deezer = graph.deezer
    val sessions = graph.sessions
    val updates = graph.updates
    val updateState = updates.state

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

    private val _mode = MutableStateFlow(SearchMode.Tracks)
    val mode: StateFlow<SearchMode> = _mode.asStateFlow()
    private val _collections = MutableStateFlow<List<DeezerApi.CollectionHit>>(emptyList())
    val collections: StateFlow<List<DeezerApi.CollectionHit>> = _collections.asStateFlow()
    /** Albums / playlists en cours d'ajout ou ajoutés (clé « album:123 »). */
    private val _addedCollections = MutableStateFlow<Set<String>>(emptySet())
    val addedCollections: StateFlow<Set<String>> = _addedCollections.asStateFlow()

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

    // Pont avec le Deezer patché (ajout à une playlist)
    private val bridge = graph.bridge
    private val _bridgeState = MutableStateFlow<BridgeState>(BridgeState.DeezerMissing)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()
    private val _playlists = MutableStateFlow<PlaylistsUi>(PlaylistsUi.Idle)
    val playlists: StateFlow<PlaylistsUi> = _playlists.asStateFlow()
    private val _bridgeMessage = MutableStateFlow<String?>(null)
    val bridgeMessage: StateFlow<String?> = _bridgeMessage.asStateFlow()
    private var messageJob: Job? = null
    val chosenPlaylist: StateFlow<DeezerPlaylist?> = graph.profiles.bridgePlaylist
        .map { it?.let { (id, title) -> DeezerPlaylist(id, title, 0, false) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val autoAdd: StateFlow<Boolean> = graph.profiles.bridgeAutoAdd.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val hintDismissed: StateFlow<Boolean> = graph.profiles.bridgeHintDismissed.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        updates.clean(); updates.check()
        refreshBridge()
        // Ajout automatique : chaque nouveau titre joué sur ce téléphone rejoint la playlist choisie.
        viewModelScope.launch {
            active.flatMapLatest { h -> h?.snapshot?.map { it.anchor.item?.track } ?: flowOf(null) }
                .distinctUntilChangedBy { it?.id }
                .collect { track ->
                    val target = chosenPlaylist.value
                    if (track != null && target != null && autoAdd.value && listenHere.value &&
                        (_bridgeState.value as? BridgeState.Patched)?.hasToken == true
                    ) addToPlaylist(track, target, announce = false)
                }
        }
    }

    fun refreshBridge() {
        viewModelScope.launch { _bridgeState.value = bridge.state() }
    }

    fun loadPlaylists() {
        if (_playlists.value is PlaylistsUi.Loading) return
        _playlists.value = PlaylistsUi.Loading
        viewModelScope.launch {
            _playlists.value = try {
                PlaylistsUi.Ready(bridge.playlists())
            } catch (e: BridgeException) {
                if (e.needsDeezer) refreshBridge()
                PlaylistsUi.Error(bridgeError(e))
            } catch (t: Throwable) {
                PlaylistsUi.Error("Impossible de lire tes playlists")
            }
        }
    }

    fun choosePlaylist(p: DeezerPlaylist) = viewModelScope.launch { graph.profiles.setBridgePlaylist(p.id, p.title) }
    fun setAutoAdd(on: Boolean) = viewModelScope.launch { graph.profiles.setBridgeAutoAdd(on) }
    fun dismissBridgeHint() = viewModelScope.launch { graph.profiles.dismissBridgeHint() }

    /** Ajoute le titre en cours à la playlist choisie. */
    fun addCurrentToPlaylist() {
        val track = active.value?.snapshot?.value?.anchor?.item?.track ?: return
        val target = chosenPlaylist.value ?: return
        viewModelScope.launch { addToPlaylist(track, target, announce = true) }
    }

    private suspend fun addToPlaylist(track: Track, target: DeezerPlaylist, announce: Boolean) {
        val msg = try {
            when (bridge.addToPlaylist(target.id, track.id)) {
                AddOutcome.Added -> "Ajouté à « ${target.title} »"
                AddOutcome.Duplicate -> "Déjà dans « ${target.title} »"
                AddOutcome.NotAllowed -> "Tu ne peux pas modifier « ${target.title} »"
                AddOutcome.PlaylistFull -> "« ${target.title} » est pleine"
            }
        } catch (e: BridgeException) {
            if (e.needsDeezer) refreshBridge()
            bridgeError(e)
        } catch (t: Throwable) {
            "Ajout impossible"
        }
        if (announce || msg.startsWith("Ajout impossible") || msg.startsWith("Ouvre")) showBridgeMessage(msg)
    }

    private fun bridgeError(e: BridgeException) = when (e.code) {
        "no_token" -> "Ouvre Deezer une fois pour connecter ta session"
        "unavailable" -> "Deezer patché introuvable"
        "forbidden" -> "Deezer refuse cette version d'Unison"
        else -> "Ajout impossible (${e.code})"
    }

    private fun showBridgeMessage(msg: String) {
        messageJob?.cancel()
        _bridgeMessage.value = msg
        messageJob = viewModelScope.launch { delay(3_500); _bridgeMessage.value = null }
    }

    fun onResume() {
        updates.resumeAfterPermission()
        _listenerGranted.value = UnisonNotificationListener.isGranted(getApplication())
        deezer.refresh()
        refreshBridge()
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
        runSearch(debounce = true)
    }

    fun setMode(m: SearchMode) {
        if (_mode.value == m) return
        _mode.value = m
        runSearch(debounce = false)
    }

    private fun runSearch(debounce: Boolean) {
        val q = _query.value
        searchJob?.cancel()
        if (q.isBlank()) {
            _results.value = emptyList(); _collections.value = emptyList()
            _searching.value = false; _searchError.value = null; return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(280)
            _searching.value = true
            try {
                when (val m = _mode.value) {
                    SearchMode.Tracks -> _results.value = graph.api.search(q)
                    SearchMode.Albums -> _collections.value = graph.api.searchCollections(DeezerLinkParser.Kind.Album, q)
                    SearchMode.Playlists -> _collections.value = graph.api.searchCollections(DeezerLinkParser.Kind.Playlist, q)
                }
                _searchError.value = null
            } catch (t: Throwable) {
                _searchError.value = "Recherche impossible : vérifie ta connexion"
            } finally {
                _searching.value = false
            }
        }
    }

    /** Ajoute tout un album / une playlist à la file (en fin de file, ou juste après le titre en cours). */
    fun addCollection(hit: DeezerApi.CollectionHit, playNext: Boolean) {
        val key = "${hit.kind.path}:${hit.id}"
        if (key in _addedCollections.value) return
        _addedCollections.value = _addedCollections.value + key
        viewModelScope.launch {
            try {
                val c = graph.api.collection(hit.kind, hit.id)
                send(Cmd.AddTracks(c.tracks, playNext))
            } catch (t: Throwable) {
                _addedCollections.value = _addedCollections.value - key
                _searchError.value = t.message ?: "Impossible de charger ${hit.title}"
            }
        }
    }

    fun resetSearch() {
        _added.value = emptySet()
        _addedCollections.value = emptySet()
    }
}
