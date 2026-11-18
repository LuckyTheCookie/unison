package fr.lulu.unison.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.lulu.unison.BuildConfig
import fr.lulu.unison.UnisonApp
import fr.lulu.unison.data.Profile
import fr.lulu.unison.library.DownloadRow
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.library.PlaylistSummary
import fr.lulu.unison.offline.DownloadStatus
import fr.lulu.unison.library.SavedRow
import fr.lulu.unison.lyrics.Lyrics
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.youtube.ArtistPage
import fr.lulu.unison.youtube.CollectionPage
import fr.lulu.unison.youtube.Quality
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.YtItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Chargement d'une page du catalogue. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

sealed interface LyricsUi {
    data object Idle : LyricsUi
    data class Loading(val trackId: String) : LyricsUi
    data class Ready(val trackId: String, val lyrics: Lyrics) : LyricsUi
    data class Missing(val trackId: String) : LyricsUi
}

/** Ce qui se passe côté « écouter ensemble ». */
sealed interface Busy {
    data object Idle : Busy
    data object Opening : Busy
    data class Joining(val hostName: String) : Busy
    data class Failed(val message: String) : Busy
}

data class SearchUi(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.SONGS,
    /** Vrai une fois la recherche lancée : on montre les résultats, plus les suggestions. */
    val submitted: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val results: Load<List<YtItem>>? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as UnisonApp).graph
    private val sessions = graph.sessions
    private val catalog = graph.catalog
    private val library = graph.library
    val updates = graph.updates

    val snapshot = sessions.snapshot
    val joinInfo = sessions.joinInfo
    val linkStatus = sessions.status
    val listenHere = sessions.listenHere
    val player = graph.playback.state
    val settings = graph.settings
    val updateState = updates.state
    val isHost: Boolean get() = sessions.isHost
    val myId: String get() = sessions.active.value.me.id

    val profile: StateFlow<Profile?> = graph.prefs.profile.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favorites: StateFlow<List<Track>> = library.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favoriteIds: StateFlow<Set<String>> = library.favoriteIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val history: StateFlow<List<Track>> = library.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists: StateFlow<List<PlaylistSummary>> = library.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val saved: StateFlow<List<SavedRow>> = library.saved.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // --- Hors ligne ---

    private val downloads = graph.downloads
    /** État de chaque titre téléchargé ou en cours (repère sur les lignes de titre). */
    val downloadStatus: StateFlow<Map<String, DownloadStatus>> = downloads.status
    val downloadRows: StateFlow<List<DownloadRow>> = downloads.rows.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloadsPaused: StateFlow<Boolean> = downloads.paused
    val waitingForWifi: StateFlow<Boolean> = downloads.waitingForWifi
    val online: StateFlow<Boolean> = graph.connectivity.online

    /** Paroles du titre en cours, cherchées dès qu'il change. */
    val lyrics: StateFlow<LyricsUi> = snapshot.map { it.anchor.item?.track }
        .distinctUntilChangedBy { it?.id }
        .transformLatest { track ->
            if (track == null) { emit(LyricsUi.Idle); return@transformLatest }
            emit(LyricsUi.Loading(track.id))
            val found = runCatching { graph.lyrics.lyricsFor(track) }.getOrNull()
            emit(if (found != null) LyricsUi.Ready(track.id, found) else LyricsUi.Missing(track.id))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsUi.Idle)

    private val _message = MutableStateFlow<String?>(null)
    /** Message bref affiché en bas de l'écran. */
    val message: StateFlow<String?> = _message.asStateFlow()
    private var messageJob: Job? = null

    fun toast(text: String) {
        messageJob?.cancel()
        _message.value = text
        messageJob = viewModelScope.launch { delay(2_600); _message.value = null }
    }

    fun onResume() = updates.resumeAfterPermission()

    // --- Lecture ---

    fun positionMs(): Long = graph.playback.positionMs()
    fun durationMs(): Long = graph.playback.durationMs()
    fun send(cmd: Cmd) = sessions.send(cmd)
    fun togglePlay() = send(if (snapshot.value.anchor.playing) Cmd.Pause else Cmd.Resume)

    /**
     * Toucher un titre : seul, on le lance tout de suite (avec ses voisins de liste) ;
     * à plusieurs, on l'ajoute à la file commune plutôt que de couper ce que tout le monde écoute.
     */
    fun play(tracks: List<Track>, index: Int = 0) {
        val track = tracks.getOrNull(index) ?: return
        if (snapshot.value.shared && snapshot.value.anchor.item != null) {
            send(Cmd.AddTrack(track)); toast("Ajouté à la file")
        } else send(Cmd.PlayTracks(tracks, index))
    }

    /** « Tout lire » : remplace la file, même à plusieurs (c'est une action explicite). */
    fun playAll(tracks: List<Track>, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        send(Cmd.PlayTracks(if (shuffle) tracks.shuffled() else tracks, 0))
    }

    fun playNext(track: Track) { send(Cmd.AddTrack(track, playNext = true)); toast("Sera lu ensuite") }
    fun addToQueue(track: Track) { send(Cmd.AddTrack(track)); toast("Ajouté à la file") }
    fun addAllToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        send(Cmd.AddTracks(tracks)); toast("${tracks.size} titres ajoutés à la file")
    }

    /** Lance le titre seul et laisse la radio automatique prolonger la file. */
    fun startRadio(track: Track) {
        send(Cmd.SetAutoplay(true))
        send(Cmd.PlayTracks(listOf(track), 0))
    }

    // --- Bibliothèque ---

    fun toggleFavorite(track: Track) = viewModelScope.launch {
        val on = track.id !in favoriteIds.value
        library.setFavorite(track, on)
        toast(if (on) "Ajouté aux favoris" else "Retiré des favoris")
    }

    fun createPlaylist(name: String, with: List<Track> = emptyList()) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val pid = library.createPlaylist(name)
        if (with.isNotEmpty()) library.addToPlaylist(pid, with)
        toast("Playlist « ${name.trim()} » créée")
    }

    fun addToPlaylist(playlist: PlaylistSummary, tracks: List<Track>) = viewModelScope.launch {
        val added = library.addToPlaylist(playlist.pid, tracks)
        toast(if (added == 0) "Déjà dans « ${playlist.name} »" else "Ajouté à « ${playlist.name} »")
    }

    fun playlistTracks(pid: Long) = library.playlistTracks(pid)
    fun playlistRow(pid: Long) = library.playlist(pid)
    fun removeFromPlaylist(rowId: Long) = viewModelScope.launch { library.removeFromPlaylist(rowId) }
    fun renamePlaylist(pid: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) library.renamePlaylist(pid, name) }
    fun deletePlaylist(pid: Long) = viewModelScope.launch { library.deletePlaylist(pid) }
    fun clearHistory() = viewModelScope.launch { library.clearHistory() }

    /** Garde ces titres sur le téléphone pour les écouter sans réseau. */
    fun download(tracks: List<Track>) {
        val missing = tracks.filter { downloadStatus.value[it.id] == null || downloadStatus.value[it.id]?.state == DownloadState.FAILED }
        if (missing.isEmpty()) { toast(if (tracks.size == 1) "Déjà téléchargé" else "Tout est déjà téléchargé"); return }
        downloads.download(missing)
        toast(if (missing.size == 1) "Téléchargement lancé" else "${missing.size} titres à télécharger")
    }

    fun removeDownload(id: String) { downloads.remove(id); toast("Téléchargement supprimé") }
    fun removeDownloads(ids: List<String>) { ids.forEach(downloads::remove); toast("Téléchargements supprimés") }
    fun pauseDownload(id: String) = downloads.pause(id)
    fun resumeDownload(id: String) = downloads.resume(id)
    fun pauseDownloads() = downloads.pauseAll()
    fun resumeDownloads() = downloads.resumeAll()
    fun downloadSpace(): Pair<Long, Long> = downloads.spaceBytes() to downloads.freeBytes()
    fun setDownloadWifiOnly(on: Boolean) = viewModelScope.launch { graph.prefs.setDownloadWifiOnly(on) }

    fun toggleSaved(browseId: String, page: CollectionPage) = viewModelScope.launch {
        val on = saved.value.none { it.browseId == browseId }
        library.setSaved(SavedRow(browseId, page.title, page.artist.ifBlank { page.subtitle }, page.thumbnail, page.isAlbum, 0), on)
        toast(if (on) "Ajouté à la bibliothèque" else "Retiré de la bibliothèque")
    }

    // --- Catalogue ---

    private val _home = MutableStateFlow<Load<List<Section>>>(Load.Loading)
    val home: StateFlow<Load<List<Section>>> = _home.asStateFlow()

    fun loadHome() {
        _home.value = Load.Loading
        viewModelScope.launch {
            _home.value = runCatching { catalog.home() }.fold({ Load.Ready(it) }, { Load.Failed(OFFLINE) })
        }
    }

    private val collections = ConcurrentHashMap<String, CollectionPage>()
    private val artists = ConcurrentHashMap<String, ArtistPage>()
    private val browses = ConcurrentHashMap<String, List<Section>>()

    suspend fun collection(browseId: String): Load<CollectionPage> = cached(collections, browseId) { catalog.collection(browseId) }
    suspend fun artist(browseId: String): Load<ArtistPage> = cached(artists, browseId) { catalog.artist(browseId) }
    suspend fun browse(browseId: String, params: String?): Load<List<Section>> =
        cached(browses, "$browseId|$params") { catalog.browse(browseId, params) }

    private suspend fun <T : Any> cached(map: ConcurrentHashMap<String, T>, key: String, fetch: suspend () -> T): Load<T> {
        map[key]?.let { return Load.Ready(it) }
        return runCatching { fetch() }.fold({ map[key] = it; Load.Ready(it) }, { Load.Failed(OFFLINE) })
    }

    // --- Recherche ---

    private val _search = MutableStateFlow(SearchUi())
    val search: StateFlow<SearchUi> = _search.asStateFlow()
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _search.value = _search.value.copy(query = q, submitted = false)
        suggestJob?.cancel()
        if (q.isBlank()) { _search.value = SearchUi(filter = _search.value.filter); searchJob?.cancel(); return }
        suggestJob = viewModelScope.launch {
            delay(180)
            val s = runCatching { catalog.suggestions(q) }.getOrDefault(emptyList())
            if (_search.value.query == q) _search.value = _search.value.copy(suggestions = s)
        }
    }

    fun submitSearch(query: String = _search.value.query) {
        val q = query.trim()
        if (q.isEmpty()) return
        suggestJob?.cancel()
        _search.value = _search.value.copy(query = q, submitted = true, results = Load.Loading)
        runSearch()
    }

    fun setFilter(f: SearchFilter) {
        if (_search.value.filter == f) return
        _search.value = _search.value.copy(filter = f)
        if (_search.value.submitted) { _search.value = _search.value.copy(results = Load.Loading); runSearch() }
    }

    private fun runSearch() {
        searchJob?.cancel()
        val (q, f) = _search.value.let { it.query to it.filter }
        searchJob = viewModelScope.launch {
            val r: Load<List<YtItem>> = runCatching { catalog.search(q, f) }.fold({ Load.Ready(it) }, { Load.Failed(OFFLINE) })
            if (_search.value.query == q && _search.value.filter == f) _search.value = _search.value.copy(results = r)
        }
    }

    // --- Écouter ensemble ---

    private val _busy = MutableStateFlow<Busy>(Busy.Idle)
    val busy: StateFlow<Busy> = _busy.asStateFlow()

    fun share(kind: TransportKind) {
        if (_busy.value is Busy.Opening) return
        _busy.value = Busy.Opening
        viewModelScope.launch {
            _busy.value = runCatching { sessions.share(kind) }
                .fold({ Busy.Idle }, { Busy.Failed(it.message ?: "Impossible d'ouvrir la session") })
        }
    }

    fun join(info: JoinInfo) {
        if (_busy.value is Busy.Joining) return
        if (joinInfo.value?.sessionId == info.sessionId) return
        _busy.value = Busy.Joining(info.hostName)
        viewModelScope.launch {
            _busy.value = runCatching { sessions.join(info) }
                .fold({ toast("Tu écoutes avec ${info.hostName}"); Busy.Idle }, { Busy.Failed(it.message ?: "Impossible de rejoindre la session") })
        }
    }

    fun leave() = viewModelScope.launch { sessions.leave() }
    fun dismissBusy() { _busy.value = Busy.Idle }
    fun setListenHere(on: Boolean) = sessions.setListenHere(on)

    /** Lien reçu par un QR code, un lien `unison://` ou le partage d'une autre app. */
    fun handleIncoming(text: String): Boolean {
        when (val parsed = JoinInfo.inspect(text)) {
            is JoinInfo.Companion.Parsed.Ok -> return false // l'activité demande d'abord les autorisations
            JoinInfo.Companion.Parsed.Outdated -> { _busy.value = Busy.Failed("Cette session vient d'une ancienne version d'Unison. Mettez les deux téléphones à jour."); return true }
            JoinInfo.Companion.Parsed.Invalid -> Unit
        }
        val id = fr.lulu.unison.youtube.YtLinks.videoId(text) ?: return false
        viewModelScope.launch {
            val track = runCatching { catalog.track(id) }.getOrNull()
            if (track == null) toast("Lien non reconnu")
            else if (snapshot.value.anchor.item == null) send(Cmd.PlayTracks(listOf(track), 0))
            else addToQueue(track)
        }
        return true
    }

    // --- Réglages ---

    fun saveProfile(name: String, color: Int) = viewModelScope.launch { graph.prefs.saveProfile(name, color) }
    fun setQuality(q: Quality) = viewModelScope.launch { graph.prefs.setQuality(q) }
    fun setCacheMb(mb: Int) = viewModelScope.launch { graph.prefs.setCacheMb(mb) }
    fun setNormalize(on: Boolean) = viewModelScope.launch { graph.prefs.setNormalize(on) }
    fun setAnimatedBackground(on: Boolean) = viewModelScope.launch { graph.prefs.setAnimatedBackground(on) }

    // En dernier : tous les champs ci-dessus doivent exister avant le premier chargement.
    init {
        if (!BuildConfig.DEBUG) { updates.clean(); updates.check() }
        loadHome()
    }

    private companion object {
        const val OFFLINE = "Vérifie ta connexion, puis réessaie."
    }
}
