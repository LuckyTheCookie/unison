package fr.lulu.unison.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.lulu.unison.BuildConfig
import fr.lulu.unison.UnisonApp
import fr.lulu.unison.data.Profile
import fr.lulu.unison.importer.ImportEntry
import fr.lulu.unison.importer.ImportUi
import fr.lulu.unison.artists.ArtistFeed
import fr.lulu.unison.library.DownloadRow
import fr.lulu.unison.library.SubscriptionRow
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.library.PlaylistSummary
import fr.lulu.unison.offline.DownloadStatus
import fr.lulu.unison.library.SavedRow
import fr.lulu.unison.lyrics.Lyrics
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.source.ManifestSource
import fr.lulu.unison.source.SourceConfig
import fr.lulu.unison.source.SourceHealth
import fr.lulu.unison.source.SourceIds
import fr.lulu.unison.youtube.ArtistPage
import fr.lulu.unison.youtube.CollectionPage
import fr.lulu.unison.youtube.Quality
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.YtItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
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

/** Étapes de l'ajout d'une source importée. */
sealed interface AddSourceUi {
    data object Idle : AddSourceUi
    data class Entering(val url: String = "", val error: String? = null) : AddSourceUi
    data class Checking(val url: String) : AddSourceUi
    data class Confirm(val url: String, val inspection: ManifestSource.Companion.Inspection) : AddSourceUi
}

/** Ce qui se passe côté « écouter ensemble ». */
sealed interface Busy {
    data object Idle : Busy
    data object Opening : Busy
    data class Joining(val hostName: String) : Busy
    data class Failed(val message: String) : Busy
}

/** Ce que l'accueil montre de la session : écoute partagée, nombre de membres, nom de l'hôte. */
data class SessionSummary(val shared: Boolean, val members: Int, val hostName: String) {
    companion object {
        fun of(s: fr.lulu.unison.model.SessionSnapshot) = SessionSummary(s.shared, s.members.size, s.hostName)
    }
}

data class SearchUi(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.SONGS,
    /** Vrai une fois la recherche lancée : on montre les résultats, plus les suggestions. */
    val submitted: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val results: Load<List<YtItem>>? = null,
    /** Recherche dans la source importée choisie plutôt que dans YouTube Music. */
    val inSource: Boolean = false,
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
    val driftMs = graph.playback.driftMs

    // Morceaux du snapshot de session, pour que chaque écran ne se recompose qu'à ce qui le concerne
    // (le snapshot entier change à chaque mouvement de file, d'ancre ou de membre).
    /** Titre en cours, ou null quand rien ne joue. */
    val currentItem: StateFlow<QueueItem?> = snapshot.map { it.anchor.item }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, snapshot.value.anchor.item)
    val playing: StateFlow<Boolean> = snapshot.map { it.anchor.playing }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, snapshot.value.anchor.playing)
    val sessionSummary: StateFlow<SessionSummary> = snapshot.map { SessionSummary(it.shared, it.members.size, it.hostName) }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, SessionSummary.of(snapshot.value))
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

    /**
     * « Tout lire » : remplace la file, même à plusieurs (c'est une action explicite).
     * [shuffle] : bouton « Aléatoire » ; l'aléatoire s'active, l'ordre de la liste reste mémorisé.
     */
    fun playAll(tracks: List<Track>, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        send(Cmd.PlayTracks(tracks, 0, shuffle = shuffle))
    }

    fun toggleShuffle() {
        val on = !snapshot.value.shuffle
        send(Cmd.SetShuffle(on))
        toast(if (on) "Aléatoire activé" else "Aléatoire désactivé")
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

    // --- Artistes suivis ---

    private val subscriptions = graph.subscriptions
    val subscribedIds: StateFlow<Set<String>> = subscriptions.ids
    val subscribed: StateFlow<List<SubscriptionRow>> = subscriptions.list.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _artistFeed = MutableStateFlow<ArtistFeed?>(null)
    /** Section « Tes artistes » de l'accueil ; null tant qu'elle n'est pas chargée (ou sans abonnement). */
    val artistFeed: StateFlow<ArtistFeed?> = _artistFeed.asStateFlow()

    fun toggleSubscription(browseId: String, name: String, thumbnail: String) = viewModelScope.launch {
        if (browseId in subscribedIds.value) {
            subscriptions.unsubscribe(browseId); toast("Tu ne suis plus $name")
        } else {
            subscriptions.subscribe(browseId, name, thumbnail); toast("Tu suis $name")
        }
    }

    private fun loadArtistFeed() = viewModelScope.launch {
        _artistFeed.value = runCatching { subscriptions.feed() }.getOrNull()?.takeIf { it.artists.isNotEmpty() }
    }

    fun setPreferMyArtists(on: Boolean) = viewModelScope.launch { graph.prefs.setPreferMyArtists(on) }

    // --- Import ---

    private val importer = graph.importer
    val importState: StateFlow<ImportUi> = importer.state
    fun openImport(uri: android.net.Uri) = importer.open(uri)
    fun startImport() = importer.start()
    fun cancelImport() = importer.cancel()
    fun dismissImport() = importer.dismiss()
    suspend fun importCandidates(entry: ImportEntry.Song, query: String? = null): List<Track>? = importer.candidates(entry, query)
    fun resolveImport(entry: ImportEntry.Song, track: Track) { importer.resolveManually(entry, track); toast("Ajouté") }

    fun toggleSaved(browseId: String, page: CollectionPage) = viewModelScope.launch {
        val on = saved.value.none { it.browseId == browseId }
        library.setSaved(SavedRow(browseId, page.title, page.artist.ifBlank { page.subtitle }, page.thumbnail, page.isAlbum, 0), on)
        toast(if (on) "Ajouté à la bibliothèque" else "Retiré de la bibliothèque")
    }

    // --- Catalogue ---

    private val _home = MutableStateFlow<Load<List<Section>>>(Load.Loading)
    val home: StateFlow<Load<List<Section>>> = _home.asStateFlow()

    fun loadHome() {
        // « Réessayer » recharge aussi « Tes artistes », resté vide si l'app a démarré sans réseau.
        loadArtistFeed()
        viewModelScope.launch {
            // Le dernier accueil reçu s'affiche tout de suite ; le nouveau le remplace dès qu'il arrive.
            if (_home.value !is Load.Ready) _home.value = graph.homeCache.read()?.let { Load.Ready(it) } ?: Load.Loading
            runCatching { catalog.home() }.fold(
                { sections -> _home.value = Load.Ready(sections); graph.homeCache.write(sections) },
                // Sans réseau, on garde l'accueil déjà affiché plutôt qu'un message d'erreur.
                { if (_home.value !is Load.Ready) _home.value = Load.Failed(OFFLINE) },
            )
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
        if (_search.value.filter == f && !_search.value.inSource) return
        _search.value = _search.value.copy(filter = f, inSource = false)
        if (_search.value.submitted) { _search.value = _search.value.copy(results = Load.Loading); runSearch() }
    }

    /** Cherche dans la source importée choisie (titres seulement). */
    fun searchInSource() {
        if (_search.value.inSource) return
        _search.value = _search.value.copy(inSource = true)
        if (_search.value.submitted) { _search.value = _search.value.copy(results = Load.Loading); runSearch() }
    }

    /** Source importée choisie dans les réglages, s'il y en a une. */
    val activeSource: StateFlow<SourceConfig?> = kotlinx.coroutines.flow.combine(settings, graph.sources.sources) { s, list ->
        list.firstOrNull { it.id == s.activeSource }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun runSearch() {
        searchJob?.cancel()
        val (q, f) = _search.value.let { it.query to it.filter }
        val source = activeSource.value?.takeIf { _search.value.inSource }
        if (source == null && _search.value.inSource) _search.value = _search.value.copy(inSource = false)
        searchJob = viewModelScope.launch {
            val r: Load<List<YtItem>> = if (source != null) {
                runCatching { graph.resolver.searchIn(source.id, q).map { SongItem(it) } }
                    .fold({ Load.Ready(it) }, { Load.Failed("La source « ${source.name} » ne répond pas. Réessaie plus tard.") })
            } else runCatching { catalog.search(q, f) }.fold({ Load.Ready(it) }, { Load.Failed(OFFLINE) })
            val now = _search.value
            if (now.query == q && now.filter == f && now.inSource == (source != null)) _search.value = now.copy(results = r)
        }
    }

    fun setFallbackToYouTube(on: Boolean) = viewModelScope.launch { graph.prefs.setFallbackToYouTube(on) }

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
    fun setBassBoost(on: Boolean) = viewModelScope.launch { graph.prefs.setBassBoost(on) }
    fun setBassLevel(level: Int) = viewModelScope.launch { graph.prefs.setBassLevel(level) }
    fun setReduceMotion(on: Boolean) = viewModelScope.launch { graph.prefs.setReduceMotion(on) }

    // --- Sources audio ---

    /** Sources importées (aucune par défaut). */
    val sources: StateFlow<List<SourceConfig>> = graph.sources.sources

    /** Choisit d'où vient le son : [SourceIds.YOUTUBE] ou une source importée. */
    fun setActiveSource(id: String) = viewModelScope.launch {
        if (id != SourceIds.YOUTUBE && graph.sources.get(id) == null) return@launch
        graph.prefs.setActiveSource(id)
    }

    /** Retire la source ; si c'était celle choisie, YouTube Music reprend la main. */
    fun removeSource(id: String) = viewModelScope.launch {
        val name = graph.sources.get(id)?.name ?: return@launch
        if (settings.value.activeSource == id) graph.prefs.setActiveSource(SourceIds.YOUTUBE)
        graph.sourceHub.remove(id)
        _sourceHealth.value -= id
        toast("Source « $name » retirée")
    }

    fun setSourceQuality(id: String, value: String) = graph.sourceHub.setQuality(id, value)

    private val _sourceHealth = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    /** Dernier test de santé de chaque source importée. */
    val sourceHealth: StateFlow<Map<String, SourceHealth>> = _sourceHealth.asStateFlow()

    /** Teste toutes les sources importées (à l'ouverture des réglages). */
    fun refreshSourceHealth() = sources.value.forEach { s ->
        viewModelScope.launch { _sourceHealth.value += s.id to graph.sourceHub.health(s.id) }
    }

    private val _addSource = MutableStateFlow<AddSourceUi>(AddSourceUi.Idle)
    /** Ajout d'une source : saisie de l'adresse, vérification, puis confirmation. */
    val addSource: StateFlow<AddSourceUi> = _addSource.asStateFlow()
    private var inspectJob: Job? = null

    fun startAddSource() { _addSource.value = AddSourceUi.Entering() }

    /** Lit le manifest à [url] et fait le test de santé ; rien n'est enregistré avant confirmation. */
    fun inspectSource(url: String) {
        inspectJob?.cancel()
        _addSource.value = AddSourceUi.Checking(url)
        inspectJob = viewModelScope.launch {
            _addSource.value = when (val r = graph.sourceHub.inspect(url)) {
                is ManifestSource.Companion.InspectResult.Ready -> AddSourceUi.Confirm(url, r.inspection)
                is ManifestSource.Companion.InspectResult.Refused -> AddSourceUi.Entering(url, r.reason)
            }
        }
    }

    /** Enregistre la source vérifiée et la choisit comme source des flux. */
    fun confirmSource() {
        val c = (_addSource.value as? AddSourceUi.Confirm)?.inspection ?: return
        if (c.health != SourceHealth.Ok) return
        graph.sourceHub.add(c.config)
        _sourceHealth.value += c.config.id to c.health
        viewModelScope.launch { graph.prefs.setActiveSource(c.config.id) }
        _addSource.value = AddSourceUi.Idle
        toast("Source « ${c.config.name} » ajoutée")
    }

    fun dismissAddSource() { inspectJob?.cancel(); _addSource.value = AddSourceUi.Idle }

    // En dernier : tous les champs ci-dessus doivent exister avant le premier chargement.
    init {
        if (!BuildConfig.DEBUG) { updates.clean(); updates.check() }
        loadHome()
        // Le fil des artistes suivis se recharge quand la liste change (abonnement, import).
        viewModelScope.launch {
            subscriptions.ids.drop(1).collectLatest { delay(800); loadArtistFeed() }
        }
    }

    private companion object {
        const val OFFLINE = "Vérifie ta connexion, puis réessaie."
    }
}
