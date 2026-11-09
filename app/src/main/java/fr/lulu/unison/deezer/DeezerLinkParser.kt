package fr.lulu.unison.deezer

/** Analyse les textes partagés par Deezer (« Écoute X sur Deezer https://… »). */
object DeezerLinkParser {
    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val trackRegex = Regex("""(?:deezer\.com|deezer://www\.deezer\.com)/(?:[a-z]{2}(?:-[a-z]{2})?/)?track/(\d+)""", RegexOption.IGNORE_CASE)
    private val shortHosts = listOf("link.deezer.com", "deezer.page.link", "dzr.page.link", "dzr.fm", "deezer.lnk.to")

    /** ID du titre s'il apparaît directement dans le texte. */
    fun directTrackId(text: String): Long? = trackRegex.find(text)?.groupValues?.get(1)?.toLongOrNull()

    enum class Kind(val path: String) { Album("album"), Playlist("playlist") }

    private val collectionRegex =
        Regex("""(?:deezer\.com|deezer://www\.deezer\.com)/(?:[a-z]{2}(?:-[a-z]{2})?/)?(album|playlist)/(\d+)""", RegexOption.IGNORE_CASE)

    /** Album ou playlist désignés directement dans le texte (type + identifiant). */
    fun directCollection(text: String): Pair<Kind, Long>? {
        val m = collectionRegex.find(text) ?: return null
        val kind = if (m.groupValues[1].equals("album", ignoreCase = true)) Kind.Album else Kind.Playlist
        return m.groupValues[2].toLongOrNull()?.let { kind to it }
    }

    /** Premier lien Deezer trouvé dans le texte (court ou complet). */
    fun firstDeezerUrl(text: String): String? = urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ')', '!') }
        .firstOrNull { url -> isShortLink(url) || url.contains("deezer.com", ignoreCase = true) }

    fun isShortLink(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return shortHosts.any { host == it || host.endsWith(".$it") }
    }

    /** Vrai si le lien désigne autre chose qu'un titre (album, playlist…). */
    fun isNonTrackDeezerUrl(url: String): Boolean =
        Regex("""deezer\.com/(?:[a-z]{2}/)?(album|playlist|artist|episode|show)/""", RegexOption.IGNORE_CASE).containsMatchIn(url)
}
