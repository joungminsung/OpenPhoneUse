package com.phoneuse.phone

import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.LatLng
import com.phoneuse.cards.PhotosCard
import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.ToolSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhotosToolTest {
    private fun uri(n: Int) = "content://media/external/images/media/$n"

    private class Source(var read: Boolean = true, var location: Boolean = true) : PhotoSource {
        override val readPermissions = listOf("android.permission.READ_MEDIA_IMAGES")
        val copied = mutableListOf<Pair<String, List<String>>>()
        val thumbs = mutableListOf<Pair<String, Int>>()
        val wallpapers = mutableListOf<String>()
        override fun canRead() = read
        override fun canReadLocation() = location
        override fun rows(from: Instant?, to: Instant?, album: String?, max: Int): List<PhotoRow> {
            if (!read) throw SecurityException("no")
            return (3 downTo 1).map { PhotoRow("content://media/external/images/media/$it", Instant.parse("2026-07-0${it}T03:00:00Z"), "Camera") }
        }
        val located = mutableListOf<String>()
        override fun location(uri: String): LatLng? {
            located += uri
            return if (uri.endsWith("/2")) LatLng(33.50041, 126.53) else null
        }
        override fun thumbnail(uri: String, maxEdge: Int): ByteArray? {
            thumbs += uri to maxEdge
            return byteArrayOf(1, 2, 3)
        }
        override fun copyToAlbum(name: String, uris: List<String>): Int {
            copied += name to uris
            return uris.size
        }
        override fun setWallpaper(uri: String): Boolean {
            wallpapers += uri
            return true
        }
    }

    private class Vision : PhotoVision {
        val asked = mutableListOf<Pair<String, Int>>()
        override suspend fun look(question: String, images: List<ByteArray>): String {
            asked += question to images.size
            return "1번과 2번에 바다가 보여요"
        }
    }

    private val source = Source()
    private val vision = Vision()
    private var prior: List<CardSpec> = emptyList()
    private val boxes = mutableListOf<String>()
    private val bounds = PlaceBounds { place ->
        boxes += place
        if (place == "제주도") BBox(33.1, 33.6, 126.1, 127.0) else null
    }
    private val tool = PhotosTool(source, vision, { prior }, ZoneId.of("Asia/Seoul"), bounds)
    private val all = PhotoQuery.parse(null, null, null, null, null, ZoneId.of("Asia/Seoul")).getOrThrow()
    private val nearJeju = PhotoQuery.parse(null, null, Near(33.5, 126.53, 2.0), null, null, ZoneId.of("Asia/Seoul")).getOrThrow()

    @Test
    fun returnsPermissionNeededWhenDenied() = runBlocking {
        source.read = false
        val find = tool.find(all)
        assertTrue(find.text, find.text.startsWith("권한 필요: 사진"))
        assertEquals(source.readPermissions, (find as PhoneReply.PermissionNeeded).permissions)
        assertTrue(tool.look(listOf(uri(1)), "바다?") is PhoneReply.PermissionNeeded)
        assertTrue(tool.album("제주", listOf(uri(1)), confirmed = true) is PhoneReply.PermissionNeeded)
        assertTrue(source.copied.isEmpty() && source.thumbs.isEmpty())
        // Photos allowed, their locations not: only a radius search asks.
        source.read = true
        source.location = false
        val near = tool.find(nearJeju)
        assertTrue(near.text, near.text.startsWith("권한 필요: 사진 위치"))
        assertEquals(listOf("android.permission.ACCESS_MEDIA_LOCATION"), (near as PhoneReply.PermissionNeeded).permissions)
        assertTrue(tool.find(all) is PhoneReply.Ok)
    }

    @Test
    fun findReturnsUrisAndLocations() = runBlocking {
        val r = tool.find(nearJeju)
        assertTrue(r.text, r is PhoneReply.Ok && r.text.contains("\"uri\":\"${uri(2)}\"") && r.text.contains("\"lat\":33.5"))
        assertTrue(r.text, !r.text.contains(uri(1) + "\""))
        val everything = tool.find(all).text
        assertTrue(everything, everything.contains("\"taken_at\":\"2026-07-03T12:00\""))
        // Photos_find counts as the user's own data (no taint), so it never returns album names any app chose.
        assertTrue(everything, !everything.contains("album") && !everything.contains("Camera"))
    }

    @Test
    fun dateSearchCarriesRoundedLocationsWhenPermitted() = runBlocking {
        val r = tool.find(all)
        assertTrue(r.text, r is PhoneReply.Ok && r.text.contains("\"lat\":33.5,") && r.text.contains("\"lng\":126.53"))
        assertTrue(r.text, !r.text.contains("33.50041"))
        assertTrue(r.text, !r.text.contains("사진 위치"))
    }

    @Test
    fun dateSearchWithoutLocationPermissionHintsAtIt() = runBlocking {
        source.location = false
        val r = tool.find(all)
        assertTrue(r.text, r is PhoneReply.Ok && r.text.contains(uri(2)) && !r.text.contains("\"lat\""))
        assertTrue(r.text, r.text.contains("'사진 위치' 권한"))
        assertTrue(source.located.isEmpty())
    }

    @Test
    fun placeSearchNeedsLocationPermissionBeforeGeocoding() = runBlocking {
        source.location = false
        val r = tool.find(all, "제주도")
        assertTrue(r.text, r is PhoneReply.PermissionNeeded && r.text.startsWith("권한 필요: 사진 위치"))
        assertEquals(PhotosTool.LOCATION_PERMISSIONS, (r as PhoneReply.PermissionNeeded).permissions)
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun unknownPlaceFails() = runBlocking {
        val r = tool.find(all, "없는나라")
        assertTrue(r.text, r is PhoneReply.Failed && r.text.contains("없는나라"))
        assertEquals(listOf("없는나라"), boxes)
        assertTrue(PhotosTool(source, vision, { prior }).find(all, "제주도") is PhoneReply.Failed)
    }

    @Test
    fun placeSearchFiltersByBoxAndGivesTheDateSpan() = runBlocking {
        val r = tool.find(all, " 제주도 ")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.contains(uri(2)) && !r.text.contains(uri(1) + "\"") && !r.text.contains(uri(3)))
        assertEquals(listOf("제주도"), boxes)
        assertTrue(r.text, r.text.contains("1장") && r.text.contains("2026-07-02 ~ 2026-07-02"))
        // Every row in range had its location read; the scan asked for up to SCAN_MAX rows.
        assertEquals(3, source.located.size)
    }

    @Test
    fun summaryTellsHowManyMatchedInTotal() = runBlocking {
        val one = PhotoQuery.parse(null, null, null, null, 1, ZoneId.of("Asia/Seoul")).getOrThrow()
        val r = tool.find(one)
        assertTrue(r.text, r.text.contains("3장 중 최신 1장"))
        assertTrue(tool.find(all).text.contains("3장"))
    }

    @Test
    fun lookSendsSmallThumbnailsToTheModel() = runBlocking {
        val r = tool.look(listOf(uri(1), uri(2)), "바다 나온 사진은?")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.contains("바다가 보여요") && r.text.contains("1. ${uri(1)}"))
        assertEquals(listOf("바다 나온 사진은?" to 2), vision.asked)
        assertEquals(listOf(uri(1) to 384, uri(2) to 384), source.thumbs)
        assertTrue(tool.look((1..25).map { uri(it) }, "?") is PhoneReply.Failed)
        assertTrue(tool.look(listOf("file:///sdcard/a.jpg"), "?") is PhoneReply.Failed)
        assertTrue(PhotosTool(source, null, { prior }).look(listOf(uri(1)), "?") is PhoneReply.Failed)
    }

    @Test
    fun albumIsMadeOnlyFromAShownPhotoCard() = runBlocking {
        // Without confirmed: a preview card whose [앨범 만들기] is the confirmation.
        val preview = tool.album("제주 노을 2025", listOf(uri(1), uri(2)), confirmed = false) as PhoneReply.Ok
        assertEquals(PhotosCard("제주 노을 2025", listOf(uri(1), uri(2))), preview.card)
        // Confirmed but never shown: refused.
        assertTrue(tool.album("제주", listOf(uri(1)), confirmed = true) is PhoneReply.Failed)
        assertTrue(source.copied.isEmpty())
        prior = listOf(PhotosCard("제주", listOf(uri(1), uri(2), uri(3))))
        assertTrue(tool.album("제주", listOf(uri(1)), confirmed = false).text.startsWith("앨범 미리보기"))
        val made = tool.album(" 제주/노을: 2025 ", listOf(uri(3), uri(1)), confirmed = true)
        assertTrue(made.text, made.text.startsWith("OK") && made.text.contains("Pictures/제주 노을 2025"))
        assertEquals(listOf("제주 노을 2025" to listOf(uri(3), uri(1))), source.copied)
        assertTrue(tool.album(" .. ", listOf(uri(1)), true) is PhoneReply.Failed)
        assertTrue(tool.album("x", listOf("content://contacts/1"), true) is PhoneReply.Failed)
        assertTrue(tool.album("x", listOf("content://media/external/file/1"), true) is PhoneReply.Failed)
    }

    @Test
    fun albumNamesFromOtherAppsAreOneShortLine() {
        assertEquals("a b", PhotosTool.albumLabel(" a\n\u0007b "))
        assertEquals(40, PhotosTool.albumLabel("x".repeat(300))!!.length)
        assertEquals(null, PhotosTool.albumLabel(" \n "))
    }

    @Test
    fun wallpaperAsksFirst() = runBlocking {
        val asked = mutableListOf<String>()
        prior = listOf(PhotosCard("바다", listOf(uri(1), uri(2))))
        assertTrue(tool.wallpaper(uri(2)) { asked += it; false } is PhoneReply.Cancelled)
        // The confirmation names the photo as the card shows it.
        assertEquals(listOf("홈 화면 배경화면 바꾸기: '바다' 카드의 사진 2번"), asked)
        assertTrue(source.wallpapers.isEmpty())
        assertTrue(tool.wallpaper(uri(2)) { true }.text.startsWith("OK"))
        assertEquals(listOf(uri(2)), source.wallpapers)
        assertEquals(1, asked.size)
        assertTrue(tool.wallpaper("content://media/external/video/media/2") { true } is PhoneReply.Failed)
    }

    @Test
    fun modelVisionSendsNumberedImages() = runBlocking {
        var sent: List<InputItem>? = null
        val client = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn {
                sent = input
                assertTrue(tools.isEmpty())
                return ModelTurn("2번", emptyList(), null)
            }
        }
        assertEquals("2번", ModelPhotoVision(client).look("고양이?", listOf(byteArrayOf(1), byteArrayOf(2))))
        val user = sent!!.single() as InputItem.UserText
        assertTrue(user.text, user.text.contains("고양이?") && user.text.contains("2장"))
        assertEquals(listOf("AQ==", "Ag=="), user.images.map { it.base64 })
    }
}
