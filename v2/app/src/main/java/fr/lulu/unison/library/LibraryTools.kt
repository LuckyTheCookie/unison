package fr.lulu.unison.library

import fr.lulu.unison.model.Track
import java.text.Normalizer
import kotlin.math.ln
import kotlin.random.Random

/** D'où vient un titre de la bibliothèque. */
sealed interface LibrarySource {
    data object Favorite : LibrarySource
    data class Playlist(val name: String) : LibrarySource
    data class History(val plays: Int) : LibrarySource
    data object Download : LibrarySource
    /** Album ou playlist YouTube Music enregistré dans la bibliothèque. */
    data class Saved(val title: String) : LibrarySource
}

/** Un titre tel qu'il apparaît à un endroit de la bibliothèque (le même titre peut apparaître plusieurs fois). */
data class LibraryEntry(val track: Track, val source: LibrarySource)

/** Un titre trouvé dans la bibliothèque, avec tous les endroits où il se trouve. */
data class LibraryHit(val track: Track, val sources: List<LibrarySource>) {
    /** « Favoris · Playlist Soirée · Téléchargé », dans un ordre stable. */
    val where: String
        get() = sources.sortedBy { order(it) }.mapNotNull { label(it) }.distinct().joinToString(" · ")

    private fun order(s: LibrarySource) = when (s) {
        LibrarySource.Favorite -> 0
        is LibrarySource.Playlist -> 1
        is LibrarySource.Saved -> 2
        LibrarySource.Download -> 3
        is LibrarySource.History -> 4
    }

    private fun label(s: LibrarySource) = when (s) {
        LibrarySource.Favorite -> "Favoris"
        is LibrarySource.Playlist -> s.name
        LibrarySource.Download -> "Téléchargé"
        is LibrarySource.History -> "Écouté"
        is LibrarySource.Saved -> s.title
    }
}

/** Recherche dans ce que l'utilisateur a déjà : favoris, playlists, téléchargements, historique. */
object LibrarySearch {
    /**
     * Chaque mot de [query] doit se trouver dans le titre, l'artiste, l'album ou le nom d'une playlist qui
     * contient le titre (sans tenir compte des accents ni de la casse). Les titres dont le nom commence par la
     * recherche passent en premier, puis ceux qui la contiennent, puis le reste.
     */
    fun search(query: String, entries: List<LibraryEntry>, limit: Int = 200): List<LibraryHit> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val q = words.joinToString(" ")
        return group(entries)
            .mapNotNull { hit ->
                val title = normalize(hit.track.title)
                val haystack = buildString {
                    append(title).append(' ').append(normalize(hit.track.artist)).append(' ').append(normalize(hit.track.album))
                    hit.sources.forEach { s ->
                        when (s) {
                            is LibrarySource.Playlist -> append(' ').append(normalize(s.name))
                            is LibrarySource.Saved -> append(' ').append(normalize(s.title))
                            else -> Unit
                        }
                    }
                }
                if (words.any { it !in haystack }) return@mapNotNull null
                val rank = when {
                    title.startsWith(q) -> 0
                    q in title -> 1
                    words.all { it in title } -> 2
                    else -> 3
                }
                Triple(hit, rank, hit.sources.size)
            }
            .sortedWith(compareBy<Triple<LibraryHit, Int, Int>> { it.second }.thenByDescending { it.third }.thenBy { it.first.track.title.lowercase() })
            .take(limit)
            .map { it.first }
    }

    /**
     * Recherche dans une seule liste (playlist, album, favoris) : les positions des titres dont le titre,
     * l'artiste ou l'album contient chaque mot de [query], dans l'ordre de la liste. Requête vide : tout.
     */
    fun within(query: String, tracks: List<Track>): List<Int> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return tracks.indices.toList()
        return tracks.indices.filter { i ->
            val t = tracks[i]
            val haystack = normalize(t.title) + ' ' + normalize(t.artist) + ' ' + normalize(t.album)
            words.all { it in haystack }
        }
    }

    /** Regroupe les apparitions d'un même titre. */
    fun group(entries: List<LibraryEntry>): List<LibraryHit> =
        entries.groupBy { it.track.id }.map { (_, list) -> LibraryHit(list.first().track, list.map { it.source }) }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}

/**
 * « Mix » : un mélange de ce que l'utilisateur a choisi de garder — favoris, playlists, albums et playlists
 * enregistrés, téléchargements. L'historique n'en fait pas partie : un titre écouté une fois n'est pas un titre
 * qu'on aime. Les titres présents à plusieurs endroits reviennent plus souvent, et deux titres du même artiste
 * ne se suivent pas quand on peut l'éviter.
 */
object LibraryMix {
    const val SIZE = 120

    fun build(entries: List<LibraryEntry>, random: Random = Random.Default, size: Int = SIZE, onlyIds: Set<String>? = null): List<Track> {
        val hits = LibrarySearch.group(entries.filter { it.source !is LibrarySource.History })
            .filter { onlyIds == null || it.track.id in onlyIds }
        if (hits.isEmpty()) return emptyList()
        // Tirage pondéré sans remise (Efraimidis–Spirakis) : clé = u^(1/poids), on garde les plus grandes.
        val picked = hits
            .map { hit -> hit to ln(random.nextDouble().coerceAtLeast(1e-12)) / weight(hit) }
            .sortedByDescending { it.second }
            .take(size)
            .map { it.first.track }
        return spreadArtists(picked)
    }

    /** Poids d'un titre : il s'additionne pour chaque endroit où le titre se trouve. */
    fun weight(hit: LibraryHit): Double = hit.sources.sumOf { s ->
        when (s) {
            LibrarySource.Favorite -> 3.0
            is LibrarySource.Playlist -> 2.0
            is LibrarySource.Saved -> 2.0
            LibrarySource.Download -> 1.5
            is LibrarySource.History -> 0.0
        }
    }.coerceAtLeast(0.5)

    /** Écarte deux titres consécutifs du même artiste, en avançant le premier titre qui ne l'est pas. */
    fun spreadArtists(tracks: List<Track>): List<Track> {
        val out = tracks.toMutableList()
        for (i in 1 until out.size) {
            if (!sameArtist(out[i], out[i - 1])) continue
            val j = (i + 1 until out.size).firstOrNull { !sameArtist(out[it], out[i - 1]) } ?: break
            out.add(i, out.removeAt(j))
        }
        return out
    }

    private fun sameArtist(a: Track, b: Track) =
        a.artist.isNotBlank() && LibrarySearch.normalize(a.artist) == LibrarySearch.normalize(b.artist)
}

/**
 * « Pour toi » : des titres proches de ceux que l'utilisateur écoute le plus et de ses favoris (les radios
 * YouTube Music de ces titres), sans ce qu'il connaît déjà. On prend tour à tour dans chaque radio, pour que
 * chaque goût soit représenté.
 */
object ForYou {
    fun pick(radios: List<List<Track>>, known: Set<String>, limit: Int = 30): List<Track> {
        val seen = HashSet<String>()
        val out = mutableListOf<Track>()
        val iterators = radios.map { it.iterator() }
        while (out.size < limit && iterators.any { it.hasNext() }) {
            for (it in iterators) {
                while (it.hasNext()) {
                    val t = it.next()
                    val same = LibrarySearch.normalize("${t.title} ${t.artist}")
                    if (t.id in known || !seen.add(t.id) || !seen.add(same)) continue
                    out += t
                    break
                }
                if (out.size >= limit) break
            }
        }
        return out
    }
}
