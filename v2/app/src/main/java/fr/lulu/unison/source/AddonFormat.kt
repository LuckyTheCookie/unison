package fr.lulu.unison.source

import fr.lulu.unison.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * Le protocole « addon » : un serveur HTTP qui répond en JSON à trois requêtes GET.
 *
 * - `GET {base}/manifest.json` : `{"id", "name", "version", "resources": ["search","stream"], "allowDownloads",
 *   "settings": [{"key":"quality","default":…,"options":[{"value","label"}]}]}`
 * - `GET {base}/search?q=…` : `{"tracks": [{"id","title","artist","album","duration","artworkURL",…}]}`
 * - `GET {base}/stream/{id}` : `{"url", "codec", "container", "manifest", "sampleRate", "bitDepth", "bitrate", "encrypted"}`
 *
 * Tout est lu avec tolérance (champs inconnus ignorés, drapeaux 0/1/"true", fréquence en Hz ou kHz,
 * débit en bit/s ou kbps) mais borné : tailles, longueurs et nombres d'éléments ont un plafond.
 * Aucune donnée reçue n'est exécutée.
 */
object AddonFormat {
    const val MAX_TRACKS = 50
    private const val MAX_ID = 256
    private const val MAX_TEXT = 300
    private const val MAX_LABEL = 80
    private const val MAX_HEADER_VALUE = 512
    private const val MAX_DURATION_MS = 24 * 3_600_000L

    /** En-têtes qu'une source peut demander d'envoyer avec son flux ; tous les autres sont ignorés. */
    val ALLOWED_HEADERS = setOf("user-agent", "referer", "origin", "accept", "accept-language")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- Manifest ---

    data class Manifest(
        val id: String,
        val name: String,
        val version: String,
        val resources: List<String>,
        val allowDownloads: Boolean,
        val qualityOptions: List<QualityOption>,
        val defaultQuality: String?,
        val defaults: Map<String, String>,
        /** La source exige une authentification propre à une autre application : non prise en charge. */
        val proprietaryAuth: Boolean,
    ) {
        val hasStream: Boolean get() = resources.isEmpty() || "stream" in resources
    }

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Invalid(val reason: String) : Parsed<Nothing>
    }

    fun parseManifest(text: String): Parsed<Manifest> {
        val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Parsed.Invalid("Ce n'est pas un manifest de source (JSON attendu)")
        if (o.containsKey("modules") || o.keys.any { it.startsWith("category:") }) {
            return Parsed.Invalid("Index de modules à exécuter : non pris en charge (Unison n'exécute aucun code distant)")
        }
        val id = o.str("id")?.take(MAX_ID)?.takeIf { it.isNotBlank() } ?: return Parsed.Invalid("Manifest sans identifiant (« id »)")
        val resources = (o["resources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe()?.lowercase() }?.take(16).orEmpty()
        if (resources.isNotEmpty() && "search" !in resources) return Parsed.Invalid("La source ne propose pas la recherche (« search »)")
        val settings = (o["settings"] as? JsonArray)?.take(16).orEmpty().mapNotNull { it as? JsonObject }
        val defaults = LinkedHashMap<String, String>()
        var options = emptyList<QualityOption>()
        var defaultQuality: String? = null
        for (s in settings) {
            val key = s.str("key")?.takeIf { KEY.matches(it) } ?: continue
            val def = s.str("default")?.take(MAX_LABEL)
            if (def != null) defaults[key] = def
            if (key == "quality") {
                options = (s["options"] as? JsonArray)?.take(12).orEmpty().mapNotNull { e ->
                    val opt = e as? JsonObject ?: return@mapNotNull null
                    val value = opt.str("value")?.take(MAX_LABEL)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    QualityOption(value, clean(opt.str("label") ?: value, MAX_LABEL))
                }
                defaultQuality = def
            }
        }
        return Parsed.Ok(
            Manifest(
                id = id,
                name = clean(o.str("name") ?: id, MAX_LABEL).ifBlank { id },
                version = clean(o.str("version").orEmpty(), 32),
                resources = resources,
                allowDownloads = o.flag("allowDownloads") ?: true,
                qualityOptions = options,
                defaultQuality = defaultQuality,
                defaults = defaults,
                proprietaryAuth = o.flag("bitchordAuth") == true || o.flag("requiresAuth") == true,
            ),
        )
    }

    // --- Recherche ---

    /** Une ligne de résultat : le titre (identifiant propre à la source) et, parfois, son flux direct. */
    data class Row(val trackId: String, val track: Track, val streamUrl: String?, val stream: JsonObject)

    /** Titres d'une réponse de recherche ; les lignes sans identifiant ou sans titre sont ignorées. */
    fun parseSearch(text: String, configId: String): Parsed<List<Row>> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Parsed.Invalid("Réponse de recherche illisible")
        val items = when (root) {
            is JsonObject -> root["tracks"] as? JsonArray ?: (root["results"] as? JsonArray)
            is JsonArray -> root
            else -> null
        } ?: return if (root is JsonObject && root.str("error") != null) Parsed.Invalid("La source a répondu par une erreur")
        else Parsed.Invalid("Réponse de recherche sans liste de titres (« tracks »)")
        val rows = items.asSequence().mapNotNull { it as? JsonObject }.mapNotNull { row(it, configId) }
            .distinctBy { it.trackId }.take(MAX_TRACKS).toList()
        return Parsed.Ok(rows)
    }

    private fun row(o: JsonObject, configId: String): Row? {
        val trackId = o.str("id")?.takeIf { validTrackId(it) } ?: return null
        val title = o.str("title")?.let { clean(it, MAX_TEXT) }?.takeIf { it.isNotBlank() } ?: return null
        val artist = when (val a = o["artist"] ?: o["artists"]) {
            is JsonPrimitive -> a.contentOrNullSafe()
            is JsonArray -> a.take(6).mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() ?: (it as? JsonObject)?.str("name") }.joinToString(", ")
            is JsonObject -> a.str("name")
            else -> null
        }.orEmpty()
        val album = when (val a = o["album"]) {
            is JsonPrimitive -> a.contentOrNullSafe()
            is JsonObject -> a.str("title") ?: a.str("name")
            else -> null
        }.orEmpty()
        val durationMs = o.num("durationMs")?.toLong()
            ?: o.num("duration")?.let { (it * 1000).toLong() }
            ?: 0L
        val cover = listOf("artworkURL", "albumArtworkURL", "artwork", "cover", "thumbnail")
            .firstNotNullOfOrNull { k -> o.str(k)?.takeIf { it.startsWith("https://") && it.length <= 2_048 } }.orEmpty()
        val track = Track(
            id = SourceTrackId.make(configId, trackId),
            title = title,
            artist = clean(artist, MAX_TEXT),
            album = clean(album, MAX_TEXT),
            cover = cover,
            durationMs = durationMs.coerceIn(0, MAX_DURATION_MS),
            explicit = o.flag("explicit") == true,
        )
        return Row(trackId, track, o.str("streamURL") ?: o.str("streamUrl"), o)
    }

    /** Identifiant de titre acceptable : borné, sans caractère de contrôle, et jamais « . » ni « .. » (chemin). */
    fun validTrackId(id: String) = id.isNotBlank() && id.length <= MAX_ID && id.none(Char::isISOControl) && id != "." && id != ".."

    // --- Flux ---

    /** Réponse de `/stream` (ou ligne de recherche portant son flux), avant les vérifications de sécurité. */
    data class Stream(
        val url: String?,
        val error: String?,
        val encrypted: Boolean,
        val transport: Transport,
        val codec: String?,
        val container: String?,
        val mimeType: String?,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val bitrateKbps: Int?,
        val headers: Map<String, String>,
        val expiresAtMs: Long?,
    )

    fun parseStream(text: String): Parsed<Stream> {
        val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Parsed.Invalid("Réponse de flux illisible")
        return Parsed.Ok(stream(o, o.str("url")))
    }

    fun stream(o: JsonObject, url: String?): Stream {
        // Texte libre décrivant la qualité (« [Service] HI_RES_LOSSLESS 24-bit 96 kHz »), à défaut de champs chiffrés.
        val text = listOf("streamQuality", "quality", "audioQuality", "label").mapNotNull { o.str(it) }.joinToString(" ").take(400)
        val manifestKind = (o.str("manifest") ?: o.str("mediaType") ?: o.str("format")).orEmpty().lowercase()
        val path = url?.substringBefore('?')?.lowercase().orEmpty()
        val transport = when {
            manifestKind in setOf("hls", "m3u8") || "mpegurl" in manifestKind || path.endsWith(".m3u8") -> Transport.HLS
            manifestKind in setOf("dash", "mpd") || "dash+xml" in manifestKind || path.endsWith(".mpd") -> Transport.DASH
            else -> Transport.PROGRESSIVE
        }
        val mime = o.str("mimeType")?.lowercase()?.take(100)
        val codec = listOf("codec", "fileCodec", "format", "audioCodec").firstNotNullOfOrNull { k -> o.str(k)?.let(::codecOf) }
            ?: mime?.let(::codecOf) ?: codecOf(path.substringAfterLast('.', ""))
        val headers = (o["headers"] as? JsonObject)?.entries.orEmpty().take(8).mapNotNull { (k, v) ->
            val value = (v as? JsonPrimitive)?.contentOrNullSafe() ?: return@mapNotNull null
            if (k.lowercase() !in ALLOWED_HEADERS || value.length > MAX_HEADER_VALUE || value.any { it == '\r' || it == '\n' }) null else k to value
        }.toMap()
        val drm = listOf("drm", "license", "licenseUrl", "licenseURL", "keySystem", "widevine").any { k -> declared(o, k) }
        return Stream(
            url = url?.trim()?.takeIf { it.isNotEmpty() },
            error = o.str("error")?.let { clean(it, 120) },
            encrypted = drm || declared(o, "encrypted"),
            transport = transport,
            codec = codec,
            container = (o.str("container") ?: o.str("containerFormat"))?.lowercase()?.take(20),
            mimeType = mime,
            sampleRateHz = sampleRate(o["sampleRate"]) ?: sampleRate(o["sampleRateHz"]) ?: SAMPLE_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { khz(it) },
            bitDepth = bitDepth(o["bitDepth"]) ?: BITS_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8..32 },
            bitrateKbps = bitrate(o["bitrate"]) ?: bitrate(o["bitrateKbps"]) ?: KBPS_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8..20_000 },
            headers = headers,
            expiresAtMs = (o.num("expiresAt") ?: o.num("expires"))?.let { if (it < 1e11) (it * 1000).toLong() else it.toLong() },
        )
    }

    // --- Nombres tolérants ---

    /** Fréquence d'échantillonnage : 44.1 ou « 44,1 kHz » → 44 100 Hz ; 96000 → 96 000 Hz. */
    fun sampleRate(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        p.doubleOrNull?.let { return if (it < 1000) khz(it) else it.roundToInt().takeIf { hz -> hz in 8_000..768_000 } }
        val s = p.contentOrNullSafe()?.lowercase()?.replace(',', '.') ?: return null
        val n = NUMBER.find(s)?.value?.toDoubleOrNull() ?: return null
        return if ("khz" in s || n < 1000) khz(n) else n.roundToInt().takeIf { it in 8_000..768_000 }
    }

    /** Débit : 320 → 320 kbps ; 1411200 (bit/s) → 1411 kbps ; « 1.4 Mbps » → 1400 kbps. */
    fun bitrate(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        p.doubleOrNull?.let { return kbps(it, null) }
        val s = p.contentOrNullSafe()?.lowercase()?.replace(',', '.') ?: return null
        val n = NUMBER.find(s)?.value?.toDoubleOrNull() ?: return null
        val unit = when {
            "mbps" in s || "mbit" in s -> "m"
            "kbps" in s || "kbit" in s || "kb/s" in s -> "k"
            "bps" in s || "bit/s" in s -> "b"
            else -> null
        }
        return kbps(n, unit)
    }

    private fun kbps(n: Double, unit: String?): Int? = when (unit) {
        "m" -> n * 1000
        "k" -> n
        "b" -> n / 1000
        else -> if (n > 3000) n / 1000 else n
    }.roundToInt().takeIf { it in 8..20_000 }

    fun bitDepth(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        val n = p.doubleOrNull?.toInt() ?: p.contentOrNullSafe()?.let { NUMBER.find(it)?.value?.toDoubleOrNull()?.toInt() } ?: return null
        return n.takeIf { it in 8..32 }
    }

    private fun khz(n: Double): Int? = (n * 1000).roundToInt().takeIf { it in 8_000..768_000 }

    /** Codec d'après un nom, une extension ou un type MIME ; null si inconnu. */
    fun codecOf(raw: String): String? {
        val s = raw.lowercase().trim()
        if (s.isEmpty()) return null
        return when {
            "flac" in s -> "flac"
            "alac" in s -> "alac"
            "eac3" in s || "ec-3" in s || "e-ac-3" in s -> "eac3"
            "ac3" in s || "ac-3" in s -> "ac3"
            "opus" in s -> "opus"
            "vorbis" in s -> "vorbis"
            "mp3" in s || "mpeg" == s.substringAfter('/') || s == "audio/mpeg" -> "mp3"
            "aac" in s || "mp4a" in s || "m4a" in s -> "aac"
            "wav" in s || "pcm" in s || "aiff" in s -> "pcm"
            else -> null
        }
    }

    // --- Outils ---

    private val KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,31}$")
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")
    private val KBPS_TEXT = Regex("""(\d{2,5})\s*kbps""", RegexOption.IGNORE_CASE)
    private val SAMPLE_TEXT = Regex("""(\d{2,3}(?:[.,]\d)?)\s*khz""", RegexOption.IGNORE_CASE)
    private val BITS_TEXT = Regex("""(\d{1,2})\s*-?\s*bits?\b""", RegexOption.IGNORE_CASE)

    /** Le champ est présent et ne dit pas « non » (false, 0, "none"…) : un objet ou une valeur inconnue comptent comme « oui ». */
    private fun declared(o: JsonObject, key: String): Boolean {
        val e = o[key] ?: return false
        if (e is JsonNull) return false
        if (e is JsonPrimitive && e.content.trim().lowercase() in setOf("", "none", "no", "false", "0", "off", "clear")) return false
        return o.flag(key) != false
    }

    private fun clean(s: String, max: Int) = s.filterNot(Char::isISOControl).trim().take(max)

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.num(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNullSafe()?.toDoubleOrNull()
    }

    /** Drapeau tolérant : true/false, 1/0, "true"/"false"/"yes"/"no". Null quand absent ou illisible. */
    fun JsonObject.flag(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.booleanOrNull?.let { return it }
        p.doubleOrNull?.let { return it != 0.0 }
        return when (p.content.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0", "" -> false
            else -> null
        }
    }
}

/** Identifiants des titres d'une source importée : `src:<configuration>::<titre>`. */
object SourceTrackId {
    private const val PREFIX = "src:"
    private const val SEP = "::"

    fun make(configId: String, trackId: String) = "$PREFIX$configId$SEP$trackId"

    fun isSource(id: String) = id.startsWith(PREFIX) && id.contains(SEP)

    /** (configuration, titre) ou null pour un identifiant YouTube. */
    fun parse(id: String): Pair<String, String>? {
        if (!id.startsWith(PREFIX)) return null
        val rest = id.substring(PREFIX.length)
        val i = rest.indexOf(SEP)
        if (i <= 0 || i + SEP.length >= rest.length) return null
        return rest.substring(0, i) to rest.substring(i + SEP.length)
    }
}
