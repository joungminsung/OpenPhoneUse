package com.phoneuse.ui.chat

import com.phoneuse.phone.AlarmTool
import com.phoneuse.phone.CallTool
import com.phoneuse.phone.ContactAdder
import com.phoneuse.phone.Recipient
import com.phoneuse.phone.SmsTool
import com.phoneuse.risk.RiskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

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

    @Test
    fun smsToAContactNamesThePerson() {
        val c = confirmCopy(RiskCategory.SEND, SmsTool.confirmSummary("김민수 (01012345678)", "곧 가"))
        assertEquals("김민수 (01012345678)에게 이 문자를 보낼까요?", c.question)
        assertEquals(listOf("곧 가"), c.details)
        assertEquals("받는 사람 김민수 (01012345678) · 3자", c.note)
    }

    @Test
    fun callCardNamesWhoIsCalled() {
        val named = confirmCopy(RiskCategory.SEND, CallTool.confirmSummary(Recipient.Found("01012345678", "김민수", "민수")))
        assertEquals("전화 확인", named.kicker)
        assertEquals("김민수에게 전화할까요?", named.question)
        assertEquals(listOf("01012345678"), named.details)
        assertEquals("전화 걸기", named.allowLabel)
        val bare = confirmCopy(RiskCategory.SEND, CallTool.confirmSummary(Recipient.Found("01012345678")))
        assertEquals("01012345678에 전화할까요?", bare.question)
        assertTrue(bare.details.isEmpty())
        // The marker belongs to SEND: the same summary under another category is the plain card.
        assertEquals("변경 확인", confirmCopy(RiskCategory.CHANGE, CallTool.confirmSummary(Recipient.Found("01012345678"))).kicker)
    }

    @Test
    fun alarmAndTimerCardsSayWhatWillBeSet() {
        val alarm = confirmCopy(RiskCategory.CHANGE, AlarmTool.alarmSummary("07:00", listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), "기상"))
        assertEquals("알람 확인", alarm.kicker)
        assertEquals("07:00에 알람을 맞출까요?", alarm.question)
        assertEquals(listOf("매주 월, 금", "기상"), alarm.details)
        assertEquals("맞추기", alarm.allowLabel)
        assertTrue(confirmCopy(RiskCategory.CHANGE, AlarmTool.alarmSummary("07:00", emptyList(), "")).details.isEmpty())

        val timer = confirmCopy(RiskCategory.CHANGE, AlarmTool.timerSummary(180, "라면"))
        assertEquals("타이머 확인", timer.kicker)
        assertEquals("3분 타이머를 시작할까요?", timer.question)
        assertEquals(listOf("라면"), timer.details)
        assertEquals("시작", timer.allowLabel)

        // Only the tool's own marked summary: the same words typed by anything else stay the plain card.
        assertEquals("변경 확인", confirmCopy(RiskCategory.CHANGE, "알람 설정: 07:00").kicker)
    }

    @Test
    fun newContactCardShowsTheNameAndNumber() {
        val c = confirmCopy(RiskCategory.CHANGE, ContactAdder.confirmSummary("김민수", "01012345678"))
        assertEquals("연락처 저장 확인", c.kicker)
        assertEquals("이 연락처를 저장할까요?", c.question)
        assertEquals(listOf("김민수", "01012345678"), c.details)
        assertEquals("저장", c.allowLabel)
        // An email is one more line.
        val mail = confirmCopy(RiskCategory.CHANGE, ContactAdder.confirmSummary("김민수", "01012345678", "ms@example.com"))
        assertEquals(listOf("김민수", "01012345678", "ms@example.com"), mail.details)
    }
}
