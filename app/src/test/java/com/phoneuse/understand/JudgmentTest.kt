package com.phoneuse.understand

import com.phoneuse.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgmentTest {
    @After fun restoreLanguage() { Lang.korean = true }

    @Test fun wireRoundTrip() {
        NoticeKind.entries.forEach { assertEquals(it, NoticeKind.of(it.wire)) }
        Sender.entries.forEach { assertEquals(it, Sender.of(it.wire)) }
        ParcelStage.entries.forEach { assertEquals(it, ParcelStage.of(it.wire)) }
        assertNull(NoticeKind.of("payment"))
        assertNull(NoticeKind.of(null))
        assertEquals(ParcelStage.DELIVERED, ParcelStage.of("delivered"))
    }

    @Test fun stageRankNeverGoesBackFromDelivered() {
        assertTrue(ParcelStage.DELIVERED.rank > ParcelStage.OUT_FOR_DELIVERY.rank)
        assertTrue(ParcelStage.OUT_FOR_DELIVERY.rank > ParcelStage.IN_TRANSIT.rank)
        assertTrue(ParcelStage.IN_TRANSIT.rank > ParcelStage.RECEIVED.rank)
    }

    @Test fun moneyMinorUnits() {
        assertEquals(0, Money.minorDigits("KRW"))
        assertEquals(2, Money.minorDigits("USD"))
        assertEquals(0, Money.minorDigits("JPY"))
        assertEquals(2, Money.minorDigits("XYZ"))
    }

    @Test fun moneyFormatsInTheAppLanguage() {
        Lang.korean = true
        assertEquals("₩4,500", Money.format(4500, "KRW"))
        Lang.korean = false
        assertEquals("$12.50", Money.format(1250, "USD"))
    }

    @Test fun moneyKinds() {
        assertTrue(NoticeKind.REWARD.isMoney)
        assertTrue(NoticeKind.BILL_PAYMENT.isMoney)
        assertFalse(NoticeKind.PARCEL.isMoney)
        assertFalse(NoticeKind.FOOD_DELIVERY.isMoney)
    }

    @Test fun stageLabelsFollowTheLanguage() {
        Lang.korean = false
        assertEquals("Out for delivery", ParcelStage.OUT_FOR_DELIVERY.label())
        Lang.korean = true
        assertEquals("배송 출발", ParcelStage.OUT_FOR_DELIVERY.label())
    }
}
