package fr.lulu.unison.library

import fr.lulu.unison.importer.ImportMatch
import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.SongItem
import kotlin.math.pow

/** Types d'endroits d'où l'on lance une écoute. */
object ContextKind {
    const val PLAYLIST = "playlist"
    const val ALBUM = "album"
    /** Playlist YouTube Music (pas une playlist de la bibliothèque). */
    const val YT_PLAYLIST = "ytplaylist"
    const val ARTIST = "artist"
    const val FAVORITES = "favorites"
    const val MOST_PLAYED = "most"
    const val DOWNLOADS = "downloads"
}

/**
 * D'où vient une écoute : une playlist, un album, un artiste, les favoris… [key] l'identifie (« p:12 », « c:MPRE… »,
 * « a:UC… », « fav »…), le reste sert à l'afficher.
 */
data class PlayContext(
    val key: String,
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String = "",
) {
    /** Identifiant de la page (pid d'une playlist, browseId d'un album ou d'un artiste). */
    val id: String get() = key.substringAfter(':')

    companion object {
        fun playlist(pid: Long, name: String, cover: String? = null, subtitle: String = "") =
            PlayContext("p:$pid", ContextKind.PLAYLIST, name, subtitle, cover.orEmpty())

        fun collection(browseId: String, title: String, subtitle: String, thumbnail: String, isAlbum: Boolean) =
            PlayContext("c:$browseId", if (isAlbum) ContextKind.ALBUM else ContextKind.YT_PLAYLIST, title, subtitle, thumbnail)

        fun artist(browseId: String, name: String, thumbnail: String) = PlayContext("a:$browseId", ContextKind.ARTIST, name, "Artiste", thumbnail)

        val Favorites = PlayContext("fav", ContextKind.FAVORITES, "Favoris")
        val MostPlayed = PlayContext("most", ContextKind.MOST_PLAYED, "Les plus écoutés")
        val Downloads = PlayContext("dl", ContextKind.DOWNLOADS, "Téléchargements")

        /** Marque des titres venus de la radio automatique : ni d'un contexte, ni lancés seuls. */
        const val RADIO = "~radio"
    }
}

/**
 * Score « récent et fréquent » : une chaleur qui gagne 1 à chaque écoute et perd la moitié de sa valeur chaque
 * semaine. Une seule valeur et sa date suffisent, sans garder chaque écoute.
 */
object Frecency {
    const val HALF_LIFE_MS = 7 * 24 * 3_600_000L

    /** Deux écoutes d'un même contexte à moins de ce délai n'en font qu'une (une playlist enchaînée compte une fois). */
    const val SESSION_GAP_MS = 30 * 60_000L

    fun at(heat: Double, heatAt: Long, now: Long): Double {
        if (heat <= 0.0) return 0.0
        val age = (now - heatAt).coerceAtLeast(0L).toDouble()
        return heat * 0.5.pow(age / HALF_LIFE_MS)
    }

    fun bump(heat: Double, heatAt: Long, now: Long): Double = at(heat, heatAt, now) + 1.0

    /**
     * Nouvel état d'un contexte qu'on vient d'écouter : sa date avance toujours, sa chaleur seulement si la
     * précédente écoute date de plus de [SESSION_GAP_MS].
     */
    fun touch(row: PlayContextRow?, ctx: PlayContext, now: Long): PlayContextRow {
        if (row == null) return PlayContextRow(ctx.key, ctx.kind, ctx.title, ctx.subtitle, ctx.thumbnail, now, 1.0, now)
        val fresh = now - row.lastAt >= SESSION_GAP_MS
        return row.copy(
            kind = ctx.kind, title = ctx.title, subtitle = ctx.subtitle, thumbnail = ctx.thumbnail, lastAt = now,
            heat = if (fresh) bump(row.heat, row.heatAt, now) else row.heat,
            heatAt = if (fresh) now else row.heatAt,
        )
    }
}

/** Ce que montre « Réécouter » : un endroit (album, playlist, artiste) ou un titre lancé seul. */
sealed interface RewindItem {
    val at: Long
    val key: String

    data class Place(val context: PlayContext, override val at: Long) : RewindItem {
        override val key get() = context.key
    }

    data class Song(val track: Track, override val at: Long) : RewindItem {
        override val key get() = "t:" + track.id
    }
}

/** Choix de l'accueil à partir de ce qu'on écoute : sans réseau ni base, pour être testé. */
object HomeRank {
    /** Les contextes qui restent valables même sans figurer dans la bibliothèque (un album écouté sans l'enregistrer). */
    private val standalone = setOf(ContextKind.ALBUM, ContextKind.YT_PLAYLIST, ContextKind.ARTIST)

    /**
     * Contextes encore valables, avec les informations à jour de [live] (nom d'une playlist renommée, nombre de
     * titres). Une playlist supprimée ou des favoris vidés disparaissent.
     */
    fun valid(rows: List<PlayContextRow>, live: Map<String, PlayContext>): List<Pair<PlayContext, PlayContextRow>> =
        rows.mapNotNull { row ->
            val ctx = live[row.key] ?: if (row.kind in standalone) row.toContext() else null
            ctx?.let { it to row }
        }

    /**
     * Raccourcis du haut de l'accueil, hors Mix : les plus chauds, avec toujours les [recent] derniers écoutés,
     * du plus récent au plus ancien. Sans assez d'écoutes, [cold] complète dans son ordre.
     */
    fun shortcuts(
        rows: List<PlayContextRow>,
        live: Map<String, PlayContext>,
        cold: List<PlayContext>,
        now: Long,
        slots: Int = 7,
        recent: Int = 2,
    ): List<PlayContext> {
        val heard = valid(rows, live)
        val latest = heard.sortedByDescending { it.second.lastAt }.take(recent)
        val hottest = heard.sortedByDescending { Frecency.at(it.second.heat, it.second.heatAt, now) }
        val chosen = (latest + hottest).distinctBy { it.first.key }.take(slots)
            .sortedByDescending { it.second.lastAt }.map { it.first }
        val keys = chosen.mapTo(HashSet()) { it.key }
        return chosen + cold.filter { keys.add(it.key) }.take(slots - chosen.size)
    }

    /**
     * Garde la place des raccourcis déjà affichés ([old], leurs clés) : un raccourci qu'on vient de toucher ne
     * saute pas sous le doigt. Ceux qui disparaissent laissent leur place aux nouveaux, dans l'ordre de [new].
     */
    fun keepOrder(old: List<String>?, new: List<PlayContext>): List<PlayContext> {
        if (old.isNullOrEmpty()) return new
        val byKey = new.associateBy { it.key }
        val fresh = ArrayDeque(new.filter { it.key !in old })
        val out = old.mapNotNull { k -> byKey[k] ?: fresh.removeFirstOrNull() }.toMutableList()
        out += fresh
        return out
    }

    /**
     * « Réécouter » : les endroits écoutés (sauf [exclude], déjà en raccourci) et les titres lancés seuls, du plus
     * récent au plus ancien. Les titres d'une playlist ou de la radio n'y sont pas : ils y seraient par dizaines.
     */
    fun rewind(
        rows: List<PlayContextRow>,
        live: Map<String, PlayContext>,
        history: List<HistoryRow>,
        exclude: Set<String>,
        limit: Int = 12,
    ): List<RewindItem> {
        val places = valid(rows, live)
            .filter { (ctx, _) -> ctx.key !in exclude && ctx.kind in placeKinds }
            .map { (ctx, row) -> RewindItem.Place(ctx, row.lastAt) }
        val songs = history.filter { it.ctx == null }.map { RewindItem.Song(it.track.toTrack(), it.playedAt) }
        return (places + songs).sortedByDescending { it.at }.distinctBy { it.key }.take(limit)
    }

    private val placeKinds = setOf(ContextKind.PLAYLIST, ContextKind.ALBUM, ContextKind.YT_PLAYLIST, ContextKind.ARTIST)

    /**
     * Graines de « Pour toi » : les titres les plus chauds (écoutés récemment et souvent), le plus chaud du
     * contexte le plus chaud, puis [extra] (un favori au hasard).
     */
    fun seeds(history: List<HistoryRow>, rows: List<PlayContextRow>, extra: List<Track>, now: Long, hot: Int = 3, limit: Int = 5): List<Track> {
        val byHeat = history.sortedByDescending { Frecency.at(it.heat, it.heatAt, now) }
        val top = byHeat.take(hot)
        val ctx = rows.maxByOrNull { Frecency.at(it.heat, it.heatAt, now) }?.key
        val fromCtx = ctx?.let { k -> byHeat.firstOrNull { it.ctx == k && it !in top } }
        return (top + listOfNotNull(fromCtx)).map { it.track.toTrack() }.plus(extra).distinctBy { it.id }.take(limit)
    }
}

/** Artistes suivis rangés selon ce qu'on écoute d'eux. */
object ArtistRank {
    /**
     * Chaleur de chaque artiste suivi (par browseId) : celle de ses titres dans l'historique, plus celle de sa
     * page quand on l'a lancée.
     */
    fun scores(subs: List<SubscriptionRow>, history: List<HistoryRow>, rows: List<PlayContextRow>, now: Long): Map<String, Double> {
        if (subs.isEmpty()) return emptyMap()
        val byName = subs.groupBy { ImportMatch.norm(it.name) }
        val out = HashMap<String, Double>()
        for (h in history) {
            val heat = Frecency.at(h.heat, h.heatAt, now)
            if (heat <= 0.0) continue
            for (name in ImportMatch.artists(h.track.artist).map { ImportMatch.norm(it) }.distinct()) {
                byName[name]?.forEach { s -> out[s.browseId] = (out[s.browseId] ?: 0.0) + heat }
            }
        }
        for (r in rows) {
            if (r.kind != ContextKind.ARTIST) continue
            val id = r.key.substringAfter(':')
            if (subs.any { it.browseId == id }) out[id] = (out[id] ?: 0.0) + Frecency.at(r.heat, r.heatAt, now)
        }
        return out
    }

    /**
     * Ordre du fil : les plus écoutés d'abord, puis les autres dans l'ordre d'abonnement. [pick] retient les
     * [top] premiers écoutés et complète au hasard, pour la découverte.
     */
    fun order(subs: List<SubscriptionRow>, scores: Map<String, Double>): List<SubscriptionRow> {
        val (heard, rest) = subs.partition { (scores[it.browseId] ?: 0.0) > 0.0 }
        return heard.sortedByDescending { scores[it.browseId] } + rest
    }

    fun pick(subs: List<SubscriptionRow>, scores: Map<String, Double>, max: Int, top: Int = 5, random: kotlin.random.Random = kotlin.random.Random): List<SubscriptionRow> {
        val ordered = order(subs, scores)
        val heard = ordered.filter { (scores[it.browseId] ?: 0.0) > 0.0 }.take(top)
        // Sans écoutes, les plus récemment suivis gardent leur place, comme avant.
        val first = heard.ifEmpty { subs.take(3) }
        return (first + subs.filter { it !in first }.shuffled(random)).take(max)
    }
}

fun PlayContextRow.toContext() = PlayContext(key, kind, title, subtitle, thumbnail)

/**
 * « À découvrir » tiré de ce qu'on écoute : pour chaque titre de départ, la page « Similaires » de YouTube Music
 * (titres proches, artistes similaires). On n'y garde que ce qu'on ne connaît pas encore.
 */
object Discover {
    /** Un titre de départ et sa page « Similaires ». */
    data class Seed(val track: Track, val related: List<Section>)

    /**
     * [known] : titres déjà écoutés, en favoris ou déjà proposés ailleurs sur l'accueil ; [followed] : artistes
     * suivis.
     */
    fun build(seeds: List<Seed>, known: Set<String>, followed: Set<String>, songsPerSeed: Int = 12): List<Section> {
        val used = HashSet(known)
        val out = mutableListOf<Section>()
        // Un artiste proposé par plusieurs titres de départ passe devant.
        val artists = LinkedHashMap<String, Pair<ArtistItem, Int>>()
        for (seed in seeds) {
            // La première rangée de titres est celle des titres proches ; les suivantes sont d'autres versions.
            val songs = seed.related.firstOrNull { s -> s.items.isNotEmpty() && s.items.all { it is SongItem } }
                ?.items?.filterIsInstance<SongItem>().orEmpty()
                .filter { it.track.id != seed.track.id && used.add(it.track.id) }
                .take(songsPerSeed)
            if (songs.size >= 4) out += Section("Parce que tu écoutes ${seed.track.title}", songs)
            seed.related.filter { s -> s.items.isNotEmpty() && s.items.all { it is ArtistItem } }.flatMap { it.items }
                .filterIsInstance<ArtistItem>().filter { it.browseId !in followed }
                .forEach { a -> artists[a.browseId] = a to ((artists[a.browseId]?.second ?: 0) + 1) }
        }
        val topArtists = artists.values.sortedByDescending { it.second }.map { it.first }.take(15)
        if (topArtists.size >= 3) out += Section("Artistes à découvrir", topArtists)
        return out
    }
}
