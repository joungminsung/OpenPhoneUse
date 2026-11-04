package com.phoneuse.ui.chat

import com.phoneuse.risk.RiskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmCopyTest {
    @Test
    fun trashSummaryBecomesPathList() {
        val copy = confirmCopy(RiskCategory.DELETE, "휴지통으로 이동: Download/a.apk, Download/b.apk")
        assertEquals("삭제 확인", copy.kicker)
        assertEquals("파일 2개를 휴지통으로 옮길까요?", copy.question)
        assertEquals(listOf("Download/a.apk", "Download/b.apk"), copy.details)
        assertTrue(copy.monoDetails)
        assertEquals("휴지통으로 옮기기", copy.allowLabel)
    }

    @Test
    fun trashSummaryCountsHiddenPaths() {
        val copy = confirmCopy(RiskCategory.DELETE, "휴지통으로 이동: a, b(외 3개)")
        assertEquals("파일 5개를 휴지통으로 옮길까요?", copy.question)
        assertEquals(listOf("a", "b", "외 3개"), copy.details)
    }

    @Test
    fun otherSummariesShownVerbatim() {
        val copy = confirmCopy(RiskCategory.MONEY, "tap: 결제하기")
        assertEquals("결제 확인", copy.kicker)
        assertEquals(listOf("tap: 결제하기"), copy.details)
        assertFalse(copy.monoDetails)
        assertEquals("결제 허용", copy.allowLabel)
        assertEquals("보내기", confirmCopy(RiskCategory.SEND, "입력 후 전송: \"hi\"").allowLabel)
    }

    @Test
    fun smsSendShowsTheNumberAndTheWholeText() {
        val body = "첫 줄\n" + "가".repeat(200) + "\n마지막 줄"
        val copy = confirmCopy(RiskCategory.SEND, com.phoneuse.phone.SmsTool.confirmSummary("01012345678", body))
        assertEquals("01012345678에 이 문자를 보낼까요?", copy.question)
        assertEquals(listOf(body), copy.details)
        assertFalse(copy.monoDetails)
        assertEquals("보내기", copy.allowLabel)
    }

    @Test
    fun onlyTheSmsToolsMarkedSummaryIsAnSmsCard() {
        val plain = confirmCopy(RiskCategory.SEND, "문자 보내기 → 01099998888\n안녕")
        assertEquals("이대로 보낼까요?", plain.question)
    }
}
