package com.phoneuse.understand

import com.phoneuse.i18n.Lang
import com.phoneuse.phone.StoredNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestGroupsTest {
    @After fun restoreLanguage() { Lang.korean = true }

    private fun n(key: String, pkg: String, title: String, text: String, removedAt: Long? = null) =
        StoredNotification(key, pkg, "앱", title, text, 0L, removedAt = removedAt)

    private fun fact(kind: NoticeKind, sender: Sender, reply: Boolean = false, amount: Long? = null, event: Long? = null) =
        NoticeFact(NoticeJudgment(0, kind, amount, amount?.let { "KRW" }, null, null, null, null, null, sender, reply, "s"), event, null)

    @Test fun alimtalkIsNotReply() {
        val cgv = n("a", "com.kakao.talk", "CGV", "[CGV] 예매가 완료되었습니다")
        val f = fact(NoticeKind.SERVICE, Sender.BUSINESS)
        assertFalse(DigestGroups.needsReply(cgv, f))
        assertEquals(DigestBucket.OTHER, DigestGroups.group(listOf(cgv), mapOf("a" to f), analysisOn = true).keys.single())
    }

    @Test fun personAskingNeedsReplyUntilRead() {
        val mom = n("m", "com.kakao.talk", "엄마", "저녁 먹고 오니?")
        val f = fact(NoticeKind.MESSAGE, Sender.PERSON, reply = true)
        assertTrue(DigestGroups.needsReply(mom, f))
        assertFalse(DigestGroups.needsReply(mom.copy(removedAt = 5), f))
    }

    @Test fun personOkIsNotReply() {
        val ok = n("o", "com.kakao.talk", "민수", "ㅇㅋ")
        assertFalse(DigestGroups.needsReply(ok, fact(NoticeKind.MESSAGE, Sender.PERSON, reply = false)))
    }

    @Test fun tossStepRewardIsMoneyGroupButNotSpending() {
        val step = n("s", "viva.republica.toss", "토스", "만보기 5,000걸음 달성! 10원 받기")
        val pay = n("p", "viva.republica.toss", "토스페이", "결제 완료 | 스타벅스 4,500원")
        val card = n("c", "com.samsung.android.messaging", "삼성카드", "승인 4,500원 스타벅스")
        val facts = mapOf(
            "s" to fact(NoticeKind.REWARD, Sender.BUSINESS, amount = 10, event = 1),
            "p" to fact(NoticeKind.SPEND, Sender.BUSINESS, amount = 4500, event = 2),
            "c" to fact(NoticeKind.SPEND, Sender.BUSINESS, amount = 4500, event = 2),
        )
        val groups = DigestGroups.group(listOf(step, pay, card), facts, analysisOn = true)
        assertEquals(listOf(step, pay, card), groups[DigestBucket.MONEY])
        // The two notices of one payment count once; the reward is not spending.
        assertEquals(4500L to "KRW", DigestGroups.spendTotal(groups.getValue(DigestBucket.MONEY), facts))
    }

    @Test fun parcelIsArrival() {
        val p = n("p", "com.coupang.mobile", "쿠팡", "배송 출발")
        val groups = DigestGroups.group(listOf(p), mapOf("p" to fact(NoticeKind.PARCEL, Sender.BUSINESS)), analysisOn = true)
        assertEquals(setOf(DigestBucket.ARRIVAL), groups.keys)
    }

    @Test fun unanalysedGoesToPending() {
        val groups = DigestGroups.group(listOf(n("x", "p", "t", "x")), emptyMap(), analysisOn = true)
        assertEquals(setOf(DigestBucket.PENDING), groups.keys)
    }

    @Test fun analysisOffReturnsFlatOther() {
        val list = listOf(n("x", "p", "t", "x"), n("m", "com.kakao.talk", "엄마", "저녁?"))
        val groups = DigestGroups.group(list, mapOf("m" to fact(NoticeKind.MESSAGE, Sender.PERSON, reply = true)), analysisOn = false)
        assertEquals(mapOf(DigestBucket.OTHER to list), groups)
    }

    @Test fun groupsComeInBoardOrder() {
        val list = listOf(n("o", "p", "t", "x"), n("m", "p", "t", "x"), n("r", "p", "t", "x"), n("a", "p", "t", "x"), n("w", "p", "t", "x"))
        val facts = mapOf(
            "o" to fact(NoticeKind.PROMO, Sender.BUSINESS),
            "m" to fact(NoticeKind.SPEND, Sender.BUSINESS, amount = 1),
            "r" to fact(NoticeKind.MESSAGE, Sender.PERSON, reply = true),
            "a" to fact(NoticeKind.PARCEL, Sender.BUSINESS),
        )
        assertEquals(
            listOf(DigestBucket.REPLY, DigestBucket.MONEY, DigestBucket.ARRIVAL, DigestBucket.PENDING, DigestBucket.OTHER),
            DigestGroups.group(list, facts, analysisOn = true).keys.toList(),
        )
    }

    @Test fun labelsFollowLanguage() {
        Lang.korean = false
        assertEquals("Needs a reply", DigestBucket.REPLY.label())
        assertEquals("Money", DigestBucket.MONEY.label())
        Lang.korean = true
        assertEquals("답장 필요", DigestBucket.REPLY.label())
        assertEquals("돈", DigestBucket.MONEY.label())
        assertEquals("정리 중", DigestBucket.PENDING.label())
    }

    @Test fun noticeLinePriority() {
        assertEquals("알림 분석이 꺼져 있어요", DigestGroups.notice(analysisOn = false, hasModel = false, limitReached = true, pending = 3))
        assertEquals("AI 모델을 연결하면 알림을 정리해 드려요", DigestGroups.notice(true, hasModel = false, limitReached = true, pending = 3))
        assertEquals("오늘 분석 한도에 도달했어요", DigestGroups.notice(true, true, limitReached = true, pending = 3))
        assertEquals("3개 정리 중", DigestGroups.notice(true, true, false, pending = 3))
        assertEquals(null, DigestGroups.notice(true, true, false, pending = 0))
    }
}
