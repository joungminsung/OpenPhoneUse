package com.phoneuse.understand

import com.phoneuse.model.InputItem
import com.phoneuse.phone.StoredNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class UnderstandPromptTest {
    private fun one(item: String) = "{\"items\":[$item]}"

    @Test fun parsesFencedJsonAndConvertsAmount() {
        val r = UnderstandPrompt.parse(
            "```json\n" + one("{\"i\":0,\"kind\":\"spend\",\"amount\":\"4,500\",\"currency\":\"KRW\",\"merchant\":\"스타벅스\",\"instrument\":\"삼성카드\",\"sender\":\"business\",\"needs_reply\":false,\"summary\":\"스타벅스 4,500원\"}") + "\n```",
            1,
        )!!
        val j = r[0]!!
        assertEquals(NoticeKind.SPEND, j.kind)
        assertEquals(4500L, j.amountMinor)
        assertEquals("KRW", j.currency)
        assertEquals("스타벅스", j.merchant)
        assertEquals("삼성카드", j.instrument)
        assertEquals(Sender.BUSINESS, j.sender)
    }

    @Test fun usdAmountInCents() {
        val r = UnderstandPrompt.parse(one("{\"i\":0,\"kind\":\"spend\",\"amount\":\"12.50\",\"currency\":\"usd\",\"sender\":\"business\",\"needs_reply\":false,\"summary\":\"\"}"), 1)!!
        assertEquals(1250L, r[0]!!.amountMinor)
        assertEquals("USD", r[0]!!.currency)
    }

    @Test fun dropsUnknownKindUnknownSenderAndOutOfRangeIndex() {
        val r = UnderstandPrompt.parse(
            "{\"items\":[" +
                "{\"i\":0,\"kind\":\"payment\",\"sender\":\"business\",\"needs_reply\":false}," +
                "{\"i\":1,\"kind\":\"other\",\"sender\":\"robot\",\"needs_reply\":false}," +
                "{\"i\":5,\"kind\":\"other\",\"sender\":\"system\",\"needs_reply\":false}]}",
            2,
        )!!
        assertTrue(r.isEmpty())
    }

    @Test fun unknownParcelStageDropsTheItem() {
        val r = UnderstandPrompt.parse(one("{\"i\":0,\"kind\":\"parcel\",\"parcel\":{\"carrier\":\"CJ대한통운\",\"stage\":\"flying\"},\"sender\":\"business\",\"needs_reply\":false}"), 1)!!
        assertTrue(r.isEmpty())
    }

    @Test fun parcelFields() {
        val r = UnderstandPrompt.parse(one("{\"i\":0,\"kind\":\"parcel\",\"parcel\":{\"carrier\":\"CJ대한통운\",\"tracking\":\"1234-5678-9012\",\"item\":\"운동화\",\"stage\":\"out_for_delivery\"},\"sender\":\"business\",\"needs_reply\":false}"), 1)!!
        assertEquals(ParcelInfo("CJ대한통운", "123456789012", "운동화", ParcelStage.OUT_FOR_DELIVERY), r[0]!!.parcel)
    }

    @Test fun sameAsEventAndItemAndCancels() {
        val r = UnderstandPrompt.parse(
            "{\"items\":[" +
                "{\"i\":0,\"kind\":\"spend\",\"amount\":\"4500\",\"currency\":\"KRW\",\"same_as\":{\"event\":12},\"sender\":\"business\",\"needs_reply\":false}," +
                "{\"i\":1,\"kind\":\"spend\",\"amount\":\"4500\",\"currency\":\"KRW\",\"same_as\":{\"item\":0},\"sender\":\"business\",\"needs_reply\":false}," +
                "{\"i\":2,\"kind\":\"refund\",\"amount\":\"4500\",\"currency\":\"KRW\",\"cancels\":12,\"sender\":\"business\",\"needs_reply\":false}]}",
            3,
        )!!
        assertEquals(Ref.Event(12), r[0]!!.sameAs)
        assertEquals(Ref.Batch(0), r[1]!!.sameAs)
        assertEquals(Ref.Event(12), r[2]!!.cancels)
    }

    @Test fun cancelsAnItemOfTheSameBatch() {
        val r = UnderstandPrompt.parse(one("{\"i\":0,\"kind\":\"refund\",\"amount\":\"100,000\",\"currency\":\"KRW\",\"cancels\":{\"item\":1},\"sender\":\"business\",\"needs_reply\":false}"), 2)!!
        assertEquals(Ref.Batch(1), r[0]!!.cancels)
        assertTrue(UnderstandPrompt.INSTRUCTIONS.contains("\"cancels\":{\"event\""))
    }

    @Test fun badAmountLeavesItNull() {
        val r = UnderstandPrompt.parse(one("{\"i\":0,\"kind\":\"spend\",\"amount\":\"lots\",\"currency\":\"KRW\",\"sender\":\"business\",\"needs_reply\":false}"), 1)!!
        assertNull(r[0]!!.amountMinor)
    }

    @Test fun notJsonIsNull() {
        assertNull(UnderstandPrompt.parse("I cannot help with that", 1))
        assertNull(UnderstandPrompt.parse(null, 1))
        assertNull(UnderstandPrompt.parse("{\"nope\":1}", 1))
    }

    @Test fun inputListsEveryNotificationWithIndexAndRecentEvents() {
        val batch = listOf(
            StoredNotification("a", "com.kakao.talk", "카카오톡", "엄마", "저녁 먹고 오니?", 1_700_000_000_000L, false),
            StoredNotification("b", "viva.republica.toss", "토스", "토스페이", "결제 완료 | 스타벅스 4,500원", 1_700_000_060_000L, true),
        )
        val recent = listOf(RecentEvent(12, NoticeKind.SPEND, 4500, "KRW", "스타벅스", "삼성카드", 1_700_000_000_000L, null, null))
        val text = UnderstandPrompt.input(batch, recent, ZoneId.of("Asia/Seoul"), "ko").joinToString("\n") { (it as InputItem.UserText).text }
        assertTrue(text.contains("[0]"))
        assertTrue(text.contains("[1]"))
        assertTrue(text.contains("저녁 먹고 오니?"))
        assertTrue(text.contains("viva.republica.toss"))
        assertTrue(text.contains("group"))
        assertTrue(text.contains("event 12"))
        assertTrue(text.contains("2023-11-15 07:13"))
    }

    @Test fun instructionsNameEveryKindAndStage() {
        NoticeKind.entries.forEach { assertTrue(it.wire, UnderstandPrompt.INSTRUCTIONS.contains(it.wire)) }
        ParcelStage.entries.forEach { assertTrue(it.wire, UnderstandPrompt.INSTRUCTIONS.contains(it.wire)) }
        Sender.entries.forEach { assertTrue(it.wire, UnderstandPrompt.INSTRUCTIONS.contains(it.wire)) }
    }
}
