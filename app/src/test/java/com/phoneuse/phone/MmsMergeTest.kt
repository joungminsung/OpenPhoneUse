package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsMergeTest {
    @Test fun mmsRowJoinsTextPartsAndUsesSeconds() {
        val m = MmsMerge.message(1_793_000_000, MmsMerge.BOX_INBOX, " 15881255 ", listOf("[Web발신]", null, "  ", "운송장번호 123456789012"))
        assertEquals(SmsMessage("15881255", "[Web발신]\n운송장번호 123456789012", 1_793_000_000_000, incoming = true), m)
        assertEquals(false, MmsMerge.message(1, MmsMerge.BOX_SENT, null, listOf("hi"))?.incoming)
    }

    @Test fun mmsWithoutTextOrOutsideInboxAndSentIsSkipped() {
        assertNull(MmsMerge.message(1, MmsMerge.BOX_INBOX, "1", listOf(null, " ")))
        assertNull(MmsMerge.message(1, 3, "1", listOf("draft")))
    }

    @Test fun mergeSortsByDateFiltersAndCaps() {
        val sms = listOf(SmsMessage("a", "택배 출발", 3000, true), SmsMessage("b", "점심 뭐 먹어", 1000, true))
        val mms = listOf(SmsMessage("c", "[CJ대한통운] 택배 도착", 2000, true), SmsMessage("d", "오래된 택배", 10, true))
        assertEquals(listOf("a", "c", "b"), MmsMerge.merge(sms, mms, since = 100, query = null, limit = 10).map { it.address })
        assertEquals(listOf("a", "c"), MmsMerge.merge(sms, mms, since = 100, query = "택배", limit = 10).map { it.address })
        assertEquals(listOf("a"), MmsMerge.merge(sms, mms, since = 100, query = null, limit = 1).map { it.address })
        assertEquals(listOf("c"), MmsMerge.merge(sms, mms, since = 100, query = "cj", limit = 10).map { it.address })
    }
}
