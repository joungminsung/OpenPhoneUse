package com.phoneuse.understand

import com.phoneuse.phone.StoredNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgmentCheckTest {
    @Test fun koreanForms() {
        assertTrue(JudgmentCheck.amountAppears(4500, "KRW", "승인 4,500원 일시불"))
        assertTrue(JudgmentCheck.amountAppears(4500, "KRW", "결제 완료 | 스타벅스 4500"))
        assertTrue(JudgmentCheck.amountAppears(100000, "KRW", "10만원 확인"))
        assertTrue(JudgmentCheck.amountAppears(100000, "KRW", "10만 원을 보낼까요?"))
        assertTrue(JudgmentCheck.amountAppears(12000, "KRW", "1.2만원 결제"))
        assertTrue(JudgmentCheck.amountAppears(3000, "KRW", "3천원 적립"))
        assertTrue(JudgmentCheck.amountAppears(4500, "KRW", "₩4,500 paid"))
    }

    @Test fun numbersInsideOtherNumbersDoNotCount() {
        assertFalse(JudgmentCheck.amountAppears(4500, "KRW", "승인 14,500원"))
        assertFalse(JudgmentCheck.amountAppears(4500, "KRW", "승인 4,5001원"))
        assertFalse(JudgmentCheck.amountAppears(4500, "KRW", "만보기 5,000걸음 달성! 10원 받기"))
    }

    @Test fun usdForms() {
        assertTrue(JudgmentCheck.amountAppears(1250, "USD", "Paid USD 12.50"))
        assertTrue(JudgmentCheck.amountAppears(1250, "USD", "You paid $12.50 to Uber"))
        assertTrue(JudgmentCheck.amountAppears(1200, "USD", "Total: $12"))
        assertFalse(JudgmentCheck.amountAppears(1250, "USD", "You paid $112.50"))
    }

    private val step = StoredNotification("k", "viva.republica.toss", "토스", "토스", "만보기 5,000걸음 달성! 10원 받기", 0L)

    @Test fun inventedAmountBecomesOther() {
        val j = NoticeJudgment(0, NoticeKind.SPEND, 450000, "KRW", "토스", null, Ref.Event(3), null, null, Sender.BUSINESS, false, "s")
        val c = JudgmentCheck.clean(j, step)
        assertEquals(NoticeKind.OTHER, c.kind)
        assertNull(c.amountMinor); assertNull(c.currency); assertNull(c.merchant); assertNull(c.sameAs)
        assertEquals("s", c.summary)
    }

    @Test fun moneyKindWithoutAmountBecomesOther() {
        val j = NoticeJudgment(0, NoticeKind.REWARD, null, null, null, null, null, null, null, Sender.BUSINESS, false, "")
        assertEquals(NoticeKind.OTHER, JudgmentCheck.clean(j, step).kind)
    }

    @Test fun amountInTheTitleCounts() {
        val n = StoredNotification("k", "com.x", "x", "4,500원 결제 완료", "스타벅스", 0L)
        val j = NoticeJudgment(0, NoticeKind.SPEND, 4500, "KRW", "스타벅스", null, null, null, null, Sender.BUSINESS, false, "")
        assertEquals(j, JudgmentCheck.clean(j, n))
    }

    @Test fun nonMoneyKindsPassUntouched() {
        val j = NoticeJudgment(0, NoticeKind.MESSAGE, null, null, null, null, null, null, null, Sender.PERSON, true, "")
        assertEquals(j, JudgmentCheck.clean(j, step))
    }
}
