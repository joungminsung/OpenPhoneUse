package com.phoneuse.phone

import com.phoneuse.cards.LatLng
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import java.util.Locale

/** One geocoding hit; [distanceM] is from the search centre when one was given. */
data class GeoPlace(val name: String, val lat: Double, val lng: Double, val address: String, val distanceM: Double? = null)

/** Place name → coordinates (the geocode tool). */
interface PlaceSearch {
    suspend fun search(query: String, nearLat: Double?, nearLng: Double?): List<GeoPlace>
}

/**
 * Nominatim (OpenStreetMap) geocoding. Its usage policy: an identifying User-Agent and at most one request a
 * second, so requests are serialized and spaced by [MIN_GAP_MS]; answers are cached for [CACHE_MS]. Keep one
 * instance per process so the spacing holds across runs.
 */
class Geocoder(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    /** Monotonic milliseconds: a wall-clock change must not stall or skip the spacing. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : PlaceSearch, PlaceBounds {
    private val lock = Mutex()
    private var lastRequestAt: Long? = null
    private val searches = Cache<List<GeoPlace>>()
    private val reverses = Cache<String?>()
    private val boxes = Cache<BBox?>()

    private class Cache<T> {
        private val map = LinkedHashMap<String, Pair<Long, T>>()

        fun get(key: String, now: Long): Pair<Long, T>? = map[key]?.takeIf { now - it.first < CACHE_MS }

        fun put(key: String, at: Long, value: T) {
            map.remove(key)
            map[key] = at to value
            while (map.size > MAX_CACHE) map.remove(map.keys.first())
        }
    }

    override suspend fun search(query: String, nearLat: Double?, nearLng: Double?): List<GeoPlace> {
        val q = query.trim()
        require(q.isNotEmpty()) { "query is empty" }
        val near = if (nearLat != null && nearLng != null) LatLng(nearLat, nearLng) else null
        val key = q + "|" + near?.let { "%.3f,%.3f".format(Locale.ROOT, it.lat, it.lng) }.orEmpty()
        return lock.withLock {
            searches.get(key, now())?.let { return@withLock it.second }
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("q", q)
                .addQueryParameter("limit", LIMIT.toString())
                .addQueryParameter("accept-language", "ko")
                .apply {
                    // Prefer (do not limit to) hits around the centre.
                    if (near != null) {
                        addQueryParameter(
                            "viewbox",
                            "%.4f,%.4f,%.4f,%.4f".format(Locale.ROOT, near.lng - BOX, near.lat + BOX, near.lng + BOX, near.lat - BOX),
                        )
                    }
                }
                .build()
            val rows = (parseJson(fetch(url)) as? JsonArray).orEmpty()
            val places = rows.mapNotNull { parse(it as? JsonObject ?: return@mapNotNull null, near) }
            val sorted = if (near == null) places else places.sortedBy { it.distanceM }
            searches.put(key, now(), sorted)
            sorted
        }
    }

    /** The best hit's bounding box for a place name ("영국", "제주도"), or null when Nominatim has none. */
    override suspend fun bounds(place: String): BBox? {
        val q = place.trim()
        require(q.isNotEmpty()) { "place is empty" }
        return lock.withLock {
            boxes.get(q, now())?.let { return@withLock it.second }
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("q", q)
                .addQueryParameter("limit", "1")
                .addQueryParameter("accept-language", "ko")
                .build()
            val first = (parseJson(fetch(url)) as? JsonArray)?.firstOrNull() as? JsonObject
            val box = first?.let { parseBox(it) }
            boxes.put(q, now(), box)
            box
        }
    }

    /** Nominatim's boundingbox: [south, north, west, east] as strings. */
    private fun parseBox(o: JsonObject): BBox? {
        val parts = (o["boundingbox"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null } ?: return null
        if (parts.size != 4) return null
        val (south, north, west, east) = parts
        if (south !in -90.0..90.0 || north !in -90.0..90.0 || south > north) return null
        if (west !in -180.0..180.0 || east !in -180.0..180.0) return null
        return BBox(south, north, west, east)
    }

    /** The address at a point, or null when Nominatim has none. */
    suspend fun reverse(lat: Double, lng: Double): String? {
        val key = "%.4f,%.4f".format(Locale.ROOT, lat, lng)
        return lock.withLock {
            reverses.get(key, now())?.let { return@withLock it.second }
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("reverse")
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("lat", lat.toString())
                .addQueryParameter("lon", lng.toString())
                .addQueryParameter("accept-language", "ko")
                .build()
            val obj = parseJson(fetch(url)) as? JsonObject
            val address = (obj?.get("display_name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            reverses.put(key, now(), address)
            address
        }
    }

    /** Called with [lock] held: waits out the one-second gap, then makes the request. */
    private suspend fun fetch(url: HttpUrl): String {
        lastRequestAt?.let { last ->
            val wait = last + MIN_GAP_MS - now()
            if (wait > 0) sleep(wait)
        }
        lastRequestAt = now()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val call = http.newCall(request)
        call.timeout().timeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        // Cancelling the run cancels the request, so a stopped run does not hold the lock until a timeout.
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                if (!it.isSuccessful) throw IOException("지오코딩 실패 (HTTP ${it.code})")
                                it.body?.string() ?: throw IOException("지오코딩 응답이 비었어요")
                            }
                        }
                        if (cont.isActive) cont.resumeWith(result)
                    }
                },
            )
        }
    }

    /** A non-JSON body (an HTML error or rate-limit page) is a network error, not bad tool arguments. */
    private fun parseJson(body: String): JsonElement = try {
        Json.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw IOException("지오코딩 응답을 읽지 못했어요")
    }

    private fun parse(o: JsonObject, near: LatLng?): GeoPlace? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val lat = s("lat")?.toDoubleOrNull() ?: return null
        val lng = s("lon")?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val address = s("display_name").orEmpty()
        val name = s("name")?.takeIf { it.isNotBlank() } ?: address.substringBefore(',').trim().takeIf { it.isNotEmpty() } ?: return null
        val distance = near?.let { PlacesDistance.haversineMeters(it, LatLng(lat, lng)).toInt().toDouble() }
        return GeoPlace(name, lat, lng, address, distance)
    }

    companion object {
        const val MIN_GAP_MS = 1000L
        const val CACHE_MS = 10 * 60 * 1000L
        private const val CALL_TIMEOUT_S = 20L
        private const val MAX_CACHE = 100
        private const val LIMIT = 5

        /** Half the side of the preferred box around the centre, in degrees (about 5 km). */
        private const val BOX = 0.05
    }
}
