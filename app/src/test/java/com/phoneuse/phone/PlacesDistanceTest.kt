package com.phoneuse.phone

import com.phoneuse.cards.LatLng
import com.phoneuse.cards.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesDistanceTest {
    @Test
    fun haversineAndWalkMinutes() {
        // Gangnam station to Yeoksam station: about 790 m along the line.
        val gangnam = LatLng(37.4979, 127.0276)
        val yeoksam = LatLng(37.5006, 127.0364)
        val d = PlacesDistance.haversineMeters(gangnam, yeoksam)
        assertEquals(830.0, d, 30.0)
        // One degree of latitude is about 111.2 km anywhere.
        assertEquals(111_195.0, PlacesDistance.haversineMeters(LatLng(0.0, 0.0), LatLng(1.0, 0.0)), 50.0)
        assertEquals(0.0, PlacesDistance.haversineMeters(gangnam, gangnam), 0.0)

        // 80 m a minute, rounded up; at least one minute for anything not at the spot.
        assertEquals(0, PlacesDistance.walkMinutes(0.0))
        assertEquals(1, PlacesDistance.walkMinutes(30.0))
        assertEquals(1, PlacesDistance.walkMinutes(80.0))
        assertEquals(2, PlacesDistance.walkMinutes(81.0))
        assertEquals(4, PlacesDistance.walkMinutes(280.0))

        // fill overwrites the model's guesses and keeps the order and other fields.
        val items = listOf(
            Place("역삼역", 37.5006, 127.0364, distanceM = 5.0, walkMin = 99, phone = "02-1"),
            Place("강남역", 37.4979, 127.0276),
        )
        val filled = PlacesDistance.fill(gangnam, items)
        assertEquals(listOf("역삼역", "강남역"), filled.map { it.name })
        assertEquals(d.toInt().toDouble(), filled[0].distanceM!!, 0.0)
        assertEquals(PlacesDistance.walkMinutes(d), filled[0].walkMin)
        assertEquals("02-1", filled[0].phone)
        assertEquals(0.0, filled[1].distanceM!!, 0.0)
        assertEquals(0, filled[1].walkMin)
    }
}
