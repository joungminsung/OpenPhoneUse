package com.phoneuse.phone

import com.phoneuse.cards.LatLng
import com.phoneuse.cards.Place
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Straight-line distances and walking minutes for a places card; the app computes them, not the model. */
object PlacesDistance {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Walking speed used for walk_min. */
    const val WALK_METERS_PER_MINUTE = 80.0

    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Minutes at [WALK_METERS_PER_MINUTE], rounded up. */
    fun walkMinutes(meters: Double): Int = if (meters <= 0) 0 else ceil(meters / WALK_METERS_PER_MINUTE).toInt()

    /** [items] with distance_m (whole metres) and walk_min from [center], in the same order. */
    fun fill(center: LatLng, items: List<Place>): List<Place> = items.map { p ->
        val d = haversineMeters(center, LatLng(p.lat, p.lng))
        p.copy(distanceM = d.toInt().toDouble(), walkMin = walkMinutes(d))
    }
}
