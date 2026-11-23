package fr.lulu.unison.lyrics

import fr.lulu.unison.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/*
 * Sources de paroles synchronisées mot à mot (souvent à la syllabe), sans compte ni clé.
 * Choix des services et format de LyricsPlus repris de BitChord (https://github.com/kushagrasinghx/BitChord,
 * GPL-3.0) ; voir THIRD_PARTY_NOTICES.md.
 */

private val wordJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Ces services répondent parfois lentement : au-delà, on passe aux autres sources. */
private fun OkHttpClient.forLyrics(): OkHttpClient = newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

private fun OkHttpClient.fetch(url: String): String? {
    val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Unison").build()
    return newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
}

/** Les paroles collent-elles à la durée du titre (la dernière ligne ne tombe pas après la fin) ? */
internal fun fitsDuration(lines: List<LyricLine>, track: Track): Boolean =
    track.durationMs <= 0 || (lines.lastOrNull()?.timeMs ?: 0) <= track.durationMs + 10_000

/**
 * BetterLyrics (le service de l'extension YouTube Music du même nom) : le TTML d'Apple Music, minuté
 * à la syllabe, d'après le titre, l'artiste et la durée.
 */
class BetterLyricsProvider(http: OkHttpClient) : LyricsProvider {
    override val name = "BetterLyrics"
    private val http = http.forLyrics()

    @Serializable private data class Response(val ttml: String? = null)

    override suspend fun find(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", track.title)
            .addQueryParameter("a", track.artist)
            .apply {
                val seconds = track.durationMs / 1000
                if (seconds > 0) addQueryParameter("d", seconds.toString())
                if (track.album.isNotBlank()) addQueryParameter("al", track.album)
            }
            .build().toString()
        val body = http.fetch(url) ?: return@withContext null
        val ttml = runCatching { wordJson.decodeFromString(Response.serializer(), body).ttml }.getOrNull() ?: return@withContext null
        val lines = Ttml.parse(ttml).takeIf { it.count { l -> l.text.isNotBlank() } >= 3 && fitsDuration(it, track) } ?: return@withContext null
        Lyrics(lines, synced = true, source = name, wordSynced = lines.any { it.words != null })
    }
}

/**
 * LyricsPlus (le service de l'extension YouLy+) : Apple Music, QQ Music, Deezer, minutés à la syllabe.
 * Il tourne sur des miroirs bénévoles, souvent indisponibles : on les interroge tous et on garde la première
 * vraie réponse, puis on commence par ce miroir la fois suivante.
 */
class LyricsPlusProvider(http: OkHttpClient) : LyricsProvider {
    override val name = "LyricsPlus"
    private val http = http.forLyrics()
    private val lastGood = AtomicReference<String?>(null)

    @Serializable data class Syllable(val time: Long? = null, val duration: Long? = null, val text: String? = null)
    @Serializable data class Line(
        val time: Long? = null,
        val duration: Long? = null,
        val text: String? = null,
        @SerialName("syllabus") val syllabus: List<Syllable>? = null,
    )
    @Serializable data class Response(val type: String? = null, val lyrics: List<Line>? = null)

    override suspend fun find(track: Track): Lyrics? = coroutineScope {
        val hosts = lastGood.get()?.let { good -> listOf(good) + MIRRORS.filterNot { it == good } } ?: MIRRORS
        val pending = hosts.associateWith { host -> async(Dispatchers.IO) { runCatching { fetch(host, track) }.getOrNull() } }.toMutableMap()
        try {
            while (pending.isNotEmpty()) {
                val (host, lyrics) = select { pending.forEach { (host, job) -> job.onAwait { host to it } } }
                pending.remove(host)
                if (lyrics != null) {
                    lastGood.set(host)
                    return@coroutineScope lyrics
                }
            }
            null
        } finally {
            pending.values.forEach { it.cancel() }
        }
    }

    private fun fetch(host: String, track: Track): Lyrics? {
        val url = "$host/v2/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("title", track.title)
            .addQueryParameter("artist", track.artist)
            .apply {
                val seconds = track.durationMs / 1000
                if (seconds > 0) addQueryParameter("duration", seconds.toString())
                if (track.album.isNotBlank()) addQueryParameter("album", track.album)
            }
            .build().toString()
        val body = http.fetch(url) ?: return null
        val response = runCatching { wordJson.decodeFromString(Response.serializer(), body) }.getOrNull() ?: return null
        val lines = parse(response).takeIf { it.count { l -> l.text.isNotBlank() } >= 3 && fitsDuration(it, track) } ?: return null
        return Lyrics(lines, synced = true, source = name, wordSynced = lines.any { it.words != null })
    }

    companion object {
        private val MIRRORS = listOf("https://lyricsplus.prjktla.my.id", "https://lyricsplus.binimum.org")

        fun parse(response: Response): List<LyricLine> = response.lyrics.orEmpty().mapNotNull { line ->
            val start = line.time ?: return@mapNotNull null
            val runs = line.syllabus.orEmpty().mapNotNull { s ->
                val t = s.time ?: return@mapNotNull null
                val text = s.text ?: return@mapNotNull null
                LyricWord(t, t + (s.duration ?: 0L).coerceAtLeast(1), text)
            }
            val words = Karaoke.trimmed(runs)
            when {
                words != null -> LyricLine(minOf(start, words.first().startMs), words.joinToString("") { it.text }, words)
                !line.text.isNullOrBlank() -> LyricLine(start, line.text.trim())
                else -> null
            }
        }.sortedBy { it.timeMs }
    }
}

/**
 * Le TTML d'Apple Music : un `<p>` par ligne, un `<span>` minuté par syllabe ; l'espace entre deux `<span>`
 * sépare deux mots. Les chœurs (`ttm:role="x-bg"`) sont laissés de côté : ils se chevauchent avec la voix
 * principale, et la ligne n'en montre qu'une.
 */
object Ttml {
    fun parse(text: String): List<LyricLine> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = runCatching { factory.newDocumentBuilder().parse(InputSource(StringReader(text))) }.getOrNull() ?: return emptyList()
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        val out = mutableListOf<LyricLine>()
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val begin = time(p.getAttribute("begin")) ?: continue
            val runs = mutableListOf<LyricWord>()
            collect(p, runs)
            val words = Karaoke.trimmed(runs)
            out += if (words != null) LyricLine(minOf(begin, words.first().startMs), words.joinToString("") { it.text }, words)
            else LyricLine(begin, p.textContent.orEmpty().replace(Regex("\\s+"), " ").trim())
        }
        return out.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
    }

    private fun collect(node: Node, runs: MutableList<LyricWord>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE -> {
                    // Espace entre deux syllabes : fin du mot précédent.
                    if (child.nodeValue.orEmpty().any { it.isWhitespace() } && runs.isNotEmpty()) {
                        val last = runs.last()
                        if (!last.text.endsWith(" ")) runs[runs.lastIndex] = last.copy(text = last.text + " ")
                    }
                }
                Node.ELEMENT_NODE -> {
                    val e = child as Element
                    if (e.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role") == "x-bg" || e.getAttribute("ttm:role") == "x-bg") continue
                    val begin = time(e.getAttribute("begin"))
                    val end = time(e.getAttribute("end"))
                    val hasSpans = e.getElementsByTagNameNS("*", "span").length > 0
                    if (begin != null && !hasSpans) {
                        val t = e.textContent.orEmpty()
                        if (t.isNotEmpty()) runs += LyricWord(begin, (end ?: begin + 1).coerceAtLeast(begin + 1), t)
                    } else {
                        collect(e, runs)
                    }
                }
            }
        }
    }

    /** « 1:23.456 », « 01:02:03.4 », « 83.456 », « 83.4s ». */
    fun time(value: String?): Long? {
        val v = value?.trim()?.removeSuffix("s")?.takeIf { it.isNotEmpty() } ?: return null
        val parts = v.split(':')
        return runCatching {
            val seconds = parts.last().toDouble()
            val minutes = parts.getOrNull(parts.size - 2)?.toLong() ?: 0L
            val hours = parts.getOrNull(parts.size - 3)?.toLong() ?: 0L
            ((hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000))
        }.getOrNull()
    }
}
