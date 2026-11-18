package fr.lulu.unison.importer

import fr.lulu.unison.model.Track
import java.text.Normalizer

/**
 * Lecteur CSV selon la RFC 4180 : champs entre guillemets (avec virgules, retours à la ligne et guillemets
 * doublés), fins de ligne CRLF, LF ou CR, marque d'ordre d'octets en tête. Le séparateur (virgule,
 * point-virgule ou tabulation) est deviné sur la première ligne.
 */
object Csv {
    fun parse(input: String): List<List<String>> {
        val text = input.removePrefix("﻿")
        val sep = guessSeparator(text)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { row += field.toString(); field.setLength(0) }
        fun endRow() {
            endField()
            // Les lignes entièrement vides ne comptent pas.
            if (row.size > 1 || row[0].isNotEmpty()) rows += row
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
            } else when (c) {
                '"' -> quoted = true
                sep -> endField()
                '\r' -> { endRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun guessSeparator(text: String): Char {
        val counts = HashMap<Char, Int>()
        var quoted = false
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (quoted) continue
            if (c == '\n' || c == '\r') break
            if (c == ',' || c == ';' || c == '\t') counts[c] = (counts[c] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: ','
    }
}

/** Où va un titre importé. */
sealed interface ImportTarget {
    data object Favorites : ImportTarget
    data class Playlist(val name: String) : ImportTarget
}

/** Une ligne utile du fichier : un titre à retrouver, ou un artiste à suivre. */
sealed interface ImportEntry {
    /** Position dans le fichier (pour garder l'ordre). */
    val line: Int

    data class Song(
        override val line: Int,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val target: ImportTarget,
    ) : ImportEntry {
        /** Même titre, même artiste : une seule recherche pour toutes ses apparitions. */
        val key: String get() = ImportMatch.norm(title) + "|" + ImportMatch.norm(ImportMatch.artists(artist).firstOrNull().orEmpty())
        val query: String get() = listOf(title, ImportMatch.artists(artist).firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
    }

    data class Artist(override val line: Int, val name: String) : ImportEntry
}

/** Contenu d'un fichier d'export, prêt à importer. */
data class ImportPlan(
    val songs: List<ImportEntry.Song>,
    val artists: List<ImportEntry.Artist>,
    /** Lignes inexploitables (vides, type inconnu). */
    val skipped: Int,
) {
    val favorites: Int get() = songs.count { it.target == ImportTarget.Favorites }
    val playlists: Map<String, Int> get() = songs.mapNotNull { (it.target as? ImportTarget.Playlist)?.name }.groupingBy { it }.eachCount()
    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty()
}

/**
 * Interprète un export de bibliothèque (TuneMyMusic, Exportify, Soundiiz…) : les colonnes sont
 * reconnues d'après leur en-tête, quel que soit leur ordre.
 */
object LibraryCsv {
    private val titleCols = listOf("trackname", "title", "name", "song", "songname", "track", "titre", "nomdutitre")
    private val artistCols = listOf("artistname", "artistnames", "artist", "artists", "artiste", "artistes")
    private val albumCols = listOf("album", "albumname", "albumtitle")
    private val playlistCols = listOf("playlistname", "playlist", "playlisttitle")
    private val typeCols = listOf("type", "kind")
    private val durationCols = listOf("durationms", "duration", "length", "durationsec", "durationseconds")

    private fun key(header: String) = header.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** [fallbackPlaylist] : nom de playlist quand le fichier n'en donne pas (export d'une seule playlist). */
    fun plan(text: String, fallbackPlaylist: String = "Import"): ImportPlan {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return ImportPlan(emptyList(), emptyList(), 0)
        val header = rows.first().map(::key)
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val title = col(titleCols) ?: return ImportPlan(emptyList(), emptyList(), rows.size - 1)
        val artist = col(artistCols)
        val album = col(albumCols)
        val playlist = col(playlistCols)
        val type = col(typeCols)
        val duration = col(durationCols)
        val durationInSeconds = duration != null && header[duration].let { it.endsWith("sec") || it.endsWith("seconds") }

        val songs = mutableListOf<ImportEntry.Song>()
        val artists = mutableListOf<ImportEntry.Artist>()
        var skipped = 0
        rows.drop(1).forEachIndexed { i, r ->
            fun at(c: Int?) = c?.let { r.getOrNull(it) }?.trim().orEmpty()
            val name = at(title)
            val kind = at(type).lowercase()
            val list = at(playlist)
            when {
                name.isBlank() -> skipped++
                kind == "artist" || kind == "artiste" -> artists += ImportEntry.Artist(i + 1, name)
                kind == "favorite" || kind == "favourite" || kind == "liked" -> songs += song(i + 1, name, at(artist), at(album), at(duration), durationInSeconds, ImportTarget.Favorites)
                kind.isEmpty() || kind == "playlist" || kind == "track" || kind == "song" -> songs += song(
                    i + 1, name, at(artist), at(album), at(duration), durationInSeconds,
                    ImportTarget.Playlist(list.ifBlank { fallbackPlaylist }.take(60)),
                )
                // Albums entiers et autres types : pas encore pris en charge.
                else -> skipped++
            }
        }
        return ImportPlan(songs, artists, skipped)
    }

    private fun song(line: Int, title: String, artist: String, album: String, duration: String, seconds: Boolean, target: ImportTarget): ImportEntry.Song {
        val d = parseDuration(duration, seconds)
        return ImportEntry.Song(line, title, artist, album, d, target)
    }

    private fun parseDuration(raw: String, seconds: Boolean): Long {
        if (raw.isBlank()) return 0
        if (':' in raw) {
            val parts = raw.split(':').mapNotNull { it.trim().toLongOrNull() }
            return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
        }
        val n = raw.toDoubleOrNull() ?: return 0
        return if (seconds || n < 10_000) (n * 1000).toLong() else n.toLong()
    }
}

/**
 * Retrouve un titre importé parmi les résultats de recherche de YouTube Music : on compare titre,
 * artiste et album (et la durée quand le fichier la donne), après normalisation.
 */
object ImportMatch {
    // « avec » : YouTube Music en français écrit « Titre (avec X) » là où les autres services mettent « feat. X ».
    private val extras = Regex("""\s*[(\[][^)\]]*(feat|ft\.|with|avec|con |mit |remaster|radio|edit|version|live|mono|stereo|deluxe|bonus)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val brackets = Regex("""\s*[(\[][^)\]]*[)\]]""")
    /** Mentions de version : à titre égal, on préfère la même version que dans le fichier. */
    private val versionTags = listOf("slowed", "sped", "speed", "reverb", "remix", "live", "acoustic", "acoustique", "instrumental", "nightcore", "cover", "edit")
    private val dashExtras = Regex("""\s+-\s+.*(remaster|radio edit|version|live|mono|stereo).*$""", RegexOption.IGNORE_CASE)
    private val featTail = Regex("""\s+(feat\.?|ft\.?|featuring|avec)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /** Forme comparable : sans accents, minuscules, sans mentions annexes ; garde toutes les écritures. */
    fun norm(s: String): String = words(s).joinToString("")

    fun words(s: String): List<String> {
        val base = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val cleaned = base.replace(extras, "").replace(dashExtras, "").replace(featTail, "").lowercase()
        return cleaned.split(nonWord).filter { it.isNotEmpty() }
    }

    /** Les artistes d'un champ « A, B & C feat. D ». */
    fun artists(s: String): List<String> =
        s.split(Regex("""\s*[,;&/]\s*|\s+(?:x|et|and|with|avec|feat\.?|ft\.?|featuring)\s+""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }

    private fun similarity(a: String, b: String): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.length >= 3 && nb.length >= 3 && (na.contains(nb) || nb.contains(na))) return 0.8
        val wa = words(a).toSet()
        val wb = words(b).toSet()
        val inter = wa.intersect(wb).size.toDouble()
        return if (inter == 0.0) 0.0 else inter / (wa.size + wb.size - inter)
    }

    private fun artistScore(wanted: String, found: String): Double {
        val names = artists(wanted)
        if (names.isEmpty()) return 0.5 // inconnu : ni bonus ni malus
        val nf = norm(found)
        if (names.any { n -> norm(n).let { it.isNotEmpty() && (nf.contains(it) || (nf.length >= 3 && it.contains(nf))) } }) return 1.0
        return names.maxOf { similarity(it, found) }
    }

    /** Titre sans ce qui est entre parenthèses ou crochets (versions, invités, bande originale…). */
    private fun core(title: String): String = title.replace(brackets, "").trim().ifEmpty { title }

    private fun tags(title: String): Set<String> = words(title).filter { w -> versionTags.any { w.startsWith(it) } }.toSet()

    /**
     * Ressemblance des titres : le cœur du titre doit correspondre (« MIMIMI (Slowed) » n'est pas
     * « Neoperreo (Slowed) »), le titre complet départage.
     */
    private fun titleScore(wanted: String, found: String): Double {
        val c = similarity(core(wanted), core(found))
        if (c < 0.5) return c
        return maxOf(c, similarity(wanted, found))
    }

    /** Note de 0 à 1 ; null quand le candidat ne correspond pas. */
    fun score(entry: ImportEntry.Song, candidate: Track): Double? {
        val title = titleScore(entry.title, candidate.title)
        val artist = artistScore(entry.artist, candidate.artist)
        if (title < 0.5 || artist < 0.5) return null
        val album = when {
            entry.album.isBlank() || candidate.album.isBlank() -> 0.5
            else -> similarity(entry.album, candidate.album)
        }
        var total = 0.55 * title + 0.35 * artist + 0.10 * album
        // La version (ralentie, remix, live…) compte plus que l'album, souvent approximatif pour les singles.
        total += if (tags(entry.title) == tags(candidate.title)) 0.03 else -0.1
        if (entry.durationMs > 0 && candidate.durationMs > 0) {
            val diff = kotlin.math.abs(entry.durationMs - candidate.durationMs)
            total += when {
                diff <= 3_000 -> 0.05
                diff > 20_000 -> -0.2
                else -> 0.0
            }
        }
        return total.takeIf { it >= 0.6 }
    }

    /** Meilleur candidat, ou null si aucun n'est assez proche. */
    fun best(entry: ImportEntry.Song, candidates: List<Track>): Track? =
        candidates.mapNotNull { c -> score(entry, c)?.let { c to it } }.maxByOrNull { it.second }?.first
}
