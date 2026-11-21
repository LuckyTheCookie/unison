package fr.lulu.unison.youtube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * Découpe les lectures sans fin annoncée (« bytes=0- ») en requêtes bornées : YouTube bride les premières
 * à environ 32 Ko/s (à peine plus que le débit du titre), alors qu'il sert les secondes à pleine vitesse.
 * Sans cela, télécharger un titre prend plusieurs minutes et le suivant se prépare au compte-gouttes.
 *
 * La taille réelle du fichier est lue dans l'en-tête Content-Range de la première réponse : [open] renvoie
 * la même longueur qu'une requête ordinaire, et aucune requête ne demande d'octet au-delà de la fin.
 */
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    /** Adresses à découper ; les autres passent telles quelles, en une seule requête. */
    private val chunked: (Uri) -> Boolean = { true },
) : DataSource {
    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
        private val chunked: (Uri) -> Boolean = { true },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunkBytes, chunked)
    }

    /** Requête en cours transmise telle quelle (adresse qui n'est pas à découper). */
    private var direct = false

    private var spec: DataSpec? = null
    /** Position absolue du prochain octet à lire. */
    private var position = 0L
    /** Fin (exclue) de ce qui est demandé, si on la connaît. */
    private var end = C.LENGTH_UNSET.toLong()
    /** Octets demandés et lus dans la requête en cours. */
    private var chunkLength = 0L
    private var chunkRead = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        direct = !chunked(dataSpec.uri)
        if (direct) return upstream.open(dataSpec)
        // Déjà bornée et courte : rien à découper.
        if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length <= chunkBytes) {
            end = dataSpec.position + dataSpec.length
            chunkLength = dataSpec.length
            chunkRead = 0
            return upstream.open(dataSpec)
        }
        openChunk(min(chunkBytes, if (dataSpec.length == C.LENGTH_UNSET.toLong()) chunkBytes else dataSpec.length))
        val total = documentSize()
        end = when {
            total == C.LENGTH_UNSET.toLong() -> if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else dataSpec.position + dataSpec.length
            dataSpec.length == C.LENGTH_UNSET.toLong() -> total
            else -> min(total, dataSpec.position + dataSpec.length)
        }
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else (end - position).coerceAtLeast(0)
    }

    private fun openChunk(length: Long) {
        val s = spec ?: error("Source fermée")
        chunkLength = length
        chunkRead = 0
        upstream.open(s.subrange(position - s.position, length))
    }

    /** Taille totale du fichier d'après la réponse en cours (« bytes 0-1023/4567 »). */
    private fun documentSize(): Long {
        val headers = upstream.responseHeaders
        val range = headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
        return if (range.isNullOrBlank()) C.LENGTH_UNSET.toLong() else HttpUtil.getDocumentSize(range)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (direct) return upstream.read(buffer, offset, length)
        while (true) {
            val left = if (end == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else end - position
            if (left <= 0) return C.RESULT_END_OF_INPUT
            val n = upstream.read(buffer, offset, min(length.toLong(), left).toInt())
            if (n != C.RESULT_END_OF_INPUT) {
                position += n
                chunkRead += n
                return n
            }
            // Fin de la requête en cours : fin du fichier si elle a rendu moins que demandé, sinon on enchaîne.
            if (chunkRead < chunkLength) return C.RESULT_END_OF_INPUT
            upstream.close()
            openChunk(min(chunkBytes, left))
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }

    companion object {
        /** 10 Mo par requête : un titre entier tient d'ordinaire en une seule. */
        const val DEFAULT_CHUNK_BYTES = 10L * 1024 * 1024

        /**
         * Seul YouTube bride les requêtes sans fin : les flux des sources importées sont lus d'une traite. Les
         * découper multiplierait les requêtes vers leur serveur, qui finit par répondre 429 au milieu du titre.
         */
        fun isYouTube(uri: Uri): Boolean = uri.host?.lowercase()?.let { it == "googlevideo.com" || it.endsWith(".googlevideo.com") } == true
    }
}
