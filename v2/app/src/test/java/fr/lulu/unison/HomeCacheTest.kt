package fr.lulu.unison

import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.AlbumItem
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.BrowseRef
import fr.lulu.unison.youtube.HomeCache
import fr.lulu.unison.youtube.PlaylistItem
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.SongItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeCacheTest {
    @get:Rule val dir = TemporaryFolder()

    private val sections = listOf(
        Section(
            "Écoute rapide",
            listOf(
                SongItem(Track(id = "abc", title = "Titre", artist = "Artiste", durationMs = 180_000, cover = "https://x/y.jpg")),
                AlbumItem("MPREb_1", "Album", "Artiste", "https://x/a.jpg"),
            ),
            more = BrowseRef("FEmusic_more", null, "MUSIC_PAGE_TYPE_PLAYLIST"),
        ),
        Section("Mixes", listOf(PlaylistItem("VL1", "Mix", "YouTube Music", ""), ArtistItem("UC1", "Artiste", "1 M", ""))),
    )

    @Test
    fun `l'accueil gardé revient à l'identique`() = runTest {
        val cache = HomeCache(dir.root.resolve("home.json"))
        cache.write(sections)
        assertEquals(sections, cache.read())
    }

    @Test
    fun `rien de gardé ou fichier abîmé donne null`() = runTest {
        val file = dir.root.resolve("home.json")
        val cache = HomeCache(file)
        assertNull(cache.read())
        file.writeText("{pas du json")
        assertNull(cache.read())
    }
}
