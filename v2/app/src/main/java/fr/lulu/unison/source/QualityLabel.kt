package fr.lulu.unison.source

/** Ce que le décodeur reçoit réellement (lu dans le format d'ExoPlayer). */
data class DecodedFormat(
    /** Type MIME de l'échantillon : audio/flac, audio/opus, audio/mp4a-latm… */
    val mimeType: String?,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
)

/** Libellé de qualité affiché sur le lecteur. Rien n'est affiché pour une qualité standard ou inconnue. */
enum class QualityTier(val label: String) {
    HI_RES("Haute résolution"),
    LOSSLESS("Sans perte"),
    ATMOS("Dolby Atmos"),
    HIGH("Haute qualité"),
}

/**
 * Le libellé vient d'abord de ce que le décodeur reçoit, jamais seulement de ce que la source annonce :
 * un flux annoncé « sans perte » mais décodé en Opus n'affiche rien. Les informations de la source ne servent
 * qu'à compléter un nombre absent (profondeur, débit) quand le codec qu'elle annonce est bien celui décodé.
 *
 * - FLAC, ALAC, PCM : « Sans perte » ; « Haute résolution » au-delà de 16 bits ou de 48 kHz ;
 * - E-AC-3 JOC : « Dolby Atmos » ;
 * - codec avec perte à 256 kbps ou plus : « Haute qualité » ;
 * - en dessous, ou rien de mesuré : pas de libellé.
 */
object QualityLabel {
    const val HIGH_KBPS = 256

    fun of(decoded: DecodedFormat?, declared: StreamInfo? = null): QualityTier? {
        val mime = decoded?.mimeType?.lowercase() ?: return null
        val family = family(mime) ?: return null
        val declaredMatches = declared != null && declared.codec?.let { familyOfCodec(it) } == family
        return when (family) {
            "lossless" -> {
                val depth = decoded.bitDepth ?: declared?.bitDepth?.takeIf { declaredMatches }
                val rate = decoded.sampleRateHz ?: declared?.sampleRateHz?.takeIf { declaredMatches }
                if ((depth != null && depth > 16) || (rate != null && rate > 48_000)) QualityTier.HI_RES else QualityTier.LOSSLESS
            }
            "atmos" -> QualityTier.ATMOS
            else -> {
                val kbps = decoded.bitrateKbps ?: declared?.bitrateKbps?.takeIf { declaredMatches }
                if (kbps != null && kbps >= HIGH_KBPS) QualityTier.HIGH else null
            }
        }
    }

    /** Résumé technique (« FLAC · 24 bits · 96 kHz »), un champ inconnu étant omis plutôt qu'inventé. */
    fun summary(decoded: DecodedFormat?, declared: StreamInfo? = null): String? {
        val mime = decoded?.mimeType?.lowercase() ?: return null
        val name = NAMES.entries.firstOrNull { mime.endsWith(it.key) }?.value ?: return null
        val lossless = family(mime) == "lossless"
        val matches = declared?.codec?.let { familyOfCodec(it) } == family(mime)
        val depth = decoded.bitDepth ?: declared?.bitDepth?.takeIf { matches }
        val rate = decoded.sampleRateHz ?: declared?.sampleRateHz?.takeIf { matches }
        val kbps = decoded.bitrateKbps ?: declared?.bitrateKbps?.takeIf { matches }
        return listOfNotNull(
            name,
            depth?.takeIf { lossless }?.let { "$it bits" },
            rate?.let { khz(it) },
            kbps?.takeIf { !lossless }?.let { "$it kbps" },
        ).joinToString(" · ")
    }

    /** Format d'un fichier téléchargé, en court (« FLAC 24 bits · 96 kHz », « Opus 160 kbps ») ; null si inconnu. */
    fun short(codec: String?, bitrateKbps: Int?, sampleRateHz: Int?, bitDepth: Int?): String? {
        val c = codec?.lowercase() ?: return null
        val name = when (c) { "flac" -> "FLAC"; "alac" -> "ALAC"; "pcm" -> "PCM"; "opus" -> "Opus"; "aac" -> "AAC"; "mp3" -> "MP3"; "vorbis" -> "Vorbis"; "eac3" -> "E-AC-3"; else -> return null }
        return if (c in setOf("flac", "alac", "pcm")) {
            listOfNotNull(name + (bitDepth?.let { " $it bits" } ?: ""), sampleRateHz?.let { khz(it) }).joinToString(" · ")
        } else name + (bitrateKbps?.let { " $it kbps" } ?: "")
    }

    private fun khz(hz: Int): String {
        val k = hz / 1000.0
        return (if (hz % 1000 == 0) "${hz / 1000}" else String.format(java.util.Locale.FRANCE, "%.1f", k)) + " kHz"
    }

    private fun family(mime: String): String? = when {
        mime.endsWith("/flac") || mime.endsWith("/alac") || mime.endsWith("/raw") || mime.contains("wav") -> "lossless"
        mime.endsWith("eac3-joc") -> "atmos"
        mime.endsWith("/opus") || mime.endsWith("/mp4a-latm") || mime.endsWith("/mpeg") || mime.endsWith("/vorbis") ||
            mime.endsWith("/eac3") || mime.endsWith("/ac3") -> "lossy:" + mime.substringAfterLast('/')
        else -> null
    }

    private fun familyOfCodec(codec: String): String? = when (codec.lowercase()) {
        "flac", "alac", "pcm" -> "lossless"
        "opus" -> "lossy:opus"
        "aac" -> "lossy:mp4a-latm"
        "mp3" -> "lossy:mpeg"
        "vorbis" -> "lossy:vorbis"
        "eac3" -> "lossy:eac3"
        "ac3" -> "lossy:ac3"
        else -> null
    }

    private val NAMES = linkedMapOf(
        "/flac" to "FLAC", "/alac" to "ALAC", "/raw" to "PCM", "eac3-joc" to "Dolby Atmos", "/eac3" to "E-AC-3", "/ac3" to "AC-3",
        "/opus" to "Opus", "/mp4a-latm" to "AAC", "/mpeg" to "MP3", "/vorbis" to "Vorbis",
    )
}
