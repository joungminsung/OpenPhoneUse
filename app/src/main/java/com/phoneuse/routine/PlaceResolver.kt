package com.phoneuse.routine

import com.phoneuse.memory.MemoryKind
import com.phoneuse.memory.MemoryStore
import com.phoneuse.phone.PlaceSearch
import kotlinx.coroutines.CancellationException

/** Where a named place is: [from] is "기억에서" or "지도 검색", [address] what was found. */
data class ResolvedPlace(val lat: Double, val lng: Double, val from: String, val address: String?)

/**
 * "회사 도착하면…": a place name becomes coordinates from the user's PLACE memories first (coordinates written in
 * the memory, or its address geocoded), else from a geocode of the name itself.
 */
class PlaceResolver(private val places: PlaceSearch?, private val memories: MemoryStore?) {
    suspend fun resolve(name: String): ResolvedPlace? {
        val memory = try {
            memories?.candidates(name)?.firstOrNull { it.kind == MemoryKind.PLACE && norm(it.text).contains(norm(name)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (memory != null) {
            val address = address(memory.text, name)
            COORDS.find(memory.text)?.let { m ->
                val lat = m.groupValues[1].toDouble()
                val lng = m.groupValues[2].toDouble()
                if (lat in -90.0..90.0 && lng in -180.0..180.0) return ResolvedPlace(lat, lng, FROM_MEMORY, address)
            }
            val search = places ?: return null
            for (q in listOfNotNull(address, memory.text).distinct()) {
                val hit = search(search, q) ?: continue
                return ResolvedPlace(hit.lat, hit.lng, FROM_MEMORY, address ?: hit.address)
            }
        }
        val search = places ?: return null
        val hit = search(search, name) ?: return null
        return ResolvedPlace(hit.lat, hit.lng, FROM_SEARCH, hit.address.ifBlank { hit.name })
    }

    private suspend fun search(places: PlaceSearch, q: String) = try {
        places.search(q, null, null).firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val FROM_MEMORY = "기억에서"
        const val FROM_SEARCH = "지도 검색"
        private val COORDS = Regex("""(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})""")
        private val ENDINGS = listOf("에 있어요", "에 있어", "에 있음", "이에요", "예요", "이야", "이다", "야", "임", "에요")

        private fun norm(s: String) = s.replace(Regex("\\s+"), "").lowercase()

        /** "회사는 강남구 테헤란로 152에 있어" → "강남구 테헤란로 152"; null when nothing is left. */
        fun address(text: String, name: String): String? {
            val i = text.indexOf(name)
            val rest = (if (i >= 0) text.substring(i + name.length) else text)
                .trimStart(' ', '은', '는', '이', '가', ':', '-', '=', ',')
                .trim().trimEnd('.', '!', ' ')
            val cut = ENDINGS.firstOrNull { rest.endsWith(it) }?.let { rest.removeSuffix(it) } ?: rest
            return cut.trim().takeIf { it.length >= 2 && !COORDS.matches(it) }
        }
    }
}
