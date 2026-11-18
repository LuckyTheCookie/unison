package fr.lulu.unison

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import fr.lulu.unison.youtube.ChunkedDataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Serveur factice qui se comporte comme googlevideo : il répond 206 avec Content-Range, et ne rend jamais
 * plus que ce que le fichier contient. On note chaque requête pour vérifier qu'aucune n'est sans fin.
 */
private class FakeServer(private val file: ByteArray, private val announceSize: Boolean = true) : DataSource {
    val requests = mutableListOf<Pair<Long, Long>>()
    private var pos = 0
    private var stop = 0
    private var headers: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        requests += dataSpec.position to dataSpec.length
        pos = dataSpec.position.toInt()
        val wanted = if (dataSpec.length == C.LENGTH_UNSET.toLong()) file.size.toLong() else dataSpec.position + dataSpec.length
        stop = minOf(wanted, file.size.toLong()).toInt()
        headers = if (announceSize) mapOf("content-range" to listOf("bytes $pos-${stop - 1}/${file.size}")) else emptyMap()
        // Comme OkHttpDataSource : une requête bornée annonce la longueur demandée.
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else (stop - pos).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= stop) return C.RESULT_END_OF_INPUT
        val n = minOf(length, stop - pos, 1000) // petites lectures, comme un vrai réseau
        System.arraycopy(file, pos, buffer, offset, n)
        pos += n
        return n
    }

    override fun getUri(): Uri? = null
    override fun getResponseHeaders() = headers
    override fun close() = Unit
}

class ChunkedDataSourceTest {
    private val file = ByteArray(25_000) { (it * 31 % 251).toByte() }

    private fun readAll(source: DataSource, spec: DataSpec): Pair<Long, ByteArray> {
        val length = source.open(spec)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        source.close()
        return length to out.toByteArray()
    }

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) = DataSpec(android.net.TestUri(), position, length)

    @Test fun openEndedReadIsSplitIntoBoundedRequests() {
        val server = FakeServer(file)
        val (length, bytes) = readAll(ChunkedDataSource(server, chunkBytes = 10_000), spec())
        assertEquals(25_000L, length)
        assertArrayEquals(file, bytes)
        // Jamais de requête sans fin, ni au-delà de la fin du fichier.
        assertEquals(listOf(0L to 10_000L, 10_000L to 10_000L, 20_000L to 5_000L), server.requests)
    }

    @Test fun seekInTheMiddleAndBoundedRanges() {
        val server = FakeServer(file)
        val (length, bytes) = readAll(ChunkedDataSource(server, chunkBytes = 10_000), spec(position = 12_345))
        assertEquals(25_000L - 12_345, length)
        assertArrayEquals(file.copyOfRange(12_345, 25_000), bytes)
        assertTrue(server.requests.none { it.second == C.LENGTH_UNSET.toLong() })

        // Plage bornée plus longue qu'un morceau : découpée, et arrêtée pile à la fin demandée.
        val s2 = FakeServer(file)
        val (l2, b2) = readAll(ChunkedDataSource(s2, chunkBytes = 4_000), spec(position = 1_000, length = 9_000))
        assertEquals(9_000L, l2)
        assertArrayEquals(file.copyOfRange(1_000, 10_000), b2)
        assertEquals(listOf(1_000L to 4_000L, 5_000L to 4_000L, 9_000L to 1_000L), s2.requests)

        // Plage courte : transmise telle quelle.
        val s3 = FakeServer(file)
        val (l3, b3) = readAll(ChunkedDataSource(s3, chunkBytes = 4_000), spec(position = 0, length = 100))
        assertEquals(100L, l3)
        assertArrayEquals(file.copyOfRange(0, 100), b3)
        assertEquals(listOf(0L to 100L), s3.requests)
    }

    @Test fun unknownSizeStopsAtTheFirstShortAnswer() {
        val server = FakeServer(file, announceSize = false)
        val (length, bytes) = readAll(ChunkedDataSource(server, chunkBytes = 10_000), spec())
        assertEquals(C.LENGTH_UNSET.toLong(), length)
        assertArrayEquals(file, bytes)
        assertEquals(listOf(0L to 10_000L, 10_000L to 10_000L, 20_000L to 10_000L), server.requests)
    }
}
