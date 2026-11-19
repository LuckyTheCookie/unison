package fr.lulu.unison

import fr.lulu.unison.source.AddonClient
import fr.lulu.unison.source.AddonFormat
import fr.lulu.unison.source.ManifestSource
import fr.lulu.unison.source.SourceConfig
import fr.lulu.unison.source.SourceHealth
import fr.lulu.unison.source.SourcePolicy
import fr.lulu.unison.source.SourceTrackId
import fr.lulu.unison.source.StreamGuard
import fr.lulu.unison.source.StreamRequest
import fr.lulu.unison.source.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** Domaine factice : aucune source réelle n'apparaît dans les tests. */
private const val SOURCE = "https://example.test"

class AddonFormatTest {
    private fun manifest(text: String) = (AddonFormat.parseManifest(text) as AddonFormat.Parsed.Ok).value
    private fun invalid(text: String) = (AddonFormat.parseManifest(text) as AddonFormat.Parsed.Invalid).reason

    @Test fun readsManifestWithTolerantFlags() {
        val m = manifest(
            """{"id":"org.exemple.source","name":"Ma source","version":"1.2.0","resources":["search","stream"],
               "allowDownloads":0,"unknown":{"x":1},
               "settings":[{"key":"quality","type":"select","default":"high",
                 "options":[{"value":"lossless","label":"Sans perte"},{"value":"high","label":"Haute"}]},
                 {"key":"region","default":"fr"},{"key":"bad key!","default":"x"}]}""",
        )
        assertEquals("org.exemple.source", m.id)
        assertEquals("Ma source", m.name)
        assertEquals("1.2.0", m.version)
        assertFalse(m.allowDownloads)
        assertEquals(listOf("lossless", "high"), m.qualityOptions.map { it.value })
        assertEquals("high", m.defaultQuality)
        assertEquals(mapOf("quality" to "high", "region" to "fr"), m.defaults)
        assertTrue(m.hasStream)
        assertFalse(m.proprietaryAuth)
        assertTrue(manifest("""{"id":"a","allowDownloads":"true"}""").allowDownloads)
        assertTrue(manifest("""{"id":"a"}""").allowDownloads)
        assertFalse(manifest("""{"id":"a","allowDownloads":"no"}""").allowDownloads)
        assertFalse(manifest("""{"id":"a","resources":["search"]}""").hasStream)
    }

    @Test fun refusesWhatIsNotASource() {
        assertTrue(invalid("""{"name":"sans id"}""").contains("identifiant"))
        assertTrue(invalid("""{"id":"a","resources":["stream"]}""").contains("recherche"))
        assertTrue(invalid("""{"category:rock":[{"url":"x.js"}]}""").contains("code distant"))
        assertTrue(invalid("<html>").isNotEmpty())
        // Authentification propre à une autre application : repérée pour être refusée, jamais reproduite.
        assertTrue(manifest("""{"id":"a","bitchordAuth":0}""").proprietaryAuth)
    }

    @Test fun readsSearchRows() {
        val r = AddonFormat.parseSearch(
            """{"tracks":[
              {"id":"t:1","title":"Titre","artist":["A","B"],"album":{"title":"Album"},"duration":201.5,"artworkURL":"https://img.example.test/c.jpg","explicit":1},
              {"id":"t:2","title":"Sans pochette sûre","artist":"C","artworkURL":"http://img.example.test/c.jpg"},
              {"title":"sans id"},{"id":"t:3"},{"id":"..","title":"chemin"},
              {"id":"t:1","title":"doublon"}],"albums":[{"id":"x"}]}""",
            "s0123456789",
        ) as AddonFormat.Parsed.Ok
        assertEquals(2, r.value.size)
        val t = r.value[0].track
        assertEquals("src:s0123456789::t:1", t.id)
        assertEquals("A, B", t.artist)
        assertEquals("Album", t.album)
        assertEquals(201_500L, t.durationMs)
        assertTrue(t.explicit)
        assertEquals("https://img.example.test/c.jpg", t.cover)
        assertEquals("", r.value[1].track.cover)
    }

    @Test fun capsTheNumberOfRows() {
        val many = (1..80).joinToString(",") { """{"id":"$it","title":"T$it"}""" }
        val r = AddonFormat.parseSearch("""{"tracks":[$many]}""", "s1") as AddonFormat.Parsed.Ok
        assertEquals(AddonFormat.MAX_TRACKS, r.value.size)
        assertTrue(AddonFormat.parseSearch("""{"error":"quota"}""", "s1") is AddonFormat.Parsed.Invalid)
    }

    @Test fun sampleRateInHzOrKhz() {
        assertEquals(44_100, AddonFormat.sampleRate(JsonPrimitive(44.1)))
        assertEquals(96_000, AddonFormat.sampleRate(JsonPrimitive(96)))
        assertEquals(96_000, AddonFormat.sampleRate(JsonPrimitive(96000)))
        assertEquals(44_100, AddonFormat.sampleRate(JsonPrimitive("44,1 kHz")))
        assertEquals(192_000, AddonFormat.sampleRate(JsonPrimitive("192000 Hz")))
        assertNull(AddonFormat.sampleRate(JsonPrimitive(5)))
        assertNull(AddonFormat.sampleRate(JsonPrimitive("beaucoup")))
    }

    @Test fun bitrateInBitsOrKbps() {
        assertEquals(1411, AddonFormat.bitrate(JsonPrimitive(1411)))
        assertEquals(1411, AddonFormat.bitrate(JsonPrimitive(1_411_200)))
        assertEquals(320, AddonFormat.bitrate(JsonPrimitive("320 kbps")))
        assertEquals(320, AddonFormat.bitrate(JsonPrimitive("320000 bps")))
        assertEquals(1400, AddonFormat.bitrate(JsonPrimitive("1.4 Mbps")))
        assertEquals(320, AddonFormat.bitrate(JsonPrimitive("320000")))
        assertNull(AddonFormat.bitrate(JsonPrimitive(1)))
    }

    @Test fun readsStreamFieldsAndFreeText() {
        val s = (AddonFormat.parseStream(
            """{"url":"https://cdn.example.test/a.flac","codec":"FLAC","sampleRate":96,"bitDepth":"24","bitrate":4608000,
               "encrypted":false,"manifest":"none","headers":{"Referer":"https://example.test","Authorization":"Bearer x","User-Agent":"a\r\nX: y"}}""",
        ) as AddonFormat.Parsed.Ok).value
        assertEquals("flac", s.codec)
        assertEquals(96_000, s.sampleRateHz)
        assertEquals(24, s.bitDepth)
        assertEquals(4608, s.bitrateKbps)
        assertFalse(s.encrypted)
        assertEquals(Transport.PROGRESSIVE, s.transport)
        // Liste blanche : Authorization et toute valeur avec retour à la ligne sont écartés.
        assertEquals(mapOf("Referer" to "https://example.test"), s.headers)

        val text = (AddonFormat.parseStream("""{"url":"https://cdn.example.test/x","streamQuality":"[Service] HI_RES 24-bit 192 kHz","format":"flac"}""") as AddonFormat.Parsed.Ok).value
        assertEquals(192_000, text.sampleRateHz)
        assertEquals(24, text.bitDepth)
        val lossy = (AddonFormat.parseStream("""{"url":"https://cdn.example.test/x.mp3","quality":"320 kbps"}""") as AddonFormat.Parsed.Ok).value
        assertEquals("mp3", lossy.codec)
        assertEquals(320, lossy.bitrateKbps)
    }

    @Test fun flagsEncryptedAndManifestStreams() {
        fun parse(t: String) = (AddonFormat.parseStream(t) as AddonFormat.Parsed.Ok).value
        assertTrue(parse("""{"url":"https://cdn.example.test/x","encrypted":true}""").encrypted)
        assertTrue(parse("""{"url":"https://cdn.example.test/x","encrypted":"1"}""").encrypted)
        assertTrue(parse("""{"url":"https://cdn.example.test/x","drm":{"type":"widevine"}}""").encrypted)
        assertFalse(parse("""{"url":"https://cdn.example.test/x","encrypted":0,"drm":"none"}""").encrypted)
        assertEquals(Transport.DASH, parse("""{"url":"https://cdn.example.test/x","format":"dash"}""").transport)
        assertEquals(Transport.HLS, parse("""{"url":"https://cdn.example.test/x.m3u8"}""").transport)
        assertEquals(Transport.DASH, parse("""{"url":"https://cdn.example.test/x","manifest":"application/dash+xml"}""").transport)
        assertNull(parse("""{"error":"pas ce titre"}""").url)
    }

    @Test fun sourceTrackIds() {
        assertEquals("src:s1::a::b", SourceTrackId.make("s1", "a::b"))
        assertEquals("s1" to "a::b", SourceTrackId.parse("src:s1::a::b"))
        assertNull(SourceTrackId.parse("khnokW3Mw24"))
        assertNull(SourceTrackId.parse("src:::x"))
        assertTrue(SourceTrackId.isSource("src:s1::x"))
        assertFalse(SourceTrackId.isSource("dQw4w9WgXcQ"))
    }
}

class SourcePolicyTest {
    private val release = SourcePolicy(debugLocal = false)
    private val debug = SourcePolicy(debugLocal = true)

    private fun ok(p: SourcePolicy, url: String) = p.checkBase(url) as? SourcePolicy.BaseCheck.Ok

    @Test fun baseMustBeHttps() {
        assertEquals("https://example.test/addon", SourcePolicy.baseString(ok(release, "https://example.test/addon/manifest.json?x=1#f")!!.url))
        assertEquals("https://example.test", SourcePolicy.baseString(ok(release, "example.test/")!!.url))
        assertNull(ok(release, "http://example.test"))
        assertNull(ok(release, "ftp://example.test"))
        assertNull(ok(release, "https://user:pass@example.test"))
        assertNull(ok(release, "https://127.0.0.1"))
        assertNull(ok(release, "https://localhost"))
        assertNull(ok(release, "https://169.254.1.1"))
        assertNull(ok(release, "http://10.0.2.2:8765"))
    }

    @Test fun debugBuildsAcceptOnlyTheDevelopmentHostsOverHttp() {
        val emulatorHost = ok(debug, "http://10.0.2.2:8765")!!
        assertTrue(emulatorHost.rule.local && emulatorHost.rule.allowHttp)
        assertNotNull(ok(debug, "http://localhost:8765"))
        assertNull(ok(debug, "http://192.168.1.20"))
        assertNull(ok(debug, "http://example.test"))
    }

    @Test fun remoteSourcesCannotPointStreamsAtTheLocalNetwork() {
        val rule = ok(release, SOURCE)!!.rule
        assertFalse(rule.local)
        assertNotNull(release.checkStream("https://cdn.example.test/a.flac?sig=1", rule))
        for (bad in listOf(
            "http://cdn.example.test/a.flac", "file:///sdcard/a.flac", "content://media/1", "intent://x",
            "https://127.0.0.1/a", "https://localhost/a", "https://10.1.2.3/a", "https://192.168.0.10/a",
            "https://172.16.5.4/a", "https://169.254.169.254/latest", "https://[::1]/a", "https://[fd00::1]/a",
            "https://[::ffff:192.168.0.1]/a", "https://router.local/a", "https://user:pw@cdn.example.test/a", "pas une adresse",
        )) assertNull(bad, release.checkStream(bad, rule))
        assertFalse(rule.allowsAddress(InetAddress.getByName("192.168.1.1"), "cdn.example.test"))
        assertFalse(rule.allowsAddress(InetAddress.getByName("127.0.0.1"), "cdn.example.test"))
        assertTrue(rule.allowsAddress(InetAddress.getByName("93.184.216.34"), "cdn.example.test"))
    }

    @Test fun localSourcesOnlyStreamFromThemselves() {
        val rule = ok(debug, "http://10.0.2.2:8765")!!.rule
        assertNotNull(debug.checkStream("http://10.0.2.2:8765/files/a.flac", rule))
        assertNull(debug.checkStream("http://192.168.1.2/a.flac", rule))
        assertNull(debug.checkStream("https://cdn.example.test/a.flac", rule))
    }

    @Test fun privateAddresses() {
        for (a in listOf("10.0.0.1", "172.31.255.255", "192.168.1.1", "127.0.0.1", "169.254.0.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fc00::1")) {
            assertTrue(a, SourcePolicy.isPrivateAddress(InetAddress.getByName(a)))
        }
        for (a in listOf("8.8.8.8", "172.32.0.1", "2001:4860:4860::8888")) assertFalse(a, SourcePolicy.isPrivateAddress(InetAddress.getByName(a)))
    }
}

/** Une vraie source factice (MockWebServer), servie en HTTP sur cette machine : seule une version de développement l'accepte. */
class ManifestSourceTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val policy = SourcePolicy(debugLocal = true)
    private val guard = StreamGuard()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()
    private val base get() = server.url("/addon").toString().trimEnd('/')

    private fun source(streamEndpoint: Boolean = true, defaults: Map<String, String> = mapOf("quality" to "high")) = ManifestSource(
        SourceConfig(SourceConfig.idFor(base), "org.exemple.source", "Ma source", "1.0", base, defaults = defaults, streamEndpoint = streamEndpoint),
        policy, http, guard, "Unison/test",
    )

    @Test fun inspectsThenHealthChecksBeforeAnything() = runBlocking<Unit> {
        server.enqueue(json("""{"id":"org.exemple.source","name":"Ma source","version":"1.0","resources":["search","stream"]}"""))
        server.enqueue(json("""{"tracks":[]}"""))
        val r = ManifestSource.inspect("$base/manifest.json", policy, http, guard, "Unison/test") as ManifestSource.Companion.InspectResult.Ready
        assertEquals(SourceHealth.Ok, r.inspection.health)
        assertEquals(base, r.inspection.config.baseUrl)
        assertEquals("org.exemple.source", r.inspection.config.manifestId)
        assertEquals("/addon/manifest.json", server.takeRequest().target)
        val probe = server.takeRequest()
        assertTrue(probe.target.startsWith("/addon/search?q=music"))
        assertEquals("Unison/test", probe.headers["User-Agent"])
    }

    @Test fun inspectReportsWhyASourceIsRefused() = runBlocking<Unit> {
        server.enqueue(json("""{"error":"not here"}""", 404))
        val missing = ManifestSource.inspect(base, policy, http, guard, "Unison/test")
        assertTrue((missing as ManifestSource.Companion.InspectResult.Refused).reason.contains("manifest"))

        server.enqueue(json("""{"id":"a","bitchordAuth":false}"""))
        val auth = ManifestSource.inspect(base, policy, http, guard, "Unison/test")
        assertEquals(ManifestSource.NOT_COMPATIBLE, (auth as ManifestSource.Companion.InspectResult.Refused).reason)

        server.enqueue(json("""{"id":"a","pad":"${"x".repeat(70_000)}"}"""))
        val big = ManifestSource.inspect(base, policy, http, guard, "Unison/test")
        assertTrue((big as ManifestSource.Companion.InspectResult.Refused).reason.contains("volumineuse"))

        server.enqueue(json("""{"id":"a"}"""))
        server.enqueue(json("boom", 500))
        val down = ManifestSource.inspect(base, policy, http, guard, "Unison/test") as ManifestSource.Companion.InspectResult.Ready
        assertTrue(down.inspection.health is SourceHealth.Unreachable)

        val https = ManifestSource.inspect(base, SourcePolicy(debugLocal = false), http, guard, "Unison/test")
        assertTrue(https is ManifestSource.Companion.InspectResult.Refused)
    }

    @Test fun searchSendsSettingsAndRetriesAfter429() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "0").build())
        server.enqueue(json("""{"tracks":[{"id":"t/1?x","title":"Titre","artist":"Artiste","duration":180}]}"""))
        val s = source()
        val tracks = s.search("été 85", 10)
        assertEquals(1, tracks.size)
        assertEquals(SourceTrackId.make(SourceConfig.idFor(base), "t/1?x"), tracks[0].id)
        server.takeRequest()
        val req = server.takeRequest()
        assertEquals("été 85", req.url.queryParameter("q"))
        assertEquals("high", req.url.queryParameter("quality"))
        // En cache : pas de nouvelle requête.
        assertEquals(1, s.search("été 85", 10).size)
        assertEquals(2, server.requestCount)
    }

    @Test fun streamIdIsEncodedAndDescribed() = runBlocking<Unit> {
        val streamUrl = server.url("/files/a.flac").toString()
        server.enqueue(json("""{"url":"$streamUrl","codec":"flac","sampleRate":44.1,"bitDepth":16,"encrypted":false}"""))
        val info = source().stream("a/b?c", StreamRequest.Best)!!
        assertEquals("/addon/stream/a%2Fb%3Fc?quality=high", server.takeRequest().target)
        assertEquals("flac", info.codec)
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(16, info.bitDepth)
        assertEquals(SourceConfig.idFor(base), info.sourceId)
        assertTrue(info.cacheKey.startsWith("src:"))
        assertFalse(info.toString().contains("a.flac"))
    }

    @Test fun refusedOrMissingStreamsAreNull() = runBlocking<Unit> {
        val s = source()
        server.enqueue(json("""{"error":"pas ce titre"}"""))
        assertNull(s.stream("1", StreamRequest.Best))
        server.enqueue(json("""{"url":"${server.url("/a.flac")}","encrypted":true}"""))
        assertNull(s.stream("2", StreamRequest.Best))
        server.enqueue(json("""{"url":"${server.url("/a.mpd")}","manifest":"dash"}"""))
        assertNull(s.stream("3", StreamRequest.Best))
        // Source locale : un flux sur un autre hôte est refusé.
        server.enqueue(json("""{"url":"https://cdn.example.test/a.flac"}"""))
        assertNull(s.stream("4", StreamRequest.Best))
        server.enqueue(json("{}", 404))
        assertNull(s.stream("5", StreamRequest.Best))
        assertNull(s.stream("..", StreamRequest.Best))
    }

    @Test fun failuresAreReportedNotHidden() = runBlocking<Unit> {
        server.enqueue(json("down", 503))
        server.enqueue(json("down", 503))
        val e = runCatching { source().stream("x", StreamRequest.Best) }.exceptionOrNull()
        assertTrue(e is fr.lulu.unison.source.AddonException.Unavailable)
        server.enqueue(json("{pas du json"))
        assertTrue(runCatching { source().search("x", 5) }.exceptionOrNull() is fr.lulu.unison.source.AddonException.Rejected)
    }

    @Test fun streamsFromTheSearchRowWhenThereIsNoStreamEndpoint() = runBlocking<Unit> {
        val s = source(streamEndpoint = false)
        server.enqueue(json("""{"tracks":[{"id":"r1","title":"T","streamURL":"${server.url("/r1.mp3")}","quality":"320 kbps"}]}"""))
        s.search("t", 5)
        val info = s.stream("r1", StreamRequest.Best)!!
        assertEquals(320, info.bitrateKbps)
        assertEquals(1, server.requestCount)
    }

    @Test fun retryAfterParsing() {
        assertEquals(2_000L, AddonClient.retryAfterMs("2"))
        assertNull(AddonClient.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals("http://localhost/x".toHttpUrl().host, "localhost")
    }
}
