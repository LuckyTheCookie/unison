package fr.lulu.unison.source

import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.MusicCatalog
import fr.lulu.unison.youtube.ResolvedStream
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YouTubeEngine

/** YouTube Music vu comme une source : le moteur existant, dont les flux sont décrits en [StreamInfo]. */
class YouTubeSource(private val engine: YouTubeEngine, private val catalog: MusicCatalog) : MusicSource {
    override val id = SourceIds.YOUTUBE
    override val displayName = "YouTube Music"

    override suspend fun health(): SourceHealth = SourceHealth.Ok

    override suspend fun search(query: String, limit: Int): List<Track> =
        catalog.search(query, SearchFilter.SONGS).filterIsInstance<SongItem>().map { it.track }.take(limit)

    /** La qualité suit le réglage de l'application (lu par le moteur) ; jamais null : une erreur lève une exception. */
    override suspend fun stream(trackId: String, request: StreamRequest): StreamInfo = describe(engine.resolve(trackId))

    override fun invalidate(trackId: String) = engine.invalidate(trackId)

    companion object {
        /** Débit nominal des formats audio de YouTube (kbps), d'après leur itag. */
        private val ITAG_KBPS = mapOf(
            249 to 50, 250 to 70, 251 to 160, 774 to 256, 600 to 35,
            139 to 48, 140 to 128, 141 to 256, 599 to 30,
        )

        private val OPUS_ITAGS = setOf(249, 250, 251, 600, 774)
        private val AAC_ITAGS = setOf(139, 140, 141, 599)

        /** Codec d'un format YouTube d'après son itag (téléchargements antérieurs à l'enregistrement du codec). */
        fun codecOfItag(itag: Int): String? = when (itag) { in OPUS_ITAGS -> "opus"; in AAC_ITAGS -> "aac"; else -> null }

        fun kbpsOfItag(itag: Int): Int? = ITAG_KBPS[itag]

        fun describe(s: ResolvedStream): StreamInfo {
            val mime = s.mimeType?.lowercase()
            val codec = when {
                mime != null && "opus" in mime -> "opus"
                mime != null && ("mp4a" in mime || "aac" in mime) -> "aac"
                mime != null && "vorbis" in mime -> "vorbis"
                s.itag in OPUS_ITAGS -> "opus"
                s.itag in AAC_ITAGS -> "aac"
                else -> null
            }
            val container = mime?.substringAfter('/', "")?.substringBefore(';')?.trim()?.ifBlank { null }
                ?: when (codec) { "opus" -> "webm"; "aac" -> "mp4"; else -> null }
            return StreamInfo(
                url = s.url,
                headers = s.headers,
                expiresAtMs = s.expiresAtMs,
                mimeType = s.mimeType,
                codec = codec,
                container = container,
                sampleRateHz = when (codec) { "opus" -> 48_000; "aac" -> 44_100; else -> null },
                bitDepth = null,
                bitrateKbps = ITAG_KBPS[s.itag],
                loudnessDb = s.loudnessDb,
                contentLength = s.contentLength,
                sourceId = SourceIds.YOUTUBE,
                // Même clé qu'avant l'arrivée des sources : le cache de lecture déjà rempli reste valable.
                cacheKey = "${s.videoId}.${s.itag}",
                itag = s.itag,
            )
        }
    }
}
