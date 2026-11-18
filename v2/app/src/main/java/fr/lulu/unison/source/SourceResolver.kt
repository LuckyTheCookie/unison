package fr.lulu.unison.source

/** D'où vient le son d'un titre : le lecteur et les téléchargements ne connaissent que cette interface. */
interface StreamResolver {
    /** Flux de [ref] ; lève [StreamUnavailableException] quand aucune source ne peut le jouer. */
    suspend fun resolve(ref: TrackRef, request: StreamRequest = StreamRequest.Best, purpose: Purpose = Purpose.PLAY): StreamInfo

    /** Oublie le flux connu de [id] (adresse expirée) ; la prochaine résolution le redemande. */
    fun invalidate(id: String)

    enum class Purpose { PLAY, DOWNLOAD }
}

/** Résolution des flux : YouTube Music seul. */
class SourceResolver(private val youtube: MusicSource) : StreamResolver {
    override suspend fun resolve(ref: TrackRef, request: StreamRequest, purpose: StreamResolver.Purpose): StreamInfo =
        youtube.stream(ref.id, request) ?: throw StreamUnavailableException("Titre indisponible")

    override fun invalidate(id: String) = youtube.invalidate(id)
}

/** Demande de flux correspondant au réglage de qualité : « Économie de données » plafonne le débit. */
fun fr.lulu.unison.youtube.Quality.toRequest(): StreamRequest = when (this) {
    fr.lulu.unison.youtube.Quality.HIGH -> StreamRequest.Best
    fr.lulu.unison.youtube.Quality.LOW -> StreamRequest.Capped(LOW_KBPS)
}

/** Plafond de l'économie de données, en kbps. */
const val LOW_KBPS = 96
