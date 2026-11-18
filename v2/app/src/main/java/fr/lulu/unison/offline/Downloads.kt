package fr.lulu.unison.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import fr.lulu.unison.data.Prefs
import fr.lulu.unison.library.DownloadRow
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.library.Library
import fr.lulu.unison.lyrics.LyricsRepository
import fr.lulu.unison.model.Track
import fr.lulu.unison.source.StreamResolver
import fr.lulu.unison.source.TrackUri
import fr.lulu.unison.source.toRequest
import fr.lulu.unison.youtube.ChunkedDataSource
import fr.lulu.unison.youtube.YtParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** État d'un titre téléchargé, tel que l'affichent les listes. */
data class DownloadStatus(val state: Int, val progress: Float) {
    val done: Boolean get() = state == DownloadState.DONE

    companion object {
        /** État d'une ligne de la base, avec l'avancement [live] du téléchargement en cours s'il est connu. */
        fun of(row: DownloadRow, live: Float?): DownloadStatus {
            val p = when {
                row.state == DownloadState.DONE -> 1f
                live != null -> live
                row.totalBytes > 0 -> row.bytes.toFloat() / row.totalBytes
                else -> 0f
            }
            return DownloadStatus(row.state, p.coerceIn(0f, 1f))
        }
    }
}

/**
 * Pochettes des titres téléchargés, enregistrées sur le téléphone : les listes et le fond animé
 * les affichent même sans réseau.
 */
object OfflineArt {
    private val files = ConcurrentHashMap<String, File>()

    /** Fichier local de la pochette [url], s'il existe. */
    operator fun get(url: String?): File? = url?.let { files[it] }?.takeIf { it.exists() }

    internal fun put(url: String, file: File) { files[url] = file }
    internal fun remove(url: String) { files.remove(url) }
}

/**
 * Téléchargements pour l'écoute hors ligne, confiés au gestionnaire de Media3 (reprise, file d'attente,
 * Wi-Fi uniquement). L'audio va dans un cache permanent, distinct du cache de lecture qui s'efface tout seul :
 * un titre complet se relit sans jamais redemander YouTube. Paroles et pochette sont gardées à côté.
 */
class Downloads(
    private val context: Context,
    private val http: OkHttpClient,
    private val streams: StreamResolver,
    private val library: Library,
    private val lyrics: LyricsRepository,
    private val prefs: Prefs,
    databaseProvider: DatabaseProvider,
    private val scope: CoroutineScope,
) {
    private val root = File(context.filesDir, "offline")
    private val coversDir = File(root, "covers")

    /** Cache permanent : rien n'en sort sans qu'on le demande. */
    val cache = SimpleCache(File(root, "audio"), NoOpCacheEvictor(), databaseProvider)

    private val completed = ConcurrentHashMap.newKeySet<String>()
    private val loudness = ConcurrentHashMap<String, Double>()
    private val loaded = CompletableDeferred<Unit>()
    private val retried = HashSet<String>()

    val manager: DownloadManager = DownloadManager(
        context, databaseProvider, cache,
        // L'adresse du flux est demandée au moment de télécharger ; la clé de cache reste celle du titre.
        // Requêtes bornées : sans fin annoncée, YouTube bride le téléchargement au débit de lecture.
        ResolvingDataSource.Factory(ChunkedDataSource.Factory(OkHttpDataSource.Factory(http))) { spec ->
            if (spec.uri.scheme != TrackUri.SCHEME) return@Factory spec
            val ref = TrackUri.parse(spec.uri.toString()) ?: return@Factory spec
            val id = ref.id
            val quality = runBlocking { prefs.settings.first() }.quality
            val stream = runBlocking { streams.resolve(ref, quality.toRequest(), StreamResolver.Purpose.DOWNLOAD) }
            stream.loudnessDb?.let { loudness[id] = it }
            scope.launch(Dispatchers.IO) { library.setDownloadFormat(id, stream.itag, stream.loudnessDb) }
            spec.buildUpon().setUri(Uri.parse(stream.url)).setHttpRequestHeaders(stream.headers).build()
        },
        Executors.newFixedThreadPool(2),
    ).apply { maxParallelDownloads = 2; minRetryCount = 3 }

    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _paused = MutableStateFlow(false)

    /** Tous les téléchargements sont en pause. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Le Wi-Fi est exigé et absent : les téléchargements attendent. */
    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    val rows: Flow<List<DownloadRow>> = library.downloads

    /** État de chaque titre téléchargé ou en cours, par identifiant. */
    val status: StateFlow<Map<String, DownloadStatus>> = combine(library.downloads, progress) { rows, live ->
        rows.associate { r -> r.track.trackId to DownloadStatus.of(r, live[r.track.trackId]) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun start() {
        scope.launch(Dispatchers.IO) {
            coversDir.mkdirs()
            for (row in library.allDownloads()) {
                if (row.state == DownloadState.DONE) completed += row.track.trackId
                row.loudnessDb?.let { loudness[row.track.trackId] = it }
                val art = coverFile(row.track.trackId)
                if (row.track.cover.isNotBlank() && art.exists()) OfflineArt.put(row.track.cover, art)
            }
            loaded.complete(Unit)
        }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) = changed(download)
            override fun onDownloadRemoved(manager: DownloadManager, download: Download) = removed(download.request.id)
            override fun onDownloadsPausedChanged(manager: DownloadManager, downloadsPaused: Boolean) { _paused.value = downloadsPaused }
            override fun onRequirementsStateChanged(manager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
                _waitingForWifi.value = notMetRequirements != 0
            }
        })
        _paused.value = manager.downloadsPaused
        scope.launch {
            prefs.settings.map { it.downloadWifiOnly }.distinctUntilChanged().collect { wifi ->
                // Le gestionnaire est dans ce processus : inutile de démarrer le service pour ça.
                manager.requirements = Requirements(if (wifi) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
                _waitingForWifi.value = manager.notMetRequirements != 0
            }
        }
        // Avancement des téléchargements en cours (le gestionnaire ne le signale pas de lui-même).
        scope.launch {
            while (isActive) {
                delay(600)
                val live = manager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                    .associate { it.request.id to (it.percentDownloaded.takeIf { p -> p >= 0 } ?: 0f) / 100f }
                if (live != progress.value) progress.value = live
            }
        }
    }

    /** Le titre est entièrement sur le téléphone : la lecture n'a pas besoin du réseau. */
    fun isComplete(id: String): Boolean {
        if (!loaded.isCompleted) runBlocking { loaded.await() }
        return id in completed
    }

    fun loudnessOf(id: String): Double? = loudness[id]

    /** Télécharge ces titres (ceux déjà présents sont ignorés, ceux en échec sont relancés). */
    fun download(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { library.addDownloads(tracks) }
            tracks.distinctBy { it.id }.filter { it.id !in completed }.forEach { t ->
                retried -= t.id
                send { DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, request(t), false) }
            }
        }
    }

    fun remove(id: String) = send { DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, id, false) }

    fun pause(id: String) = send { DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, id, STOP_USER, false) }

    fun resume(id: String) = send { DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, id, Download.STOP_REASON_NONE, false) }

    fun pauseAll() = send { DownloadService.sendPauseDownloads(context, OfflineDownloadService::class.java, false) }

    fun resumeAll() = send { DownloadService.sendResumeDownloads(context, OfflineDownloadService::class.java, false) }

    /** Place occupée par l'audio téléchargé, en octets. */
    fun spaceBytes(): Long = cache.cacheSpace + (coversDir.listFiles()?.sumOf { it.length() } ?: 0L)

    /** Place encore libre sur le téléphone, en octets. */
    fun freeBytes(): Long = context.filesDir.usableSpace

    private fun send(action: () -> Unit) {
        runCatching(action).onFailure { Log.w(TAG, "service de téléchargement", it) }
    }

    private fun request(t: Track) = DownloadRequest.Builder(t.id, Uri.parse(TrackUri.build(t)))
        .setCustomCacheKey(cacheKey(t.id))
        .build()

    private fun changed(d: Download) {
        val id = d.request.id
        val state = stateOf(d.state, d.stopReason) ?: return
        if (state == DownloadState.FAILED && retried.add(id)) {
            // Souvent une adresse de flux périmée : on la redemande une fois avant d'abandonner.
            streams.invalidate(id)
            send { DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, d.request, false) }
        }
        if (state == DownloadState.DONE) completed += id else completed -= id
        val bytes = d.bytesDownloaded
        val total = d.contentLength.takeIf { it > 0 } ?: bytes
        scope.launch(Dispatchers.IO) {
            library.setDownloadState(id, state, bytes, total)
            if (state == DownloadState.DONE) keepExtras(id)
        }
    }

    private fun removed(id: String) {
        completed -= id
        loudness -= id
        scope.launch(Dispatchers.IO) {
            library.download(id)?.let { OfflineArt.remove(it.track.cover) }
            library.deleteDownload(id)
            coverFile(id).delete()
            lyrics.forgetOffline(id)
        }
    }

    /** Paroles et pochette d'un titre complet, pour qu'il s'affiche entièrement hors ligne. */
    private suspend fun keepExtras(id: String) {
        val track = library.download(id)?.track?.toTrack() ?: return
        runCatching { lyrics.keepOffline(track) }.onFailure { Log.w(TAG, "paroles hors ligne", it) }
        if (track.cover.isBlank()) return
        val file = coverFile(id)
        if (!file.exists()) runCatching {
            http.newCall(Request.Builder().url(YtParser.resize(track.cover, 544)).build()).execute().use { r ->
                if (!r.isSuccessful) return@use
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "pochette hors ligne", it) }
        if (file.exists()) OfflineArt.put(track.cover, file)
    }

    private fun coverFile(id: String) = File(coversDir, "$id.jpg")

    companion object {
        private const val TAG = "UnisonDownloads"
        private const val STOP_USER = 1
        const val KEY_PREFIX = "dl:"

        /** Clé de cache d'un titre téléchargé : stable, quel que soit le format du flux. */
        fun cacheKey(id: String) = "$KEY_PREFIX$id"

        /**
         * État affiché d'un téléchargement d'après celui du gestionnaire de Media3 ; null pour les états
         * transitoires (suppression, redémarrage) qui ne changent rien à l'écran.
         */
        fun stateOf(media3State: Int, stopReason: Int): Int? = when (media3State) {
            Download.STATE_QUEUED -> if (stopReason != Download.STOP_REASON_NONE) DownloadState.PAUSED else DownloadState.QUEUED
            Download.STATE_STOPPED -> DownloadState.PAUSED
            Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
            Download.STATE_COMPLETED -> DownloadState.DONE
            Download.STATE_FAILED -> DownloadState.FAILED
            else -> null
        }
    }
}
