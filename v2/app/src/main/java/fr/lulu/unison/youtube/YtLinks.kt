package fr.lulu.unison.youtube

/** Reconnaît les liens YouTube et YouTube Music partagés depuis une autre application. */
object YtLinks {
    private val id = "[A-Za-z0-9_-]{11}"
    private val patterns = listOf(
        Regex("""(?:music\.|www\.|m\.)?youtube\.com/watch\?(?:[^\s#]*&)?v=($id)"""),
        Regex("""youtu\.be/($id)"""),
        Regex("""(?:www\.|m\.)?youtube\.com/(?:shorts|embed|live)/($id)"""),
    )

    fun videoId(text: String): String? = patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
}
