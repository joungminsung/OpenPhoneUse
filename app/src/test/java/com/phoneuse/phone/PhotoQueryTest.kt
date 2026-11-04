package com.phoneuse.phone

import com.phoneuse.cards.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class PhotoQueryTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, h: Int = 12) = ZonedDateTime.of(2026, 7, day, h, 0, 0, 0, seoul).toInstant()

    private val jeju = LatLng(33.4996, 126.5312)
    private val seoulCity = LatLng(37.5665, 126.9780)

    // Newest first, as MediaStore returns them.
    private val rows = listOf(
        PhotoRow("content://media/external/images/media/5", at(20), "Camera"),
        PhotoRow("content://media/external/images/media/4", at(15, 23), "Camera"),
        PhotoRow("content://media/external/images/media/3", at(15, 0), "KakaoTalk"),
        PhotoRow("content://media/external/images/media/2", at(14, 23), "Camera"),
        PhotoRow("content://media/external/images/media/1", null, "Screenshots"),
    )
    private val places = mapOf(
        "content://media/external/images/media/5" to seoulCity,
        "content://media/external/images/media/4" to LatLng(33.51, 126.52), // about 1.5 km from Jeju city
        "content://media/external/images/media/3" to jeju,
    )

    private fun query(from: String? = null, to: String? = null, near: Near? = null, album: String? = null, limit: Int? = null) =
        PhotoQuery.parse(from, to, near, album, limit, seoul).getOrThrow()

    private fun ids(q: PhotoQuery) = q.select(rows) { places[it] }.hits.map { it.uri.substringAfterLast('/') }

    @Test
    fun dateRangeIsLocalAndToDateIsInclusive() {
        // A date-only "to" means the end of that day; "from" its start.
        assertEquals(listOf("4", "3"), ids(query("2026-07-15", "2026-07-15")))
        assertEquals(listOf("5", "4", "3"), ids(query(from = "2026-07-15")))
        assertEquals(listOf("2"), ids(query(to = "2026-07-14")))
        // Date-times are exact; "to" is exclusive.
        assertEquals(listOf("3"), ids(query("2026-07-15T00:00", "2026-07-15T23:00")))
        // No range: every photo, the undated one too.
        assertEquals(5, ids(query()).size)
    }

    @Test
    fun radiusFilterUsesPhotoLocation() {
        val near = Near(jeju.lat, jeju.lng, 3.0)
        val hits = query(near = near).select(rows) { places[it] }.hits
        assertEquals(listOf("4", "3"), hits.map { it.uri.substringAfterLast('/') })
        // Matches carry their location; photos without one never match a radius search.
        assertEquals(33.51, hits[0].lat!!, 0.0)
        assertEquals(listOf("3"), ids(query(near = Near(jeju.lat, jeju.lng, 0.5))))
        assertEquals(listOf("5"), ids(query(near = Near(seoulCity.lat, seoulCity.lng, 1.0))))
    }

    @Test
    fun albumAndLimit() {
        assertEquals(listOf("5", "4", "2"), ids(query(album = " camera ")))
        assertEquals(listOf("5", "4"), ids(query(limit = 2)))
        val hit = query(limit = 1).select(rows) { error("location is not read without near") }.hits.single()
        assertNull(hit.lat)
        assertEquals("Camera", hit.album)
    }

    @Test
    fun badArgumentsAreRejected() {
        assertTrue(PhotoQuery.parse("작년 여름", null, null, null, null, seoul).isFailure)
        assertTrue(PhotoQuery.parse("2026-07-20", "2026-07-10", null, null, null, seoul).isFailure)
        assertTrue(PhotoQuery.parse(null, null, Near(95.0, 0.0, 1.0), null, null, seoul).isFailure)
        assertTrue(PhotoQuery.parse(null, null, Near(33.0, 126.0, 0.0), null, null, seoul).isFailure)
        assertTrue(PhotoQuery.parse(null, null, Near(33.0, 126.0, 500.0), null, null, seoul).isFailure)
        assertTrue(PhotoQuery.parse(null, null, null, null, 0, seoul).isFailure)
        assertEquals(PhotoQuery.MAX_LIMIT, PhotoQuery.parse(null, null, null, null, 500, seoul).getOrThrow().limit)
        assertEquals(Instant.parse("2026-07-14T15:00:00Z"), query(from = "2026-07-15").from)
    }

    @Test
    fun boxFilterUsesPhotoLocation() {
        // Jeju island's box: rows 4 and 3; Seoul (row 5) is outside, photos without a location never match.
        val jejuBox = BBox(33.1, 33.6, 126.1, 127.0)
        assertEquals(listOf("4", "3"), ids(query().copy(box = jejuBox)))
        val hits = query().copy(box = jejuBox).select(rows) { places[it] }.hits
        assertEquals(33.51, hits[0].lat!!, 0.0)
        // Edges are inclusive.
        assertTrue(BBox(33.4996, 33.4996, 126.5312, 126.5312).contains(jeju))
        assertTrue(!jejuBox.contains(seoulCity))
    }

    @Test
    fun boxAcrossTheAntimeridian() {
        // Fiji-like: west 177, east -178 wraps over 180.
        val box = BBox(-21.0, -12.0, 177.0, -178.0)
        assertTrue(box.contains(LatLng(-17.0, 178.5)))
        assertTrue(box.contains(LatLng(-17.0, -179.5)))
        assertTrue(box.contains(LatLng(-17.0, 180.0)))
        assertTrue(!box.contains(LatLng(-17.0, 0.0)))
        assertTrue(!box.contains(LatLng(-17.0, -170.0)))
        assertTrue(!box.contains(LatLng(10.0, 178.5)))
    }

    @Test
    fun selectionCountsEveryMatchAndItsDateSpan() {
        val all = query(limit = 2).select(rows) { places[it] }
        assertEquals(2, all.hits.size)
        assertEquals(5, all.total)
        assertEquals(at(14, 23), all.earliest)
        assertEquals(at(20), all.latest)
        val inJeju = query(limit = 1).copy(box = BBox(33.1, 33.6, 126.1, 127.0)).select(rows) { places[it] }
        assertEquals(listOf("4"), inJeju.hits.map { it.uri.substringAfterLast('/') })
        assertEquals(2, inJeju.total)
        assertEquals(at(15, 0), inJeju.earliest)
        assertEquals(at(15, 23), inJeju.latest)
    }

    @Test
    fun hitsCarryLocationWhenAskedEvenWithoutAPlaceFilter() {
        val read = mutableListOf<String>()
        val sel = query(limit = 2).select(rows, locateHits = true) { read += it; places[it] }
        assertEquals(37.5665, sel.hits[0].lat!!, 0.0)
        assertEquals(33.51, sel.hits[1].lat!!, 0.0)
        // Only the returned photos are read.
        assertEquals(2, read.size)
    }
}
