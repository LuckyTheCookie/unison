package fr.lulu.unison.sync

import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import fr.lulu.unison.deezer.DeezerController
import fr.lulu.unison.deezer.DeezerState
import fr.lulu.unison.model.PlayAnchor
import fr.lulu.unison.model.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.math.abs

/**
 * Fait suivre au Deezer de ce téléphone l'ancre de lecture de la session :
 * même titre, même position, même état lecture/pause.
 */
class PlaybackSync(
    private val scope: CoroutineScope,
    private val deezer: DeezerController,
    private val hostNow: () -> Long,
) {
    data class Status(val driftMs: Long? = null, val lastError: String? = null, val active: Boolean = false)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private var loop: Job? = null
    private var anchor: PlayAnchor = PlayAnchor()
    private var loadedUid: String? = null
    private var leadMs = 250L
    private var pausedSince = 0L
    private var graceUntil = 0L      // elapsedRealtime : on ignore la dérive jusque-là
    private var lastLoadAt = 0L
    private var badDrifts = 0

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) { loadedUid = null; scope.launch { deezer.pause() } } else onAnchor(anchor)
        }

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                delay(2_000)
                runCatching { correct() }.onFailure { Log.w(TAG, "correction", it) }
            }
        }
        _status.value = _status.value.copy(active = true)
    }

    fun stop() {
        loop?.cancel(); loop = null
        loadedUid = null
        _status.value = Status()
    }

    /** Nouvelle ancre reçue de l'hôte. */
    fun onAnchor(a: PlayAnchor) {
        val previous = anchor
        anchor = a
        if (!enabled) return
        scope.launch {
            mutex.withLock {
                val item = a.item
                when {
                    item == null -> { if (previous.item != null) deezer.pause(); loadedUid = null }
                    item.uid != loadedUid -> load(item, a)
                    a.playing != previous.playing || a.positionMs != previous.positionMs || a.hostTimeMs != previous.hostTimeMs -> {
                        if (a.playing) {
                            deezer.seekTo(expected(a) + leadMs)
                            deezer.resume()
                            grace()
                        } else {
                            deezer.pause()
                            deezer.seekTo(a.positionMs)
                        }
                    }
                }
            }
        }
    }

    private fun grace() { graceUntil = SystemClock.elapsedRealtime() + GRACE_MS; badDrifts = 0 }

    private suspend fun load(item: QueueItem, a: PlayAnchor) {
        lastLoadAt = SystemClock.elapsedRealtime()
        loadedUid = null
        // 1) session média (playFromUri), 2) ouverture de Deezer par intent (Unison au premier plan).
        var ok = false
        for (attempt in 0..1) {
            if (!deezer.playTrack(item.track.id, viaIntent = attempt == 1)) continue
            val r = withTimeoutOrNull(if (attempt == 0) 4_000 else 8_000) {
                deezer.state.first { it.playing && matches(it, item) }
            }
            if (r != null) { ok = true; break }
        }
        if (!ok) {
            _status.value = _status.value.copy(lastError = "Deezer n'a pas lancé le titre — ouvre Deezer")
            return
        }
        loadedUid = item.uid
        _status.value = _status.value.copy(lastError = null)
        val cur = anchor
        if (cur.item?.uid != item.uid) return
        if (!cur.playing) {
            deezer.pause(); deezer.seekTo(cur.positionMs); return
        }
        if (cur.hostTimeMs > hostNow()) {
            // Départ programmé : on se cale à 0 en pause, puis on lance à l'heure exacte.
            deezer.pause(); deezer.seekTo(cur.positionMs)
            while (anchor === cur && hostNow() < cur.hostTimeMs - leadMs) delay(15)
            if (anchor === cur) { deezer.resume(); grace() }
        } else {
            deezer.seekTo(expected(cur) + leadMs)
            grace()
        }
    }

    private fun canRetryLoad() = SystemClock.elapsedRealtime() - lastLoadAt > RETRY_MS

    /** Boucle de correction de dérive. */
    private suspend fun correct() = mutex.withLock {
        if (!enabled) return@withLock
        val a = anchor
        val item = a.item ?: return@withLock
        val d = deezer.state.value
        if (!d.connected) {
            deezer.ensureController()
            if (a.playing && canRetryLoad()) load(item, a)
            return@withLock
        }
        if (SystemClock.elapsedRealtime() < graceUntil) return@withLock
        if (a.playing && a.hostTimeMs > hostNow()) return@withLock // départ programmé en attente

        if (!matches(d, item)) {
            // Deezer joue autre chose (fin de titre, action manuelle…) : on remet le bon titre.
            if (a.playing && canRetryLoad()) load(item, a)
            return@withLock
        }
        if (a.playing && !d.playing) {
            if (d.stateCode == PlaybackState.STATE_BUFFERING || d.stateCode == PlaybackState.STATE_CONNECTING) return@withLock
            val now = System.currentTimeMillis()
            if (pausedSince == 0L) pausedSince = now
            if (now - pausedSince > 1_500) {
                deezer.seekTo(expected(a) + leadMs); deezer.resume(); pausedSince = 0; grace()
            }
            return@withLock
        }
        pausedSince = 0
        if (!a.playing && d.playing) { deezer.pause(); return@withLock }
        if (!a.playing) return@withLock

        if (d.stateCode == PlaybackState.STATE_BUFFERING) return@withLock
        val drift = d.positionNow() - expected(a)
        _status.value = _status.value.copy(driftMs = drift)
        if (abs(drift) > DRIFT_TOLERANCE_MS) {
            // Il faut deux mesures hors tolérance d'affilée pour agir (évite les seeks sur une mesure bruitée).
            if (++badDrifts < 2) return@withLock
            leadMs = (leadMs - drift / 3).coerceIn(0, 1_500)
            deezer.seekTo(expected(a) + leadMs)
            grace()
        } else badDrifts = 0
    }

    private fun expected(a: PlayAnchor) = a.positionAt(hostNow())

    private fun matches(d: DeezerState, item: QueueItem): Boolean {
        val id = item.track.id.toString()
        if (d.mediaId?.contains(id) == true) return true
        val t = norm(d.title ?: return false)
        val want = norm(item.track.title)
        return t.isNotEmpty() && want.isNotEmpty() && (t.startsWith(want) || want.startsWith(t))
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    companion object {
        private const val TAG = "UnisonSync"
        const val DRIFT_TOLERANCE_MS = 350L
        private const val GRACE_MS = 3_000L
        private const val RETRY_MS = 8_000L
    }
}
