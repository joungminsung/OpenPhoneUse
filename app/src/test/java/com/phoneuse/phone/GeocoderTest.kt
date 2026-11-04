package com.phoneuse.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeocoderTest {
    private val server = MockWebServer()
    private var now = 1_000_000L
    private val slept = mutableListOf<Long>()

    private fun geocoder() = Geocoder(
        OkHttpClient(),
        userAgent = "PhoneUse/1.2.3",
        baseUrl = server.url("/").toString().trimEnd('/'),
        now = { now },
        sleep = { slept += it; now += it },
    )

    private val body = """
        [
          {"place_id":1,"lat":"37.4979","lon":"127.0276","name":"강남역","display_name":"강남역, 강남대로, 서울특별시, 대한민국"},
          {"place_id":2,"lat":"35.1","lon":"129.0","name":"","display_name":"강남역, 부산, 대한민국"},
          {"place_id":3,"lat":"not a number","lon":"1","name":"x","display_name":"x"}
        ]
    """.trimIndent()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    @Test
    fun sendsUserAgentAndParses() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        val g = geocoder()
        // Near Seoul: the Seoul hit sorts first.
        val places = g.search("강남역", nearLat = 37.5, nearLng = 127.03)

        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PhoneUse/1.2.3", req.getHeader("User-Agent"))
        val url = req.requestUrl!!
        assertEquals("/search", url.encodedPath)
        assertEquals("jsonv2", url.queryParameter("format"))
        assertEquals("강남역", url.queryParameter("q"))
        assertEquals("ko", url.queryParameter("accept-language"))
        assertTrue(url.queryParameter("viewbox")!!.isNotBlank())

        assertEquals(2, places.size) // the malformed row is skipped
        assertEquals("강남역", places[0].name)
        assertEquals(37.4979, places[0].lat, 1e-9)
        assertEquals(127.0276, places[0].lng, 1e-9)
        assertEquals("강남역, 강남대로, 서울특별시, 대한민국", places[0].address)
        assertTrue(places[0].distanceM!! < 1000)
        // A blank name falls back to the first part of the address.
        assertEquals("강남역", places[1].name)

        // Without a centre: no viewbox, no distance, Nominatim's order.
        server.enqueue(MockResponse().setBody(body))
        val plain = g.search("강남", null, null)
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("viewbox"))
        assertNull(plain[0].distanceM)
    }

    @Test
    fun throttlesToOnePerSecond() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setBody("[]")) }
        val g = geocoder()
        g.search("a", null, null)
        assertEquals(emptyList<Long>(), slept)
        // Right after: waits out the rest of the second.
        now += 300
        g.search("b", null, null)
        assertEquals(listOf(700L), slept)
        // Cached for ten minutes: no request and no wait.
        g.search("a", null, null)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(700L), slept)
        // After the cache expires it asks again (and is far past the one-second gap).
        now += 10 * 60 * 1000L + 1
        g.search("a", null, null)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(700L), slept)
    }

    @Test
    fun parallelSearchesStillOnePerSecond() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("[]")) }
        val g = geocoder()
        val a = async(Dispatchers.Default) { g.search("a", null, null) }
        val b = async(Dispatchers.Default) { g.search("b", null, null) }
        a.await()
        b.await()
        assertEquals(2, server.requestCount)
        // Whichever went second waited the full second (the fake clock did not move in between).
        assertEquals(listOf(1000L), slept)
    }

    @Test
    fun reverseSendsUserAgentAndCaches() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"display_name":"서울특별시 강남구 역삼동"}"""))
        val g = geocoder()
        assertEquals("서울특별시 강남구 역삼동", g.reverse(37.5, 127.03))
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/reverse", req.requestUrl!!.encodedPath)
        assertEquals("PhoneUse/1.2.3", req.getHeader("User-Agent"))
        assertEquals("37.5", req.requestUrl!!.queryParameter("lat"))
        assertEquals("127.03", req.requestUrl!!.queryParameter("lon"))
        assertEquals("jsonv2", req.requestUrl!!.queryParameter("format"))
        // Cached: no second request.
        assertEquals("서울특별시 강남구 역삼동", g.reverse(37.5, 127.03))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nonJsonOrHttpErrorIsAnIoError() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>Too many requests</html>"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("[]"))
        val g = geocoder()
        for (q in listOf("a", "b")) {
            try {
                g.search(q, null, null)
                fail("expected IOException for $q")
            } catch (e: IOException) {
                // expected
            }
        }
    }

    @Test
    fun cancellingTheCallerCancelsTheRequestAndFreesTheLock() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("[]"))
        val g = geocoder()
        val job = launch(Dispatchers.Default) { g.search("slow", null, null) }
        server.takeRequest(5, TimeUnit.SECONDS)
        job.cancelAndJoin()
        // The next search is not stuck behind the cancelled one.
        val next = withTimeout(5_000) { g.search("next", null, null) }
        assertEquals(emptyList<GeoPlace>(), next)
    }

    @Test
    fun boundsParsesTheBestHitsBoxAndCaches() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"lat":"54.7","lon":"-3.27","name":"영국","display_name":"영국","boundingbox":["49.674","61.061","-14.015","2.091"]},
                    {"lat":"1","lon":"1","boundingbox":["0","1","0","1"]}]""",
            ),
        )
        val g = geocoder()
        assertEquals(BBox(49.674, 61.061, -14.015, 2.091), g.bounds(" 영국 "))
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PhoneUse/1.2.3", req.getHeader("User-Agent"))
        assertEquals("/search", req.requestUrl!!.encodedPath)
        assertEquals("영국", req.requestUrl!!.queryParameter("q"))
        assertEquals("jsonv2", req.requestUrl!!.queryParameter("format"))
        // Cached: no second request.
        assertEquals(BBox(49.674, 61.061, -14.015, 2.091), g.bounds("영국"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun boundsIsNullWithoutAUsableHit() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""[{"lat":"1","lon":"1","boundingbox":["x","1","0","1"]}]"""))
        server.enqueue(MockResponse().setBody("""[{"lat":"1","lon":"1","boundingbox":["10","5","0","1"]}]"""))
        server.enqueue(MockResponse().setBody("""[{"lat":"1","lon":"1"}]"""))
        val g = geocoder()
        for (q in listOf("a", "b", "c", "d")) assertNull(q, g.bounds(q))
        // Spaced one a second like search.
        assertEquals(listOf(1000L, 1000L, 1000L), slept)
    }
}
