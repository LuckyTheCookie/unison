package fr.lulu.unison.lyrics

import java.text.Normalizer

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String,
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
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** Parse un fichier LRC (plusieurs horodatages par ligne, balises mot à mot ignorées). */
    fun parse(text: String): List<LyricLine> {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val stamps = timeTag.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val content = raw.substring(stamps.last().range.last + 1).replace(wordTag, "").trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                out += LyricLine((min * 60_000 + sec * 1000 + frac - offset).coerceAtLeast(0), content)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Lignes de crédits ajoutées par certains fournisseurs (作词 : …, 编曲：…). */
    fun isCredit(text: String): Boolean {
        val head = text.take(16)
        val hasColon = head.contains(':') || head.contains('：')
        val hasCjk = head.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return hasColon && hasCjk
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
