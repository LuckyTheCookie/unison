package fr.lulu.unison

import fr.lulu.unison.library.Discover
import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.PlaylistItem
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YtParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverTest {
    private fun song(id: String) = SongItem(Track(id, "Titre $id", "Artiste"))
    private fun artist(id: String) = ArtistItem(id, "Artiste $id", "", "")
    private fun playlist(id: String) = PlaylistItem(id, "Playlist $id", "", "")

    private fun seed(id: String, songs: List<String>, artists: List<String>, playlists: List<String>) = Discover.Seed(
        Track(id, "Seed $id", "A"),
        listOf(
            Section("Découvrez également", songs.map { song(it) }),
            Section("Playlists recommandées", playlists.map { playlist(it) }),
            Section("Autres performances", listOf(song("other"))),
            Section("Artistes similaires", artists.map { artist(it) }),
            Section("Seed", listOf(fr.lulu.unison.youtube.AlbumItem("MPRE", "Album", "", ""))),
        ),
    )

    @Test fun onlyWhatYouDoNotKnowYet() {
        val seeds = listOf(
            seed("s1", listOf("k1", "n1", "n2", "n3", "n4", "s1"), listOf("UC1", "UC2", "UCf"), listOf("P1", "Psaved")),
            seed("s2", listOf("n1", "m1", "m2"), listOf("UC2", "UC3"), listOf("P2", "P3")),
        )
        val got = Discover.build(seeds, known = setOf("k1"), followed = setOf("UCf"))
        assertEquals(listOf("Parce que tu écoutes Seed s1", "Artistes à découvrir"), got.map { it.title })
        // Ni titre connu, ni le titre de départ ; « n1 » déjà proposé n'est pas répété (s2 n'a donc pas assez de titres).
        assertEquals(listOf("n1", "n2", "n3", "n4"), got[0].items.map { (it as SongItem).track.id })
        // UC2 est proposé par les deux titres de départ : il passe devant ; l'artiste suivi est écarté.
        assertEquals(listOf("UC2", "UC1", "UC3"), got[1].items.map { (it as ArtistItem).browseId })
    }

    @Test fun relatedTabIsFoundInTheWatchPage() {
        val json = Json.parseToJsonElement(
            """{"contents":{"tabs":[{"tabRenderer":{"title":"À suivre"}},
              {"tabRenderer":{"title":"Paroles","endpoint":{"browseEndpoint":{"browseId":"MPLYt_x"}}}},
              {"tabRenderer":{"title":"Similaires","endpoint":{"browseEndpoint":{"browseId":"MPTRt_x"}}}}]}}""",
        )
        assertEquals("MPTRt_x", YtParser.relatedId(json))
        assertTrue(YtParser.relatedId(Json.parseToJsonElement("{}")) == null)
    }

    @Test fun realRelatedPageGivesSongsPlaylistsAndArtists() {
        val sections = YtParser.sections(Json.parseToJsonElement(javaClass.getResource("/yt/related.json")!!.readText()))
            .filter { it.items.isNotEmpty() }
        assertTrue(sections.first().items.size >= 10 && sections.first().items.all { it is SongItem })
        assertTrue(sections.any { s -> s.items.size >= 5 && s.items.all { it is PlaylistItem } })
        assertTrue(sections.any { s -> s.items.size >= 5 && s.items.all { it is ArtistItem } })
        val got = Discover.build(listOf(Discover.Seed(Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley"), sections)), emptySet(), emptySet())
        assertEquals(listOf("Parce que tu écoutes Never Gonna Give You Up", "Artistes à découvrir"), got.map { it.title })
    }
}
