package fr.lulu.unison

import fr.lulu.unison.model.Track
import fr.lulu.unison.source.SourceIds
import fr.lulu.unison.source.SourceResolver
import fr.lulu.unison.source.StreamInfo
import fr.lulu.unison.source.TrackRef
import fr.lulu.unison.source.TrackUri
import fr.lulu.unison.source.YouTubeSource
import fr.lulu.unison.youtube.ResolvedStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrackUriTest {
    @Test fun roundTripKeepsEverything() {
        val t = Track("src:ab12::album/42?x=1&y", "Été — « live »", "A & B", "L'album", "", 201_000)
        val ref = TrackUri.parse(TrackUri.build(t))
        assertEquals(TrackRef(t.id, t.title, t.artist, t.album, t.durationMs), ref)
    }

    @Test fun oldDownloadRequestsStillParse() {
        // Téléchargements mis en file par la 2.2 : l'identifiant YouTube est le dernier segment du chemin.
        assertEquals(TrackRef("khnokW3Mw24"), TrackUri.parse("unison://track/khnokW3Mw24"))
        assertNull(TrackUri.parse("https://example.test/track/x"))
        assertNull(TrackUri.parse("unison://track?ti=sans-id"))
    }

    @Test fun youtubeIdHasNoQueryNoise() {
        val uri = TrackUri.build(Track("khnokW3Mw24", "T", "A"))
        assertEquals("unison://track?id=khnokW3Mw24&ti=T&ar=A", uri)
    }
}

class YouTubeSourceTest {
    @Test fun describesYoutubeFormatsAndKeepsOldCacheKey() {
        val s = YouTubeSource.describe(ResolvedStream("vid", "https://r1.example.test/x?sig=1", emptyMap(), 251, "audio/webm; codecs=\"opus\"", 1, -3.0, 100))
        assertEquals("opus", s.codec)
        assertEquals("webm", s.container)
        assertEquals(160, s.bitrateKbps)
        assertEquals(48_000, s.sampleRateHz)
        assertEquals(SourceIds.YOUTUBE, s.sourceId)
        assertEquals("vid.251", s.cacheKey)
        val aac = YouTubeSource.describe(ResolvedStream("vid", "https://r1.example.test/x", emptyMap(), 140, "audio/mp4; codecs=\"mp4a.40.2\"", 1, null, null))
        assertEquals("aac", aac.codec)
        assertEquals(128, aac.bitrateKbps)
    }

    @Test fun urlsAreNeverPrinted() {
        val s = YouTubeSource.describe(ResolvedStream("vid", "https://r1.example.test/videoplayback?sig=SECRET", emptyMap(), 251, null, 1, null, null))
        assertFalse(s.toString().contains("SECRET"))
        assertEquals("https://r1.example.test/***", StreamInfo.redact("https://user:pw@r1.example.test/a?b"))
        assertEquals("***", StreamInfo.redact("pas une adresse"))
    }
}

class SourceRepositoryTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    private fun config(url: String, name: String = "Ma source") =
        fr.lulu.unison.source.SourceConfig(fr.lulu.unison.source.SourceConfig.idFor(url), "org.exemple.source", name, "1.0", url)

    @Test fun keepsSourcesAcrossRestarts() {
        val file = java.io.File(tmp.root, "sources.json")
        val repo = fr.lulu.unison.source.SourceRepository(file)
        assertEquals(emptyList<Any>(), repo.sources.value)
        val a = config("https://a.example.test")
        repo.put(a)
        repo.put(config("https://b.example.test", "B"))
        repo.update(a.id) { it.copy(pickedQuality = "lossless") }
        val again = fr.lulu.unison.source.SourceRepository(file)
        assertEquals(2, again.sources.value.size)
        assertEquals("lossless", again.get(a.id)?.pickedQuality)
        again.remove(a.id)
        assertNull(fr.lulu.unison.source.SourceRepository(file).get(a.id))
    }

    @Test fun sameAddressSameIdOnEveryPhone() {
        assertEquals(config("https://a.example.test").id, config("https://A.example.test").id)
        assertFalse(config("https://a.example.test").id == config("https://b.example.test").id)
        assertEquals(11, config("https://a.example.test").id.length)
    }

    @Test fun brokenFileStartsEmpty() {
        val file = java.io.File(tmp.root, "sources.json").apply { writeText("{pas du json") }
        assertEquals(0, fr.lulu.unison.source.SourceRepository(file).sources.value.size)
    }
}
