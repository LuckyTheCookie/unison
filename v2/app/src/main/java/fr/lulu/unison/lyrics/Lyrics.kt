package fr.lulu.unison.lyrics

import kotlinx.serialization.Serializable
import java.text.Normalizer

/** Un mot (ou une syllabe) chanté de [startMs] à [endMs] ; [text] garde l'espace qui le suit. */
@Serializable
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * Une ligne de paroles. [words] découpe [text] (leur concaténation donne exactement [text]) avec le moment
 * où chaque mot est chanté ; null tant que la ligne n'est synchronisée qu'à la ligne.
 */
@Serializable
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord>? = null)

@Serializable
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String,
    /** Vrai quand la source donne le moment de chaque mot (YRC, LRC enrichi) ; sinon il est estimé. */
    val wordSynced: Boolean = false,
) {
    /** Index de la ligne en cours à [positionMs] (-1 avant la première ligne). */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

object Lrc {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    private fun ms(min: String, sec: String, fracStr: String): Long {
        val frac = when (fracStr.length) {
            0 -> 0L
            1 -> fracStr.toLong() * 100
            2 -> fracStr.toLong() * 10
            else -> fracStr.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + frac
    }

    /**
     * Parse un fichier LRC : plusieurs horodatages par ligne, et balises mot à mot du LRC enrichi
     * (`[00:12.00]<00:12.00>Un <00:12.40>mot <00:12.90>`), qui donnent le début de chaque mot.
     */
    fun parse(text: String): List<LyricLine> {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val stamps = timeTag.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val body = raw.substring(stamps.last().range.last + 1)
            val content = body.replace(wordTag, "").trim()
            val first = ms(stamps[0].groupValues[1], stamps[0].groupValues[2], stamps[0].groupValues[3])
            for (m in stamps) {
                val at = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                val start = (at - offset).coerceAtLeast(0)
                // Une ligne répétée (plusieurs horodatages) reprend ses temps de mots décalés d'autant.
                out += LyricLine(start, content, enhancedWords(body, start, offset - (at - first)))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Mots d'une ligne de LRC enrichi, ou null s'il n'y a pas de balise mot à mot. */
    private fun enhancedWords(body: String, lineStart: Long, offset: Long): List<LyricWord>? {
        val tags = wordTag.findAll(body).toList()
        if (tags.isEmpty()) return null
        // Segments (début, texte) : le texte avant la première balise commence avec la ligne.
        val segments = mutableListOf<Pair<Long, String>>()
        val head = body.substring(0, tags.first().range.first)
        if (head.isNotBlank()) segments += lineStart to head
        tags.forEachIndexed { i, m ->
            val t = (ms(m.groupValues[1], m.groupValues[2], m.groupValues[3]) - offset).coerceAtLeast(0)
            val end = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            segments += t to body.substring(m.range.last + 1, end)
        }
        val words = mutableListOf<LyricWord>()
        segments.forEachIndexed { i, (start, raw) ->
            // Une balise sans texte derrière marque la fin du mot précédent.
            if (raw.isEmpty()) return@forEachIndexed
            val end = segments.getOrNull(i + 1)?.first ?: -1L
            words += LyricWord(start, end, raw)
        }
        return Karaoke.trimmed(words)
    }

    /** Lignes de crédits ajoutées par certains fournisseurs (作词 : …, 编曲：…). */
    fun isCredit(text: String): Boolean {
        val head = text.take(16)
        val hasColon = head.contains(':') || head.contains('：')
        val hasCjk = head.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return hasColon && hasCjk
    }

    private val yrcLine = Regex("""^\[(\d+),(\d+)]""")
    private val yrcWord = Regex("""\((\d+),(\d+),\d+\)""")

    /**
     * Parse les paroles mot à mot de NetEase (YRC) : `[début,durée](début,durée,0)mot(début,durée,0)mot…`,
     * en millisecondes. Les lignes de métadonnées (JSON) sont ignorées.
     */
    fun parseYrc(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val head = yrcLine.find(raw) ?: continue
            val lineStart = head.groupValues[1].toLong()
            val lineEnd = lineStart + head.groupValues[2].toLong()
            val body = raw.substring(head.range.last + 1)
            val tags = yrcWord.findAll(body).toList()
            val words = tags.mapIndexedNotNull { i, m ->
                val start = m.groupValues[1].toLong()
                val end = (start + m.groupValues[2].toLong()).coerceAtMost(lineEnd.coerceAtLeast(start + 1))
                val until = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                val word = body.substring(m.range.last + 1, until)
                if (word.isEmpty()) null else LyricWord(start, end.coerceAtLeast(start + 1), word)
            }
            val trimmed = Karaoke.trimmed(words) ?: continue
            out += LyricLine(lineStart, trimmed.joinToString("") { it.text }, trimmed)
        }
        return out.sortedBy { it.timeMs }
    }

    fun plain(text: String): List<LyricLine> =
        text.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }.map { LyricLine(0, it) }
}

/** Normalisation pour comparer des titres d'origines différentes. */
fun normTitle(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "")
    .lowercase()
    .replace(Regex("""\s*[(\[].*?(feat|ft\.|with|remaster|radio|edit|version|live|mono|stereo).*?[)\]]"""), "")
    .replace(Regex("""\s+-\s+.*(remaster|radio|edit|version|live).*$"""), "")
    .replace(Regex("[^a-z0-9]"), "")

/**
 * Paroles mot à mot : temps de chaque mot (réel ou estimé) et avancement du remplissage d'une ligne.
 */
object Karaoke {
    private val token = Regex("""(?:[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]|[^\s\p{IsHan}\p{IsHiragana}\p{IsKatakana}]+)\s*""")

    /** Débarrasse la ligne de ses espaces de bord, pour que les mots recollés donnent le texte affiché. */
    fun trimmed(words: List<LyricWord>): List<LyricWord>? {
        val list = words.toMutableList()
        while (list.isNotEmpty() && list.first().text.isBlank()) list.removeAt(0)
        while (list.isNotEmpty() && list.last().text.isBlank()) list.removeAt(list.lastIndex)
        if (list.isEmpty()) return null
        list[0] = list[0].copy(text = list[0].text.trimStart())
        list[list.lastIndex] = list.last().copy(text = list.last().text.trimEnd())
        return list
    }

    /** Poids d'un mot : à peu près le temps qu'il faut pour le chanter (un idéogramme vaut une syllabe). */
    fun weight(text: String): Float = text.sumOf { c ->
        when {
            Character.isIdeographic(c.code) || Character.UnicodeScript.of(c.code).let {
                it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA
            } -> 2.5
            c.isLetterOrDigit() -> 1.0
            c.isWhitespace() -> 0.0
            else -> 0.3
        }
    }.toFloat().coerceAtLeast(0.5f)

    /**
     * Complète des paroles synchronisées : fin de chaque mot connue, et, pour les lignes sans horodatage
     * mot à mot, temps de la ligne réparti sur ses mots selon leur poids, jusqu'à la ligne suivante
     * (sans traîner plus que nécessaire avant un passage instrumental).
     */
    fun complete(lyrics: Lyrics): Lyrics {
        if (!lyrics.synced) return lyrics
        val lines = lyrics.lines
        val out = lines.mapIndexed { i, line ->
            val next = lines.drop(i + 1).firstOrNull { it.timeMs > line.timeMs }?.timeMs
            val words = line.words
            if (words != null) {
                line.copy(words = words.mapIndexed { j, w ->
                    if (w.endMs > w.startMs) w
                    else w.copy(endMs = words.getOrNull(j + 1)?.startMs?.takeIf { it > w.startMs } ?: lineEnd(line, next, w.startMs))
                })
            } else if (line.text.isBlank()) line
            else line.copy(words = estimate(line, next))
        }
        return lyrics.copy(lines = out)
    }

    private fun lineEnd(line: LyricLine, next: Long?, from: Long): Long {
        val guess = from + 600 + (weight(line.text) * 90).toLong()
        return (next?.coerceAtMost(guess.coerceAtLeast(from + 300)) ?: guess).coerceAtLeast(from + 1)
    }

    private fun estimate(line: LyricLine, next: Long?): List<LyricWord> {
        val parts = token.findAll(line.text).map { it.value }.toList().ifEmpty { listOf(line.text) }
        val weights = parts.map { weight(it) }
        val total = weights.sum()
        val singing = 600 + (total * 110).toLong()
        val gap = next?.let { it - line.timeMs } ?: singing
        // Jusqu'à la ligne suivante, sauf si elle est loin : on garde alors un débit de chant plausible.
        val duration = gap.coerceAtMost((singing * 1.6).toLong()).coerceAtLeast(gap.coerceAtMost(400)).coerceAtLeast(1)
        var t = line.timeMs.toDouble()
        val words = parts.mapIndexed { i, p ->
            val d = duration * (weights[i] / total)
            val w = LyricWord(t.toLong(), (t + d).toLong().coerceAtLeast(t.toLong() + 1), p)
            t += d
            w
        }
        // Le dernier mot rejoint le texte exact de la ligne (le découpage ne perd aucun caractère).
        return if (words.joinToString("") { it.text } == line.text) words
        else listOf(LyricWord(line.timeMs, line.timeMs + duration, line.text))
    }

    /**
     * Nombre de caractères de [line] déjà chantés à [positionMs], avec une fraction pour le caractère en cours :
     * 0 avant le premier mot, la longueur du texte après le dernier.
     */
    fun progress(line: LyricLine, positionMs: Long): Float {
        val words = line.words ?: return if (positionMs >= line.timeMs) line.text.length.toFloat() else 0f
        var chars = 0f
        for (w in words) {
            val len = w.text.length
            when {
                positionMs >= w.endMs -> chars += len
                positionMs > w.startMs -> return chars + len * ((positionMs - w.startMs).toFloat() / (w.endMs - w.startMs))
                else -> return chars
            }
        }
        return chars
    }
}
