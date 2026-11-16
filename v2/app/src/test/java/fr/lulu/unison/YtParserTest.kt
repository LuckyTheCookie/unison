package fr.lulu.unison

import fr.lulu.unison.youtube.AlbumItem
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.PlaylistItem
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YtParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réponses réelles de YouTube Music, enregistrées puis allégées (src/test/resources/yt). */
class YtParserTest {
    private fun load(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResource("/yt/$name.json")!!.readText())

    @Test
    fun searchSongs() {
        val items = YtParser.searchItems(load("search_songs"))
        assertEquals(20, items.size)
        val first = (items.first() as SongItem).track
        assertEquals("khnokW3Mw24", first.id)
        assertEquals("Instant Crush (feat. Julian Casablancas)", first.title)
        assertEquals("Daft Punk et Julian Casablancas", first.artist)
        assertEquals("Random Access Memories", first.album)
        assertEquals(338_000, first.durationMs)
        assertTrue(first.cover.endsWith("=w544-h544-l90-rj"))
        assertTrue(items.all { it is SongItem && it.track.artist.isNotBlank() && it.track.durationMs > 0 })
    }

    @Test
    fun searchAlbumsArtistsPlaylists() {
        val album = YtParser.searchItems(load("search_albums")).first() as AlbumItem
        assertEquals("MPREb_K8qWMWVqXGi", album.browseId)
        assertEquals("Random Access Memories", album.title)
        assertEquals("Album • Daft Punk • 2013", album.subtitle)

        val artist = YtParser.searchItems(load("search_artists")).first() as ArtistItem
        assertEquals("UCRr1xG_2WIDs18a6cIiCxeA", artist.browseId)
        assertEquals("Daft Punk", artist.title)

        val playlists = YtParser.searchItems(load("search_playlists"))
        assertEquals(20, playlists.size)
        assertTrue(playlists.all { it is PlaylistItem && it.browseId.startsWith("VL") })
    }

    @Test
    fun suggestions() {
        val s = YtParser.suggestions(load("suggestions"))
        assertEquals("daft punk", s.first())
        assertTrue(s.size >= 5)
    }

    @Test
    fun home() {
        val page = YtParser.sectionsPage(load("home"))
        assertEquals(listOf("Sélection rapide", "Playlists de la communauté populaires", "100% hits"), page.sections.map { it.title })
        val quick = page.sections.first().items
        assertEquals(20, quick.size)
        val t = (quick.first() as SongItem).track
        assertEquals("Mariah Carey", t.artist)
        assertEquals("Merry Christmas", t.album)
        assertTrue(page.sections[1].items.all { it is PlaylistItem })
        assertTrue(page.continuation != null)
    }

    @Test
    fun album() {
        val page = YtParser.collection(load("album"), "MPREb_K8qWMWVqXGi")
        assertEquals("Random Access Memories", page.title)
        assertEquals("Daft Punk", page.artist)
        assertEquals("UCRr1xG_2WIDs18a6cIiCxeA", page.artistId)
        assertEquals("Album • 2013", page.subtitle)
        assertTrue(page.isAlbum)
        assertEquals(13, page.tracks.size)
        val t = page.tracks.first()
        assertEquals("IluRBvnYMoY", t.id)
        assertEquals("Give Life Back to Music", t.title)
        assertEquals("Daft Punk", t.artist)
        assertEquals("Random Access Memories", t.album)
        assertEquals(276_000, t.durationMs)
        assertEquals(page.thumbnail, t.cover)
        assertEquals("Autres versions", page.related.first().title)
    }

    @Test
    fun playlist() {
        val page = YtParser.collection(load("playlist"), "VLRDCLAK5uy_mhri0dZDOPMOmDRxoIPN_9Z0bwutZZVe8")
        assertEquals("Hits 90", page.title)
        assertEquals(30, page.tracks.size)
        val t = page.tracks.first()
        assertEquals("AzaTyxMduH4", t.id)
        assertEquals("Céline Dion", t.artist)
        assertEquals(251_000, t.durationMs)
        assertTrue(t.cover.isNotBlank())
    }

    @Test
    fun artist() {
        val page = YtParser.artist(load("artist"))
        assertEquals("Daft Punk", page.name)
        assertTrue(page.thumbnail.isNotBlank())
        assertTrue(page.listeners.contains("auditeurs"))
        val titles = page.sections.map { it.title }
        assertTrue(titles.containsAll(listOf("Top des titres", "Albums", "Singles et EP")))
        val top = page.sections.first { it.title == "Top des titres" }
        assertEquals("khnokW3Mw24", (top.items.first() as SongItem).track.id)
        assertEquals("MUSIC_PAGE_TYPE_PLAYLIST", top.more?.pageType)
        val albums = page.sections.first { it.title == "Albums" }
        assertTrue(albums.items.all { it is AlbumItem })
        assertEquals("MPADUCRr1xG_2WIDs18a6cIiCxeA", albums.more?.browseId)
        assertTrue(albums.more?.params != null)
    }

    @Test
    fun radio() {
        val tracks = YtParser.radio(load("next_song"), "khnokW3Mw24")
        assertTrue(tracks.size >= 20)
        assertTrue(tracks.none { it.id == "khnokW3Mw24" })
        val t = tracks.first()
        assertEquals("Rgrt_8mXrK8", t.id)
        assertEquals("Daft Punk, Pharrell Williams et Nile Rodgers", t.artist)
        assertEquals(249_000, t.durationMs)
        assertTrue(tracks.all { it.title.isNotBlank() && it.artist.isNotBlank() && it.cover.isNotBlank() })
        // Radio lancée depuis un clip : pas d'album, mais un artiste quand même.
        val fromVideo = YtParser.radio(load("next_radio"), "fJ9rUzIMcZQ")
        assertTrue(fromVideo.size >= 20)
        assertEquals("Queen", fromVideo.first { it.id == "kijpcUv-b8M" }.artist)
    }

    @Test
    fun durations() {
        assertEquals(0, YtParser.parseDuration(""))
        assertEquals(65_000, YtParser.parseDuration("1:05"))
        assertEquals(3_723_000, YtParser.parseDuration("1:02:03"))
    }
}
