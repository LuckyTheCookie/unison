package fr.lulu.unison.source

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Retrouve le même enregistrement d'une source à l'autre, d'après le titre, l'artiste et la durée.
 * Les règles sont strictes : mieux vaut lire YouTube Music qu'une autre version du morceau.
 *
 * - titre « propre » (sans parenthèses, crochets ni « feat. ») identique ;
 * - mêmes mots de version des deux côtés (remix, live, acoustique…) : un remix ne remplace jamais l'original,
 *   ni l'inverse ; le reste du contexte entre parenthèses (« Remastered », « From the film… ») n'élimine pas ;
 * - au moins un artiste en commun quand les deux côtés en nomment ;
 * - durées proches quand les deux sont connues.
 */
object TrackMatcher {
    data class Candidate(val title: String, val artist: String, val durationMs: Long)

    /** Le meilleur candidat de [candidates] pour [target], ou null si aucun n'est assez sûr. */
    fun <T> best(target: Candidate, candidates: List<T>, describe: (T) -> Candidate): T? =
        candidates.mapNotNull { c -> score(target, describe(c))?.let { c to it } }.minByOrNull { it.second }?.first

    /** Écart (plus petit = meilleur) entre [a] et [b], ou null s'ils ne sont pas le même enregistrement. */
    fun score(a: Candidate, b: Candidate): Long? {
        if (cleanTitle(a.title).isEmpty() || cleanTitle(a.title) != cleanTitle(b.title)) return null
        if (versionWords(a.title) != versionWords(b.title)) return null
        val artistsA = artists(a.artist)
        val artistsB = artists(b.artist)
        if (artistsA.isNotEmpty() && artistsB.isNotEmpty() && artistsA.none { x -> artistsB.any { y -> sameArtist(x, y) } }) return null
        val gap = if (a.durationMs > 0 && b.durationMs > 0) abs(a.durationMs - b.durationMs) else UNKNOWN_GAP_MS
        if (a.durationMs > 0 && b.durationMs > 0 && gap > tolerance(max(a.durationMs, b.durationMs))) return null
        // Départage : durée la plus proche, puis titre complet identique.
        return gap + if (normalize(a.title) == normalize(b.title)) 0 else 500
    }

    /** Écart de durée accepté : 4 s, ou 2 % pour les titres longs. */
    fun tolerance(durationMs: Long): Long = max(4_000L, durationMs / 50)

    fun cleanTitle(title: String): String {
        var t = title
        t = t.replace(BRACKETS, " ")
        t = t.replace(FEAT, " ")
        t = t.substringBefore(" - ").ifBlank { t }
        return normalize(t)
    }

    /** Titre à chercher : sans parenthèses, crochets, « feat. » ni suffixe « - … », casse et accents gardés. */
    fun baseTitle(title: String): String {
        val t = title.replace(BRACKETS, " ").replace(FEAT, " ")
        return t.substringBefore(" - ").ifBlank { t }.trim().replace(SPACES, " ")
    }

    /** Premier artiste d'une liste (« Jul, SCH & Naps » → « Jul »). */
    fun mainArtist(raw: String): String = raw.split(ARTIST_SEP).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** Mots qui désignent une autre version du morceau, où qu'ils soient dans le titre. */
    fun versionWords(title: String): Set<String> {
        val words = normalize(title).split(' ').filter { it.isNotEmpty() }
        val out = mutableSetOf<String>()
        for ((i, w) in words.withIndex()) {
            val canonical = VERSION[w] ?: continue
            // « Radio edit » et « mix » seuls (« Original mix ») ne changent pas l'enregistrement de référence.
            if (canonical == "edit" && words.getOrNull(i - 1) == "radio") continue
            if (canonical == "mix" && words.getOrNull(i - 1) in setOf("original", "album", "main")) continue
            out += canonical
        }
        if ("sped" in words && "up" in words) out += "sped up"
        return out
    }

    private fun artists(raw: String): List<String> =
        raw.split(ARTIST_SEP).map(::normalize).filter { it.isNotEmpty() }

    private fun sameArtist(a: String, b: String) = a == b || (min(a, b).length >= 4 && (a.contains(b) || b.contains(a)))

    private fun min(a: String, b: String) = if (a.length <= b.length) a else b

    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        return decomposed.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .replace('&', ' ')
            .replace(NON_WORD, " ")
            .trim().replace(SPACES, " ")
    }

    private const val UNKNOWN_GAP_MS = 3_000L
    private val BRACKETS = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val FEAT = Regex("""\b(feat\.?|ft\.?|featuring|avec)\s.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SEP = Regex("""\s*(,|&|;|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\band\b|\bet\b)\s*""", RegexOption.IGNORE_CASE)
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
    private val VERSION = mapOf(
        "remix" to "remix", "rmx" to "remix", "mix" to "mix", "rework" to "remix", "bootleg" to "remix", "mashup" to "remix",
        "live" to "live", "concert" to "live", "unplugged" to "live",
        "acoustic" to "acoustic", "acoustique" to "acoustic", "acustico" to "acoustic",
        "instrumental" to "instrumental", "karaoke" to "karaoke", "acapella" to "acapella", "cappella" to "acapella",
        "cover" to "cover", "demo" to "demo", "edit" to "edit",
        "slowed" to "slowed", "reverb" to "slowed", "nightcore" to "nightcore", "sped" to "sped up",
        "orchestral" to "orchestral", "piano" to "piano",
    )
}
