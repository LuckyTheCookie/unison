package fr.lulu.unison.source

import android.util.Log
import fr.lulu.unison.youtube.Quality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** D'où vient le son d'un titre : le lecteur et les téléchargements ne connaissent que cette interface. */
interface StreamResolver {
    /** Flux de [ref] ; lève [StreamUnavailableException] quand aucune source ne peut le jouer. */
    suspend fun resolve(ref: TrackRef, request: StreamRequest = StreamRequest.Best, purpose: Purpose = Purpose.PLAY): StreamInfo

    /** Oublie le flux connu de [id] (adresse expirée) ; la prochaine résolution le redemande. */
    fun invalidate(id: String)

    /**
     * Lecture : cherche [ref] dans la source importée choisie pendant que YouTube Music le joue déjà, avec plus de
     * temps que [resolve] n'en laisse. Flux de la source, ou null s'il n'y a rien de mieux à jouer.
     */
    suspend fun upgrade(ref: TrackRef, request: StreamRequest): StreamInfo? = null

    /** Source importée dont le flux de [ref] est déjà connu : un [TrackRef.via] qui la désigne se lit sans attente. */
    fun readySource(ref: TrackRef, request: StreamRequest): String? = null

    enum class Purpose { PLAY, DOWNLOAD }
}

/** Ce que la résolution lit des réglages, à chaque titre. */
data class ResolverSettings(
    /** [SourceIds.YOUTUBE] ou l'identifiant de la source importée choisie. */
    val activeSource: String = SourceIds.YOUTUBE,
    /** Un titre que la source choisie n'a pas (ou ne peut pas donner) se lit sur YouTube Music. */
    val fallbackToYouTube: Boolean = true,
)

/** Une source importée et ce qu'elle autorise. */
class ImportedSource(val source: MusicSource, val allowDownloads: Boolean)

/**
 * Choisit la source de chaque flux.
 *
 * - Titre d'une source importée (`src:…`) : cette source ; si elle n'est plus là, ne l'a plus ou ne répond pas,
 *   le même enregistrement est cherché sur YouTube Music (titre, artiste, durée).
 * - Titre YouTube Music, avec une source importée choisie : la lecture part sur YouTube Music sans attendre, et
 *   [upgrade] cherche en parallèle le même enregistrement dans la source ([TrackMatcher]) ; le lecteur bascule
 *   dessus une fois trouvé, en le désignant par [TrackRef.via]. Sans repli autorisé, la source est attendue.
 * - Écoute à plusieurs : les titres YouTube restent sur YouTube pour que tous les téléphones jouent exactement
 *   le même enregistrement (la synchronisation en dépend) ; un titre d'une source importée y est d'abord cherché
 *   sur YouTube Music, et ne vient de la source que s'il n'y est pas.
 * - « Économie de données » : pas de substitution (les sources importées visent la haute qualité).
 */
class SourceResolver(
    private val youtube: MusicSource,
    /** Source importée [id], ou null si elle n'existe pas (ou plus). */
    private val imported: (id: String) -> ImportedSource?,
    private val settings: () -> ResolverSettings = { ResolverSettings() },
    /** Vrai pendant une écoute à plusieurs. */
    private val shared: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : StreamResolver {
    private class Match(val trackId: String?, val until: Long)
    private class Found(val source: MusicSource, val trackId: String, val info: StreamInfo)

    /** Correspondances déjà établies : « source|titre » → identifiant dans l'autre source (null : absent). */
    private val matches = ConcurrentHashMap<String, Match>()
    /** Source et identifiant qui ont servi le dernier flux de chaque titre, pour [invalidate]. */
    private val served = ConcurrentHashMap<String, Pair<MusicSource, String>>()
    /** Flux de la source importée déjà trouvés, par titre YouTube Music. */
    private val found = ConcurrentHashMap<String, Found>()
    private val pending = ConcurrentHashMap<String, Deferred<Found?>>()

    override suspend fun resolve(ref: TrackRef, request: StreamRequest, purpose: StreamResolver.Purpose): StreamInfo {
        val s = settings()
        val budget = if (purpose == StreamResolver.Purpose.DOWNLOAD) DOWNLOAD_BUDGET_MS else PLAY_BUDGET_MS
        val src = SourceTrackId.parse(ref.id)
        if (src != null) {
            val (configId, trackId) = src
            val imp = imported(configId)
            // À plusieurs, chacun cherche d'abord le même enregistrement sur YouTube Music : tous les téléphones,
            // qu'ils aient ou non cette source, jouent alors le même fichier et restent synchronisés.
            if (shared() && purpose == StreamResolver.Purpose.PLAY) {
                attempt(budget) { matchIn(youtube, SourceIds.YOUTUBE, ref, budget)?.let { id -> youtube.stream(id, request)?.let { id to it } } }
                    ?.let { (id, info) -> return served(ref.id, youtube, id, info) }
            }
            if (imp != null && (purpose == StreamResolver.Purpose.PLAY || imp.allowDownloads)) {
                attempt(budget) { imp.source.stream(trackId, request) }?.let { return served(ref.id, imp.source, trackId, it) }
            }
            if (!s.fallbackToYouTube && imp != null) throw StreamUnavailableException("Titre indisponible dans la source")
            val ytId = matchIn(youtube, SourceIds.YOUTUBE, ref, budget)
                ?: throw StreamUnavailableException("Titre introuvable sur YouTube Music")
            return served(ref.id, youtube, ytId, youtube.stream(ytId, request) ?: throw StreamUnavailableException("Titre indisponible"))
        }

        // Élément du lecteur déjà passé sur la source : rouvert (saut, nouvel essai), il en garde le fichier, même si
        // l'écoute est devenue partagée ou l'économie de données activée entre-temps.
        if (ref.via != null && purpose == StreamResolver.Purpose.PLAY) {
            found[ref.id]?.takeIf { it.source.id == ref.via && fresh(it) }?.let { return served(ref.id, it.source, it.trackId, it.info) }
        }
        val imp = substituteFor(ref, request)?.takeIf { purpose == StreamResolver.Purpose.PLAY || it.allowDownloads }
        if (imp != null) {
            val f = when {
                purpose == StreamResolver.Purpose.DOWNLOAD -> attempt(budget) { lookup(imp.source, ref, request, budget) }
                ref.via == imp.source.id -> attempt(budget) { find(imp.source, ref, request) }
                // Sans repli, c'est la source ou rien : mieux vaut lui laisser le temps de répondre.
                !s.fallbackToYouTube -> find(imp.source, ref, request)
                else -> null
            }
            if (f != null) return served(ref.id, f.source, f.trackId, f.info)
            if (!s.fallbackToYouTube) throw StreamUnavailableException("Titre absent de la source choisie")
        }
        return served(ref.id, youtube, ref.id, youtube.stream(ref.id, request) ?: throw StreamUnavailableException("Titre indisponible"))
    }

    override suspend fun upgrade(ref: TrackRef, request: StreamRequest): StreamInfo? {
        val imp = substituteFor(ref, request) ?: return null
        return find(imp.source, ref, request)?.info
    }

    override fun readySource(ref: TrackRef, request: StreamRequest): String? =
        substituteFor(ref, request)?.let { known(it.source, ref) }?.source?.id

    /** Source importée qui peut jouer à la place de YouTube Music le titre YouTube [ref], ou null. */
    private fun substituteFor(ref: TrackRef, request: StreamRequest): ImportedSource? {
        if (SourceTrackId.parse(ref.id) != null || shared() || request != StreamRequest.Best) return null
        return settings().activeSource.takeIf { it != SourceIds.YOUTUBE }?.let(imported)
    }

    /**
     * [ref] et son flux dans [source], avec [UPGRADE_BUDGET_MS]. Les demandes simultanées d'un même titre
     * (préchargement de la file, puis lecture) partagent une seule recherche, qui va à son terme même si plus
     * personne ne l'attend : un serveur qui démarre à froid n'est attendu qu'une fois.
     */
    private suspend fun find(source: MusicSource, ref: TrackRef, request: StreamRequest): Found? {
        known(source, ref)?.let { return it }
        val key = "${source.id}|${ref.id}"
        val search = pending.computeIfAbsent(key) {
            // Démarrage différé : la recherche ne doit pas pouvoir finir (et se retirer) avant d'être enregistrée.
            background.async(start = CoroutineStart.LAZY) {
                attempt(UPGRADE_BUDGET_MS) { lookup(source, ref, request, UPGRADE_BUDGET_MS) }?.also { remember(ref.id, it) }
            }
        }
        search.invokeOnCompletion { pending.remove(key, search) }
        search.start()
        return search.await()
    }

    private suspend fun lookup(source: MusicSource, ref: TrackRef, request: StreamRequest, budgetMs: Long): Found? {
        val trackId = matchIn(source, source.id, ref, budgetMs) ?: return null
        return source.stream(trackId, request)?.let { Found(source, trackId, it) }
    }

    private fun known(source: MusicSource, ref: TrackRef): Found? = found[ref.id]?.takeIf { it.source === source && fresh(it) }

    private fun fresh(f: Found) = f.info.expiresAtMs - EXPIRY_MARGIN_MS > now()

    private fun remember(id: String, f: Found) {
        found[id] = f
        if (found.size > MAX_FOUND) found.entries.removeIf { !fresh(it.value) }
        Log.d(TAG, "titre trouvé dans la source ${f.source.id}")
    }

    private fun served(id: String, source: MusicSource, trackId: String, info: StreamInfo): StreamInfo {
        served[id] = source to trackId
        Log.d(TAG, "flux servi par ${if (source === youtube) "YouTube Music" else "la source ${source.id}"} (${info.codec ?: "format inconnu"}${if (info.itag != 0) ", itag ${info.itag}" else ""})")
        return info
    }

    /** Exécute [block] dans le temps imparti ; une panne ou un délai dépassé valent « pas de flux ». */
    private suspend fun <T> attempt(budgetMs: Long, block: suspend () -> T?): T? = try {
        withTimeout(budgetMs) { block() }
    } catch (e: TimeoutCancellationException) {
        Log.i(TAG, "source trop lente, repli")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "source indisponible (${e::class.simpleName}), repli")
        null
    }

    /** Identifiant, dans [source], du même enregistrement que [ref] (recherche puis [TrackMatcher]). */
    private suspend fun matchIn(source: MusicSource, sourceId: String, ref: TrackRef, budgetMs: Long): String? {
        if (ref.title.isBlank()) return null
        val key = "$sourceId|${ref.id}"
        matches[key]?.takeIf { it.until > now() }?.let { return it.trackId }
        val query = listOf(ref.title, ref.artist).filter { it.isNotBlank() }.joinToString(" ")
        val results = attempt(budgetMs) { source.search(query, SEARCH_LIMIT) } ?: return null
        val target = TrackMatcher.Candidate(ref.title, ref.artist, ref.durationMs)
        val best = TrackMatcher.best(target, results) { TrackMatcher.Candidate(it.title, it.artist, it.durationMs) }
        val trackId = best?.id?.let { SourceTrackId.parse(it)?.second ?: it }
        matches[key] = Match(trackId, now() + if (trackId != null) HIT_TTL_MS else MISS_TTL_MS)
        if (matches.size > MAX_MATCHES) matches.entries.removeIf { it.value.until <= now() }
        return trackId
    }

    /** Titres de la source importée [id] pour [query] (recherche directe dans la source). */
    suspend fun searchIn(id: String, query: String) = imported(id)?.source?.search(query, SEARCH_PAGE).orEmpty()

    override fun invalidate(id: String) {
        served.remove(id)?.let { (source, trackId) -> source.invalidate(trackId) }
        found.remove(id)
        if (SourceTrackId.parse(id) == null) youtube.invalidate(id)
    }

    private companion object {
        const val TAG = "UnisonSources"
        /** Temps accordé à une source importée avant de passer à YouTube Music, en lecture. */
        const val PLAY_BUDGET_MS = 6_000L
        /** Recherche menée pendant que YouTube Music joue : de quoi laisser démarrer un serveur endormi. */
        const val UPGRADE_BUDGET_MS = 20_000L
        /** Un flux trouvé n'est plus proposé à moins de cette marge de son expiration. */
        const val EXPIRY_MARGIN_MS = 2 * 60_000L
        const val MAX_FOUND = 64
        const val DOWNLOAD_BUDGET_MS = 20_000L
        const val SEARCH_LIMIT = 10
        const val SEARCH_PAGE = 30
        const val HIT_TTL_MS = 24 * 3_600_000L
        const val MISS_TTL_MS = 30 * 60_000L
        const val MAX_MATCHES = 1_000
    }
}

/** Demande de flux correspondant au réglage de qualité : « Économie de données » plafonne le débit. */
fun Quality.toRequest(): StreamRequest = when (this) {
    Quality.HIGH -> StreamRequest.Best
    Quality.LOW -> StreamRequest.Capped(LOW_KBPS)
}

/** Plafond de l'économie de données, en kbps. */
const val LOW_KBPS = 96
