package com.phoneuse.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTilesTest {
    @Test
    fun tilesSurviveARoundTrip() {
        val tiles = listOf(
            QuickTile("무음", "1시간 무음 켜줘", TileIcon.MOON),
            QuickTile("집", "집으로 길안내", TileIcon.HOME),
        )
        assertEquals(tiles, QuickTiles.decode(QuickTiles.encode(tiles)))
    }

    @Test
    fun brokenOrEmptyDataGivesNull() {
        assertNull(QuickTiles.decode(null))
        assertNull(QuickTiles.decode(""))
        assertNull(QuickTiles.decode("{not json"))
        assertNull(QuickTiles.decode("[]"))
        assertNull(QuickTiles.decode("""[{"label":"x","prompt":"  "}]"""))
    }

    @Test
    fun readingIsForgiving() {
        val tiles = QuickTiles.decode("""[{"label":"","prompt":"알람  맞춰줘","icon":"rocket","extra":1},{"prompt":"절전 모드"}]""")!!
        assertEquals(QuickTile("알람 맞춰줘", "알람 맞춰줘", TileIcon.BOLT), tiles[0])
        assertEquals(QuickTile("절전 모드", "절전 모드", TileIcon.BOLT), tiles[1])
    }

    @Test
    fun cleanCutsAndCaps() {
        val many = (1..10).map { QuickTile("라벨".repeat(20), "할 일 $it ".repeat(100)) }
        val clean = QuickTiles.clean(many)
        assertEquals(QuickTiles.MAX_TILES, clean.size)
        assertTrue(clean.all { it.label.length <= QuickTiles.LABEL_CHARS && it.prompt.length <= QuickTiles.PROMPT_CHARS })
    }

    @Test
    fun defaultsAreUsableTiles() {
        val defaults = QuickTiles.defaults()
        assertEquals(QuickTiles.MAX_TILES, defaults.size)
        assertEquals(defaults, QuickTiles.clean(defaults))
        assertEquals("1시간 무음", defaults.first().label)
    }

    @Test
    fun unknownIconsFallBackToBolt() {
        assertEquals(TileIcon.MOON, TileIcon.fromWire(" Moon "))
        assertEquals(TileIcon.BOLT, TileIcon.fromWire(null))
        assertEquals(TileIcon.BOLT, TileIcon.fromWire("??"))
    }
}
