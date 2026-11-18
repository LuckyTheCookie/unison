package fr.lulu.unison.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import fr.lulu.unison.library.Library
import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.MusicCatalog
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Résultat de la recherche d'un titre importé. */
sealed interface MatchResult {
    data class Found(val track: Track) : MatchResult
    data object NotFound : MatchResult
    /** Recherche impossible (réseau) : à retenter, jamais mémorisé comme « introuvable ». */
    data object Failed : MatchResult
}

/**
 * Retrouve sur YouTube Music les titres d'un import, quelques recherches à la fois, une seule fois par
 * couple titre/artiste. Indépendant d'Android, pour pouvoir le tester.
 */
class ImportMatcher(
    private val catalog: MusicCatalog,
    private val parallelism: Int = 3,
    private val retryDelayMs: Long = 2_000,
) {
    suspend fun find(entry: ImportEntry.Song): MatchResult {
        // Après beaucoup de recherches, YouTube peut refuser quelques requêtes : on patiente un peu avant d'abandonner.
        for (attempt in 0..RETRIES) {
            try {
                val first = songs(entry.query)
                ImportMatch.best(entry, first)?.let { return MatchResult.Found(it) }
                // Deuxième essai avec le titre seul : l'artiste du fichier est parfois écrit autrement.
                if (entry.query != entry.title) ImportMatch.best(entry, songs(entry.title))?.let { return MatchResult.Found(it) }
                return MatchResult.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recherche impossible (${e::class.simpleName}), essai ${attempt + 1}")
                if (attempt < RETRIES) delay(retryDelayMs * (attempt + 1))
            }
        }
        return MatchResult.Failed
    }

    private suspend fun songs(query: String): List<Track> =
        catalog.search(query, SearchFilter.SONGS).filterIsInstance<SongItem>().map { it.track }.take(10)

    /**
     * Cherche chaque titre de [entries] absent de [known] ; [onResult] est appelé à chaque réponse
     * (clé, résultat). Renvoie tous les résultats, connus compris.
     */
    suspend fun matchAll(
        entries: List<ImportEntry.Song>,
        known: Map<String, Track?>,
        onResult: suspend (String, MatchResult) -> Unit,
    ): Map<String, MatchResult> = coroutineScope {
        val out = HashMap<String, MatchResult>()
        known.forEach { (k, t) -> out[k] = if (t != null) MatchResult.Found(t) else MatchResult.NotFound }
        val todo = entries.distinctBy { it.key }.filter { it.key !in known }
        val gate = Semaphore(parallelism)
        val lock = Mutex()
        todo.map { e ->
            async {
                val r = gate.withPermit { find(e) }
                lock.withLock { out[e.key] = r; onResult(e.key, r) }
            }
        }.awaitAll()
        out
    }

    private companion object {
        const val TAG = "UnisonImport"
        const val RETRIES = 2
    }
}

/** Bilan d'un import. */
data class ImportReport(
    val favorites: Int,
    val playlists: Map<String, Int>,
    val notFound: List<ImportEntry.Song>,
    /** Lignes sans titre, types non pris en charge, doublons. */
    val ignored: Int,
    /** Titres dont la recherche a échoué faute de réseau : un nouvel essai les reprendra. */
    val failed: Int,
    /** Artistes suivis grâce à l'import. */
    val artists: Int = 0,
    val artistsNotFound: List<String> = emptyList(),
)

sealed interface ImportUi {
    data object Idle : ImportUi
    data object Reading : ImportUi
    data class Ready(val name: String, val plan: ImportPlan) : ImportUi
    data class Running(val name: String, val done: Int, val total: Int, val found: Int) : ImportUi
    data class Finished(val name: String, val report: ImportReport) : ImportUi
    /** Un import a été interrompu (app fermée) : on peut le reprendre là où il en était. */
    data class Interrupted(val name: String) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/**
 * Import d'une bibliothèque exportée d'un autre service (fichier CSV) : favoris, playlists et artistes.
 * Les correspondances trouvées sont gardées sur le téléphone au fil de l'eau : un import interrompu
 * reprend sans refaire les recherches déjà faites. Écrire deux fois le même import ne crée pas de doublon.
 */
class Importer(
    private val context: Context,
    private val library: Library,
    private val catalog: MusicCatalog,
    private val scope: CoroutineScope,
    /** Abonnement aux artistes de l'import (branché par les abonnements). */
    private val followArtists: suspend (List<ImportEntry.Artist>) -> Pair<Int, List<String>> = { 0 to it.map { a -> a.name } },
) {
    private val matcher = ImportMatcher(catalog)
    private val dir = File(context.filesDir, "import")
    private val pendingCsv = File(dir, "pending.csv")
    private val pendingName = File(dir, "pending.name")
    private val matchesFile = File(dir, "matches.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val matchesSerializer = MapSerializer(String.serializer(), Track.serializer().nullable)

    private val _state = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val state: StateFlow<ImportUi> = _state.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            if (pendingCsv.exists() && _state.value == ImportUi.Idle) _state.value = ImportUi.Interrupted(pendingName.readTextOrNull() ?: "Import")
        }
    }

    /** Lit le fichier choisi et montre ce qu'il contient, sans rien importer encore. */
    fun open(uri: Uri) {
        if (job?.isActive == true) return
        _state.value = ImportUi.Reading
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) } ?: error("illisible")
                    if (bytes.size > MAX_BYTES) error("trop gros")
                    val text = bytes.toString(Charsets.UTF_8)
                    Triple(name, text, LibraryCsv.plan(text, fallbackPlaylist = name))
                }
            }
            result.fold(
                onSuccess = { (name, text, plan) ->
                    if (plan.isEmpty) _state.value = ImportUi.Failed("Ce fichier ne ressemble pas à un export de bibliothèque (colonnes de titre et d'artiste introuvables).")
                    else {
                        withContext(Dispatchers.IO) { dir.mkdirs(); pendingCsv.writeText(text); pendingName.writeText(name); matchesFile.delete() }
                        _state.value = ImportUi.Ready(name, plan)
                    }
                },
                onFailure = { _state.value = ImportUi.Failed("Impossible de lire ce fichier.") },
            )
        }
    }

    /** Lance l'import préparé (ou reprend celui qui a été interrompu). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val text = pendingCsv.readTextOrNull() ?: return@withContext null
                val name = pendingName.readTextOrNull() ?: "Import"
                Triple(name, LibraryCsv.plan(text, fallbackPlaylist = name), readMatches())
            } ?: run { _state.value = ImportUi.Idle; return@launch }
            val (name, plan, known) = loaded
            run(name, plan, known)
        }
    }

    fun cancel() {
        job?.cancel()
        scope.launch(Dispatchers.IO) { pendingCsv.delete(); pendingName.delete(); matchesFile.delete() }
        _state.value = ImportUi.Idle
    }

    /** Ferme le bilan. */
    fun dismiss() {
        if (job?.isActive == true) return
        _state.value = ImportUi.Idle
    }

    private suspend fun run(name: String, plan: ImportPlan, known: MutableMap<String, Track?>) {
        val total = plan.songs.distinctBy { it.key }.size
        var found = known.values.count { it != null }
        var done = known.size
        _state.value = ImportUi.Running(name, done, total, found)
        var unsaved = 0
        val results = matcher.matchAll(plan.songs, known) { key, r ->
            done++
            when (r) {
                is MatchResult.Found -> { found++; known[key] = r.track }
                MatchResult.NotFound -> known[key] = null
                MatchResult.Failed -> Unit
            }
            _state.value = ImportUi.Running(name, done, total, found)
            // Sauvegarde régulière : en cas d'interruption, la reprise saute ce qui est déjà trouvé.
            if (++unsaved >= 20) { unsaved = 0; withContext(Dispatchers.IO) { writeMatches(known) } }
        }
        withContext(Dispatchers.IO) { writeMatches(known) }

        // Écriture dans la bibliothèque, dans l'ordre du fichier.
        val report = withContext(Dispatchers.IO) {
            var ignored = plan.skipped
            val favorites = mutableListOf<Track>()
            val playlists = LinkedHashMap<String, MutableList<Track>>()
            val notFound = mutableListOf<ImportEntry.Song>()
            var failed = 0
            for (song in plan.songs) {
                when (val r = results[song.key]) {
                    is MatchResult.Found -> {
                        val list = when (val t = song.target) {
                            ImportTarget.Favorites -> favorites
                            is ImportTarget.Playlist -> playlists.getOrPut(t.name) { mutableListOf() }
                        }
                        if (list.any { it.id == r.track.id }) ignored++ else list += r.track
                    }
                    MatchResult.Failed, null -> { failed++; notFound += song }
                    MatchResult.NotFound -> notFound += song
                }
            }
            library.importFavorites(favorites)
            playlists.forEach { (playlist, tracks) -> library.importPlaylist(playlist, tracks) }
            val counts = playlists.mapValues { it.value.size }
            ImportReport(favorites.size, counts, notFound, ignored, failed)
        }
        val (followed, missingArtists) = runCatching { followArtists(plan.artists) }.getOrElse { 0 to plan.artists.map { it.name } }
        // Tout s'est bien passé : rien à reprendre (sauf si des recherches ont échoué faute de réseau).
        if (report.failed == 0) withContext(Dispatchers.IO) { pendingCsv.delete(); pendingName.delete(); matchesFile.delete() }
        _state.value = ImportUi.Finished(name, report.copy(artists = followed, artistsNotFound = missingArtists))
    }

    /** Recherche manuelle d'un titre non trouvé : le titre choisi rejoint sa destination. */
    fun resolveManually(entry: ImportEntry.Song, track: Track) {
        scope.launch {
            withContext(Dispatchers.IO) {
                when (val t = entry.target) {
                    ImportTarget.Favorites -> library.importFavorites(listOf(track))
                    is ImportTarget.Playlist -> library.importPlaylist(t.name, listOf(track))
                }
            }
            _state.update { s ->
                if (s !is ImportUi.Finished) s
                else s.copy(report = s.report.copy(notFound = s.report.notFound.filterNot { it.key == entry.key && it.target == entry.target }))
            }
        }
    }

    /** Résultats de recherche proposés pour une correspondance manuelle. */
    suspend fun candidates(entry: ImportEntry.Song): List<Track> = runCatching {
        matcherSearch(entry.query).ifEmpty { matcherSearch(entry.title) }
    }.getOrDefault(emptyList())

    private suspend fun matcherSearch(q: String) =
        catalog.search(q, SearchFilter.SONGS).filterIsInstance<SongItem>().map { it.track }.take(12)

    private fun readMatches(): MutableMap<String, Track?> =
        runCatching { json.decodeFromString(matchesSerializer, matchesFile.readText()).toMutableMap() }.getOrElse { HashMap() }

    private fun writeMatches(m: Map<String, Track?>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "matches.json.tmp")
            tmp.writeText(json.encodeToString(matchesSerializer, m))
            tmp.renameTo(matchesFile)
        }
    }

    private fun displayName(uri: Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Import"
        return raw.substringBeforeLast('.').ifBlank { "Import" }.take(60)
    }

    private fun File.readTextOrNull() = runCatching { if (exists()) readText() else null }.getOrNull()

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
