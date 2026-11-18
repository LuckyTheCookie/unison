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
import org.junit.Assert.assertTrue
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

class TrackMatcherTest {
    private fun c(title: String, artist: String = "Eurythmics", d: Long = 216_000) = fr.lulu.unison.source.TrackMatcher.Candidate(title, artist, d)
    private fun same(a: fr.lulu.unison.source.TrackMatcher.Candidate, b: fr.lulu.unison.source.TrackMatcher.Candidate) =
        fr.lulu.unison.source.TrackMatcher.score(a, b) != null

    @Test fun sameRecordingDespiteContext() {
        assertTrue(same(c("Sweet Dreams (Are Made of This)"), c("Sweet Dreams (Are Made of This) - Remastered")))
        assertTrue(same(c("Sweet Dreams"), c("Sweet Dreams [2005 Remaster]", "Eurythmics, Annie Lennox, Dave Stewart")))
        assertTrue(same(c("Été indien", "Joe Dassin"), c("Ete Indien", "Joe Dassin")))
        assertTrue(same(c("Titre (feat. X)", "A"), c("Titre", "A & X")))
        assertTrue(same(c("Song - Radio Edit", "A"), c("Song", "A")))
    }

    @Test fun otherVersionsAreRejected() {
        assertFalse(same(c("Sweet Dreams"), c("Sweet Dreams (Remix)")))
        assertFalse(same(c("Sweet Dreams (Live)"), c("Sweet Dreams")))
        assertFalse(same(c("Sweet Dreams"), c("Sweet Dreams - Acoustic Version")))
        assertFalse(same(c("Sweet Dreams"), c("Sweet Dreams (Slowed + Reverb)")))
        assertTrue(same(c("Live Forever", "Oasis", 276_000), c("Live Forever (Remastered)", "Oasis", 277_000)))
    }

    @Test fun artistAndDurationMatter() {
        assertFalse(same(c("Sweet Dreams", "Marilyn Manson"), c("Sweet Dreams", "Eurythmics")))
        assertTrue(same(c("Sweet Dreams", ""), c("Sweet Dreams", "Eurythmics")))
        // Écart de durée : un autre montage (163 s contre 189 s) n'est pas le même enregistrement.
        assertFalse(same(c("Titre", "A", 163_000), c("Titre", "A", 189_000)))
        assertTrue(same(c("Titre", "A", 200_000), c("Titre", "A", 203_000)))
        assertTrue(same(c("Titre", "A", 0), c("Titre", "A", 203_000)))
    }

    @Test fun picksTheClosestCandidate() {
        val target = c("Sweet Dreams (Are Made of This)")
        val list = listOf(c("Sweet Dreams (Remix)"), c("Sweet Dreams", d = 219_000), c("Sweet Dreams (Are Made of This)", d = 216_500))
        assertEquals(list[2], fr.lulu.unison.source.TrackMatcher.best(target, list) { it })
        assertNull(fr.lulu.unison.source.TrackMatcher.best(target, listOf(c("Autre chose"))) { it })
    }
}

/** Résolution multi-sources avec de fausses sources en mémoire. */
class SourceResolverTest {
    private class FakeSource(override val id: String, val tracks: List<Track>, var fail: Boolean = false, var slowMs: Long = 0) : fr.lulu.unison.source.MusicSource {
        override val displayName = id
        val streamed = mutableListOf<String>()
        var searches = 0
        override suspend fun health() = fr.lulu.unison.source.SourceHealth.Ok
        override suspend fun search(query: String, limit: Int): List<Track> {
            searches++
            if (slowMs > 0) kotlinx.coroutines.delay(slowMs)
            if (fail) throw fr.lulu.unison.source.AddonException.Unavailable("panne")
            return tracks
        }
        override suspend fun stream(trackId: String, request: fr.lulu.unison.source.StreamRequest): StreamInfo? {
            if (fail) throw fr.lulu.unison.source.AddonException.Unavailable("panne")
            streamed += trackId
            if (tracks.none { (fr.lulu.unison.source.SourceTrackId.parse(it.id)?.second ?: it.id) == trackId }) return null
            return StreamInfo(url = "https://cdn.example.test/$trackId", expiresAtMs = Long.MAX_VALUE, sourceId = id, cacheKey = "$id:$trackId")
        }
        override fun invalidate(trackId: String) = Unit
    }

    private val ytTrack = Track("ytSweet0001", "Sweet Dreams (Are Made of This)", "Eurythmics", durationMs = 216_000)
    private val youtube = FakeSource(SourceIds.YOUTUBE, listOf(ytTrack, Track("ytOther0002", "Autre", "X", durationMs = 100_000)))
    private val addon = FakeSource("s1", listOf(Track("src:s1::hr/1", "Sweet Dreams (Are Made of This) - Remastered", "Eurythmics", durationMs = 217_000)))

    private fun resolver(
        settings: fr.lulu.unison.source.ResolverSettings = fr.lulu.unison.source.ResolverSettings("s1"),
        shared: Boolean = false,
        allowDownloads: Boolean = true,
        present: Boolean = true,
    ) = SourceResolver(youtube, { id -> if (id == "s1" && present) fr.lulu.unison.source.ImportedSource(addon, allowDownloads) else null }, { settings }, { shared })

    @Test fun youtubeStaysTheDefault() = kotlinx.coroutines.runBlocking<Unit> {
        val info = resolver(fr.lulu.unison.source.ResolverSettings()).resolve(TrackRef.of(ytTrack))
        assertEquals(SourceIds.YOUTUBE, info.sourceId)
        assertEquals(0, addon.searches)
    }

    @Test fun activeSourceServesTheSameRecording() = kotlinx.coroutines.runBlocking<Unit> {
        val r = resolver()
        assertEquals("s1", r.resolve(TrackRef.of(ytTrack)).sourceId)
        assertEquals(listOf("hr/1"), addon.streamed)
        // Correspondance gardée : pas de nouvelle recherche.
        r.resolve(TrackRef.of(ytTrack))
        assertEquals(1, addon.searches)
    }

    @Test fun fallsBackToYoutubeWhenMissingFailingOrSlow() = kotlinx.coroutines.runBlocking<Unit> {
        val other = Track("ytOther0002", "Autre", "X", durationMs = 100_000)
        assertEquals(SourceIds.YOUTUBE, resolver().resolve(TrackRef.of(other)).sourceId)
        addon.fail = true
        assertEquals(SourceIds.YOUTUBE, resolver().resolve(TrackRef.of(ytTrack)).sourceId)
        addon.fail = false
        addon.slowMs = 60_000
        val started = System.currentTimeMillis()
        assertEquals(SourceIds.YOUTUBE, resolver().resolve(TrackRef.of(ytTrack)).sourceId)
        assertTrue(System.currentTimeMillis() - started < 15_000)
    }

    @Test fun noFallbackWhenTheUserSaysSo() = kotlinx.coroutines.runBlocking<Unit> {
        val strict = resolver(fr.lulu.unison.source.ResolverSettings("s1", fallbackToYouTube = false))
        val other = Track("ytOther0002", "Autre", "X", durationMs = 100_000)
        assertTrue(runCatching { strict.resolve(TrackRef.of(other)) }.exceptionOrNull() is fr.lulu.unison.source.StreamUnavailableException)
    }

    @Test fun sharedSessionsAndDataSaverStayOnYoutube() = kotlinx.coroutines.runBlocking<Unit> {
        assertEquals(SourceIds.YOUTUBE, resolver(shared = true).resolve(TrackRef.of(ytTrack)).sourceId)
        assertEquals(SourceIds.YOUTUBE, resolver().resolve(TrackRef.of(ytTrack), fr.lulu.unison.source.StreamRequest.Capped(96)).sourceId)
        assertEquals(0, addon.searches)
    }

    @Test fun downloadsRespectTheSourcePolicy() = kotlinx.coroutines.runBlocking<Unit> {
        val r = resolver(allowDownloads = false)
        assertEquals(SourceIds.YOUTUBE, r.resolve(TrackRef.of(ytTrack), purpose = fr.lulu.unison.source.StreamResolver.Purpose.DOWNLOAD).sourceId)
        assertEquals("s1", r.resolve(TrackRef.of(ytTrack)).sourceId)
    }

    @Test fun sourceTracksPlayFromTheirSourceOrFromYoutube() = kotlinx.coroutines.runBlocking<Unit> {
        val srcTrack = Track("src:s1::hr/1", "Sweet Dreams (Are Made of This)", "Eurythmics", durationMs = 216_500)
        assertEquals("s1", resolver(fr.lulu.unison.source.ResolverSettings()).resolve(TrackRef.of(srcTrack)).sourceId)
        // Source retirée (ou invité qui ne l'a pas) : le même enregistrement sur YouTube Music.
        val gone = resolver(present = false).resolve(TrackRef.of(srcTrack))
        assertEquals(SourceIds.YOUTUBE, gone.sourceId)
        assertEquals("${SourceIds.YOUTUBE}:ytSweet0001", gone.cacheKey)
        addon.fail = true
        assertEquals(SourceIds.YOUTUBE, resolver().resolve(TrackRef.of(srcTrack)).sourceId)
        // À plusieurs : le même enregistrement sur YouTube Music d'abord, même pour qui a la source.
        addon.fail = false
        assertEquals(SourceIds.YOUTUBE, resolver(shared = true).resolve(TrackRef.of(srcTrack)).sourceId)
        val onlyInSource = Track("src:s1::hr/1", "Sweet Dreams (Are Made of This) - Remastered", "Eurythmics", durationMs = 300_000)
        assertEquals("s1", resolver(shared = true).resolve(TrackRef.of(onlyInSource)).sourceId)
        val unknown = Track("src:s9::x", "Introuvable", "Personne", durationMs = 1_000)
        assertTrue(runCatching { resolver().resolve(TrackRef.of(unknown)) }.exceptionOrNull() is fr.lulu.unison.source.StreamUnavailableException)
    }
}

class QualityLabelTest {
    private fun d(mime: String?, rate: Int? = null, depth: Int? = null, kbps: Int? = null) = fr.lulu.unison.source.DecodedFormat(mime, rate, depth, kbps)
    private fun declared(codec: String?, rate: Int? = null, depth: Int? = null, kbps: Int? = null) =
        StreamInfo(url = "https://cdn.example.test/x", expiresAtMs = 0, codec = codec, sampleRateHz = rate, bitDepth = depth, bitrateKbps = kbps, sourceId = "s1", cacheKey = "k")
    private fun tier(decoded: fr.lulu.unison.source.DecodedFormat?, s: StreamInfo? = null) = fr.lulu.unison.source.QualityLabel.of(decoded, s)

    @Test fun losslessAndHiRes() {
        assertEquals(fr.lulu.unison.source.QualityTier.LOSSLESS, tier(d("audio/flac", 44_100, 16)))
        assertEquals(fr.lulu.unison.source.QualityTier.LOSSLESS, tier(d("audio/flac", 48_000, 16)))
        assertEquals(fr.lulu.unison.source.QualityTier.HI_RES, tier(d("audio/flac", 96_000, 24)))
        assertEquals(fr.lulu.unison.source.QualityTier.HI_RES, tier(d("audio/flac", 44_100, 24)))
        assertEquals(fr.lulu.unison.source.QualityTier.HI_RES, tier(d("audio/alac", 88_200)))
        assertEquals(fr.lulu.unison.source.QualityTier.LOSSLESS, tier(d("audio/raw", 44_100, 16)))
        // Profondeur absente du format décodé : celle annoncée par la source, puisque c'est bien du FLAC.
        assertEquals(fr.lulu.unison.source.QualityTier.HI_RES, tier(d("audio/flac", 44_100), declared("flac", depth = 24)))
        assertEquals("FLAC · 24 bits · 96 kHz", fr.lulu.unison.source.QualityLabel.summary(d("audio/flac", 96_000, 24)))
        assertEquals("FLAC · 16 bits · 44,1 kHz", fr.lulu.unison.source.QualityLabel.summary(d("audio/flac", 44_100, 16)))
    }

    @Test fun lossyNeedsAtLeast256Kbps() {
        assertEquals(fr.lulu.unison.source.QualityTier.HIGH, tier(d("audio/mpeg", 44_100, kbps = 320)))
        assertEquals(fr.lulu.unison.source.QualityTier.HIGH, tier(d("audio/mp4a-latm", kbps = 256)))
        assertNull(tier(d("audio/mp4a-latm", kbps = 128)))
        // YouTube Music : Opus à 160 kbps, qualité standard, aucun libellé.
        assertNull(tier(d("audio/opus", 48_000), declared("opus", kbps = 160)))
        assertEquals(fr.lulu.unison.source.QualityTier.HIGH, tier(d("audio/mpeg"), declared("mp3", kbps = 320)))
        assertEquals("MP3 · 44,1 kHz · 320 kbps", fr.lulu.unison.source.QualityLabel.summary(d("audio/mpeg", 44_100), declared("mp3", kbps = 320)))
    }

    @Test fun whatTheSourceClaimsIsNotEnough() {
        // Annoncé sans perte mais décodé en Opus (repli, erreur) : pas de libellé.
        assertNull(tier(d("audio/opus", 48_000), declared("flac", 96_000, 24)))
        // Annoncé à 320 kbps en MP3 mais décodé en AAC : le débit annoncé ne s'applique pas.
        assertNull(tier(d("audio/mp4a-latm"), declared("mp3", kbps = 320)))
        // Rien de décodé : rien d'affiché.
        assertNull(tier(null, declared("flac", 96_000, 24)))
        assertNull(tier(d(null)))
        assertNull(tier(d("audio/unknown")))
        assertEquals(fr.lulu.unison.source.QualityTier.ATMOS, tier(d("audio/eac3-joc")))
        assertNull(tier(d("audio/eac3", kbps = 192)))
    }
}

class SafeFileNameTest {
    @Test fun youtubeIdsKeepTheirFilesOthersAreHashed() {
        assertEquals("khnokW3Mw24", fr.lulu.unison.source.SourceTrackId.fileName("khnokW3Mw24"))
        val src = fr.lulu.unison.source.SourceTrackId.fileName("src:s1::../../etc/x")
        assertFalse(src.contains('/') || src.contains(".."))
        assertEquals(33, src.length)
        assertFalse(src == fr.lulu.unison.source.SourceTrackId.fileName("src:s1::autre"))
    }
}

class LyricFadeTest {
    private val lyrics = fr.lulu.unison.lyrics.Lyrics(
        listOf(
            fr.lulu.unison.lyrics.LyricLine(0, "Une"),
            fr.lulu.unison.lyrics.LyricLine(4_000, "Deux"),
            fr.lulu.unison.lyrics.LyricLine(4_300, "Trois"),
        ),
        synced = true, source = "test",
    )
    private fun alpha(i: Int, at: Long) = fr.lulu.unison.lyrics.LyricFade.alpha(lyrics, i, at)

    @Test fun linesFadeJustBeforeTheNextOne() {
        // Ligne de 4 s : fondu sur 28 % de sa durée, plafonné à 700 ms.
        assertEquals(0.78f, alpha(0, 1_000), 0.001f)
        assertEquals(0.78f, alpha(0, 3_300), 0.001f)
        assertEquals(0.39f, alpha(0, 3_650), 0.001f)
        assertEquals(0f, alpha(0, 4_000), 0.001f)
        // Ligne de 300 ms : fondu d'au moins 160 ms.
        assertEquals(0.78f, alpha(1, 4_100), 0.001f)
        assertEquals(0.39f, alpha(1, 4_220), 0.001f)
        // Dernière ligne : durée supposée de 5 s.
        assertEquals(0.78f, alpha(2, 5_000), 0.001f)
        assertEquals(0f, alpha(2, 9_300), 0.001f)
    }
}
