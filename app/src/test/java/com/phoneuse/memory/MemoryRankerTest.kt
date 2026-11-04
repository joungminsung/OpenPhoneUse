package com.phoneuse.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankerTest {
    private fun mem(id: Long, text: String, at: Long, kind: MemoryKind = MemoryKind.PERSON) =
        Memory(id, text, kind, Confidence.SURE, createdAt = at, updatedAt = at)

    @Test fun ranksByMatchThenRecency() {
        val birthday = mem(1, "엄마 생신은 11월 20일", at = 100)
        val hiking = mem(2, "엄마가 10월부터 등산을 다니심", at = 200)
        val office = mem(3, "회사는 강남역 근처, 보통 9시 출근", at = 300, kind = MemoryKind.PLACE)
        val budget = mem(4, "선물 예산은 보통 10만 원 안쪽", at = 50, kind = MemoryKind.HABIT)
        val ranked = MemoryRanker.rank("엄마 생신 선물 뭐가 좋을까?", listOf(office, hiking, birthday, budget))
        // Two terms match the birthday; one each for hiking and budget (newer first); no match last.
        assertEquals(listOf(1L, 2L, 4L, 3L), ranked.map { it.memory.id })
        assertTrue(ranked.first().score > ranked[1].score)
        assertEquals(0, ranked.last().score)

        // Particles do not hide a match: "엄마가" finds "엄마".
        assertTrue(MemoryRanker.rank("엄마가 뭐 좋아해?", listOf(birthday)).single().score > 0)

        // Equal scores: newer first.
        val a = mem(10, "커피는 라떼", at = 1)
        val b = mem(11, "커피는 아이스 아메리카노", at = 2)
        assertEquals(listOf(11L, 10L), MemoryRanker.rank("커피 사러 갈까", listOf(a, b)).map { it.memory.id })
    }

    @Test fun injectionRespectsBudget() {
        // 1,500 memories, every one of them matching the question.
        val many = (1..1500L).map { mem(it, "엄마 관련 기억 $it " + "가".repeat(120), at = it) }
        val top = MemoryRanker.top("엄마 선물", many, n = 12, budgetChars = 2000)
        assertTrue(top.size <= 12)
        assertTrue(top.isNotEmpty())
        val block = MemoryRanker.block(top)
        assertTrue("block was ${block.length}", block.length <= 2000)
        // The newest matches win.
        assertEquals(1500L, top.first().memory.id)

        // Long memories: the budget, not n, is the limit.
        val long = (1..50L).map { mem(it, "엄마 " + "나".repeat(600), at = it) }
        val fewer = MemoryRanker.top("엄마", long, n = 12, budgetChars = 2000)
        assertTrue(fewer.size in 1..3)
        assertTrue(MemoryRanker.block(fewer).length <= 2000)

        // Nothing matches: nothing is injected (no recency fill).
        val unrelated = (1..40L).map { mem(it, "기억 $it", at = it) }
        assertTrue(MemoryRanker.top("날씨", unrelated, n = 12, budgetChars = 2000).isEmpty())

        // Only matching memories, never guesses: a guess waits for the user's "맞아요".
        val guess = mem(99, "엄마는 꽃을 좋아함", at = 9999).copy(confidence = Confidence.GUESS)
        val sure = mem(98, "엄마 생신은 11월 20일", at = 1)
        val off = mem(97, "회사는 강남역", at = 2)
        assertEquals(listOf(98L), MemoryRanker.top("엄마 선물", listOf(guess, sure, off)).map { it.memory.id })

        // A zero budget injects nothing.
        assertTrue(MemoryRanker.top("엄마", many, n = 12, budgetChars = 0).isEmpty())
    }

    @Test fun ftsQueryUsesPrefixTermsWithoutParticlesOrOperators() {
        assertEquals("엄마* OR 생신*", MemoryText.ftsQuery("엄마가 생신은?"))
        // Quotes, operators and punctuation never reach the FTS parser.
        val q = MemoryText.ftsQuery("\"OR\" NEAR -drop* (x)")!!
        assertTrue(q, q.none { it == '"' || it == '(' || it == '-' })
        assertTrue(q, "OR*" !in q && "NEAR*" !in q)
        assertEquals(null, MemoryText.ftsQuery("? !"))
    }
}
