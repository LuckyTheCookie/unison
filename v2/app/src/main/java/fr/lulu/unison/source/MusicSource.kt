package fr.lulu.unison.source

import fr.lulu.unison.model.Track

/** État d'une source, tel que le montre l'écran des réglages. */
sealed interface SourceHealth {
    data object Ok : SourceHealth
    /** Serveur injoignable ou en panne : la source est gardée, on réessaiera. */
    data class Unreachable(val reason: String) : SourceHealth
    /** La source répond mais refuse (format inconnu, accès refusé, réglage invalide). */
    data class Rejected(val reason: String) : SourceHealth
}

/**
 * Une source de flux audio. Le catalogue (accueil, artistes, albums, radio) reste celui de YouTube Music :
 * une source sait seulement chercher des titres et donner leur flux.
 */
interface MusicSource {
    /** [SourceIds.YOUTUBE] ou l'identifiant de la configuration de la source importée. */
    val id: String
    val displayName: String

    suspend fun health(): SourceHealth

    /** Titres trouvés pour [query] ; leurs identifiants sont ceux de l'application (préfixés pour une source importée). */
    suspend fun search(query: String, limit: Int): List<Track>

    /** Flux du titre [trackId] (identifiant propre à la source), ou null quand la source ne l'a pas. */
    suspend fun stream(trackId: String, request: StreamRequest): StreamInfo?

    fun invalidate(trackId: String)
}
