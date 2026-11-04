package com.phoneuse.memory

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The behaviour every [MemoryStore] / [AppTipStore] must have; run against the in-memory implementation. */
abstract class MemoryStoreContractTest {
    protected abstract fun newStore(now: () -> Long): Pair<MemoryStore, AppTipStore>

    private var clock = 1_000L
    private val stores by lazy { newStore { clock } }
    private val store get() = stores.first
    private val tips get() = stores.second

    @Test fun addsGetsAndListsNewestFirst() = runTest {
        val a = store.add(NewMemory("엄마 생신은 11월 20일", MemoryKind.PERSON, date = "11월 20일", conversationId = 7, origin = "엄마 생신 선물"))
        assertTrue(a.created)
        clock += 10
        val b = store.add(NewMemory("매운 음식 좋아함", MemoryKind.ME)).id
        val got = store.get(a.id)!!
        assertEquals("엄마 생신은 11월 20일", got.text)
        assertEquals(MemoryKind.PERSON, got.kind)
        assertEquals(Confidence.SURE, got.confidence)
        assertEquals("11월 20일", got.date)
        assertEquals(7L, got.conversationId)
        assertEquals("엄마 생신 선물", got.origin)
        assertEquals(listOf(b, a.id), store.observe().first().map { it.id })
    }

    @Test fun sameTextUpdatesInsteadOfDuplicating() = runTest {
        val a = store.add(NewMemory("회사는 강남역 근처", MemoryKind.PLACE, Confidence.GUESS)).id
        clock += 5
        val again = store.add(NewMemory("  회사는   강남역 근처 ", MemoryKind.ME, Confidence.SURE))
        assertEquals(a, again.id)
        // Nothing new was made, so the caller must not offer to undo it.
        assertFalse(again.created)
        assertEquals(1, store.observe().first().size)
        // Saying it for sure upgrades a guess; the kind already chosen stays.
        assertEquals(Confidence.SURE, store.get(a)!!.confidence)
        assertEquals(MemoryKind.PLACE, store.get(a)!!.kind)
    }

    @Test fun updateConfirmAndDelete() = runTest {
        val id = store.add(NewMemory("선물 예산은 보통 10만 원 안쪽", MemoryKind.HABIT, Confidence.GUESS)).id
        assertTrue(store.update(id, "선물 예산은 15만 원 안쪽", MemoryKind.HABIT))
        assertEquals("선물 예산은 15만 원 안쪽", store.get(id)!!.text)
        // Editing a guess confirms it.
        assertEquals(Confidence.SURE, store.get(id)!!.confidence)
        assertTrue(store.confirm(id))
        assertEquals(Confidence.SURE, store.get(id)!!.confidence)
        assertTrue(store.delete(id))
        assertNull(store.get(id))
        assertFalse(store.delete(id))
        assertFalse(store.update(id, "x", MemoryKind.ME))
    }

    @Test fun editToAnExistingTextMerges() = runTest {
        val keep = store.add(NewMemory("오이는 싫어함", MemoryKind.ME)).id
        clock += 1
        val other = store.add(NewMemory("오이 싫어", MemoryKind.HABIT, Confidence.GUESS)).id
        assertTrue(store.update(other, "오이는 싫어함", MemoryKind.HABIT))
        val all = store.observe().first()
        assertEquals(1, all.size)
        assertEquals(keep, all.single().id)
        assertEquals(MemoryKind.HABIT, all.single().kind)
        assertEquals(Confidence.SURE, all.single().confidence)
    }

    @Test fun restorePutsADeletedMemoryBack() = runTest {
        val id = store.add(NewMemory("등산 스틱 주문하기", MemoryKind.TODO, Confidence.GUESS, conversationId = 3, origin = "등산", due = "2026-11-15")).id
        val gone = store.get(id)!!
        store.delete(id)
        val back = store.restore(gone)
        assertTrue(back.created)
        val got = store.get(back.id)!!
        assertEquals(gone.text, got.text)
        assertEquals(MemoryKind.TODO, got.kind)
        assertEquals(Confidence.GUESS, got.confidence)
        assertEquals("2026-11-15", got.due)
        assertEquals(3L, got.conversationId)
    }

    @Test fun todoKeepsDueDate() = runTest {
        val id = store.add(NewMemory("등산 스틱 주문하기", MemoryKind.TODO, due = "2026-11-15")).id
        assertEquals("2026-11-15", store.get(id)!!.due)
    }

    @Test fun searchRanksMatchesAndCandidatesIncludeThem() = runTest {
        store.add(NewMemory("회사는 강남역 근처", MemoryKind.PLACE))
        clock += 1
        val mom = store.add(NewMemory("엄마가 10월부터 등산을 다니심", MemoryKind.PERSON)).id
        clock += 1
        store.add(NewMemory("오이는 싫어함", MemoryKind.ME))
        assertEquals(listOf(mom), store.search("엄마 선물").map { it.id })
        assertTrue(store.candidates("엄마 선물").any { it.id == mom })
        // Candidates are ranked matches only.
        assertEquals(listOf(mom), store.candidates("엄마 선물").map { it.id })
        // A blank query lists recent memories.
        assertEquals(3, store.search("", limit = 10).size)
    }

    @Test fun deleteAllAndDeleteAllTipsAreSeparate() = runTest {
        store.add(NewMemory("a b", MemoryKind.ME))
        tips.add("com.kakao.talk", "엄마에게 사진 보내기", "채팅 열기 → + → 앨범")
        store.deleteAll()
        assertTrue(store.observe().first().isEmpty())
        assertEquals(1, tips.top("com.kakao.talk", "사진", 3).size)
        tips.deleteAllTips()
        assertTrue(tips.top("com.kakao.talk", "사진", 3).isEmpty())
    }

    @Test fun appTipsTopThreeForThePackageBestMatchFirst() = runTest {
        val pkg = "com.coupang.mobile"
        tips.add(pkg, "생수 주문", "검색 → 생수 → 장바구니")
        clock += 1
        tips.add(pkg, "주문 내역 보기", "마이쿠팡 → 주문목록")
        clock += 1
        tips.add(pkg, "로켓프레시 우유 주문", "검색 → 우유 → 장바구니")
        clock += 1
        tips.add(pkg, "배송 조회", "마이쿠팡 → 배송조회")
        tips.add("com.other", "생수 주문", "x")
        val top = tips.top(pkg, "생수 2박스 주문해줘", 3)
        assertEquals(3, top.size)
        assertTrue(top.all { it.packageName == pkg })
        assertEquals("생수 주문", top.first().task)
        // Same task again replaces the old steps rather than adding a row.
        tips.add(pkg, "생수 주문", "검색 → 생수 2L → 바로구매")
        val again = tips.top(pkg, "생수 주문", 10)
        assertEquals(4, again.size)
        assertEquals("검색 → 생수 2L → 바로구매", again.first().steps)
        assertNotNull(tips.top("com.none", "x", 3))
    }

    @Test fun tipTaskIsAShortSummaryWithoutQuotedText() {
        assertEquals("엄마에게 … 보냈어요.", AppTips.task("엄마에게 \"내일 7시에 봬요\" 보냈어요. 답장은 아직 없어요."))
        assertNull(AppTips.task("  \n "))
        assertEquals(80, AppTips.task("가".repeat(200))!!.length)
    }
}

class InMemoryMemoryStoreTest : MemoryStoreContractTest() {
    override fun newStore(now: () -> Long): Pair<MemoryStore, AppTipStore> =
        InMemoryMemoryStore(now) to InMemoryAppTipStore(now)
}
