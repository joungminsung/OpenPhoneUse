package com.phoneuse.agent

import com.phoneuse.screen.ScreenSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word checks that let a call, an alarm, a timer or a new contact go without its card. */
class UserAskedTest {
    private fun said(vararg words: String, trust: Boolean = true): ToolExecutor {
        val ex = ToolExecutor(
            FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))),
            FakeUserGate(),
            trustUserAuthorized = trust,
        )
        words.forEach(ex::userSaid)
        return ex
    }

    private val number = "01012345678"

    @Test
    fun aCallNeedsACallWordAndTheRecipient() {
        val ex = said("엄마한테 전화 걸어줘")
        assertTrue(ex.userAskedCall(listOf("엄마"), number))
        assertFalse(ex.userAskedCall(listOf("아빠"), number))
        // One letter is too little to be a name.
        assertFalse(ex.userAskedCall(listOf("엄"), number))
        assertFalse(ex.userAskedCall(emptyList(), number))
        // The recipient alone is not a request to call.
        assertFalse(said("엄마한테 문자 보내줘").userAskedCall(listOf("엄마"), number))
        assertFalse(said().userAskedCall(listOf("엄마"), number))
    }

    @Test
    fun aCallByNumber() {
        val ex = said("010-1234-5678로 전화해줘")
        assertTrue(ex.userAskedCall(emptyList(), number))
        assertTrue(ex.userAskedCall(emptyList(), "010 1234 5678"))
        assertFalse(ex.userAskedCall(emptyList(), "01099998888"))
        // A short number counts only as a number of its own.
        assertTrue(said("114에 전화해줘").userAskedCall(emptyList(), "114"))
        assertFalse(said("1145번 버스 타고 전화해줘").userAskedCall(emptyList(), "114"))
        assertFalse(said("전화해줘").userAskedCall(emptyList(), ""))
        // With its country code it is the same line.
        assertTrue(said("+82 10-1234-5678로 전화해줘").userAskedCall(emptyList(), number))
        // Digits spread over the message are not one number.
        assertFalse(said("12시 34분 56초에 78번 전화해줘").userAskedCall(emptyList(), "12345678"))
    }

    @Test
    fun aNameIsAWordOfItsOwn() {
        val full = said("김민수한테 전화해줘")
        assertTrue(full.userAskedCall(listOf("김민수"), number))
        // "민수" could be 박민수: the user named 김민수.
        assertFalse(full.userAskedCall(listOf("민수"), number))
        val latin = said("call Minsu")
        assertTrue(latin.userAskedCall(listOf("Minsu"), number))
        assertFalse(latin.userAskedCall(listOf("Min"), number))
    }

    @Test
    fun askingAboutCallsIsNotAskingToCall() {
        assertFalse(said("엄마 전화번호 알려줘").userAskedCall(listOf("엄마"), number))
        assertFalse(said("엄마 통화 기록 보여줘").userAskedCall(listOf("엄마"), number))
        assertFalse(said("엄마한테 부재중 전화 왔어?").userAskedCall(listOf("엄마"), number))
        assertFalse(said("what's mom's phone number").userAskedCall(listOf("Mom"), number))
        assertFalse(said("엄마한테 전화했어?").userAskedCall(listOf("엄마"), number))
        assertFalse(said("who called mom").userAskedCall(listOf("Mom"), number))
        // Asking not to call.
        assertFalse(said("엄마한테 전화하지 마").userAskedCall(listOf("엄마"), number))
        assertFalse(said("don't call mom").userAskedCall(listOf("Mom"), number))
        assertFalse(said("dont call mom").userAskedCall(listOf("Mom"), number))
        // Both in one message: the call is asked for.
        assertTrue(said("엄마 전화번호 찾아서 전화 걸어줘").userAskedCall(listOf("엄마"), number))
    }

    @Test
    fun englishCallWordsAreWholeWords() {
        assertTrue(said("Call Mom please").userAskedCall(listOf("Mom"), number))
        assertTrue(said("dial 010 1234 5678").userAskedCall(emptyList(), number))
        assertFalse(said("recall what mom said").userAskedCall(listOf("Mom"), number))
        assertFalse(said("dialog with mom").userAskedCall(listOf("Mom"), number))
        // Walking is not calling.
        assertFalse(said("걸어서 10분 거리 엄마 집").userAskedCall(listOf("엄마"), number))
    }

    @Test
    fun alarmsAndTimersAreToldApart() {
        assertTrue(said("내일 7시에 깨워줘").userAskedAlarm())
        assertTrue(said("wake me at 7").userAskedAlarm())
        assertFalse(said("알림 좀 꺼줘").userAskedAlarm())
        assertFalse(said("내일 7시 알람").userAskedTimer())
        assertTrue(said("3분 타이머").userAskedTimer())
        assertTrue(said("set a 5 minute timer").userAskedTimer())
        assertFalse(said("3분 타이머").userAskedAlarm())
    }

    @Test
    fun stoppingOrLookingIsNotSetting() {
        assertFalse(said("7시 알람 꺼줘").userAskedAlarm())
        assertFalse(said("다음 알람 언제야").userAskedAlarm())
        assertFalse(said("cancel my alarm").userAskedAlarm())
        assertFalse(said("what alarms do i have").userAskedAlarm())
        assertFalse(said("타이머 취소해줘").userAskedTimer())
        assertFalse(said("how long is left on the timer").userAskedTimer())
        // Both in one message: the new one is asked for.
        assertTrue(said("7시 알람 끄고 8시에 깨워줘").userAskedAlarm())
    }

    @Test
    fun afterThirdPartyTextTheTimeIsNoLongerSurelyTheUsers() {
        val ex = said("엄마한테 전화 걸어줘", "7시 알람", "3분 타이머")
        assertTrue(ex.userAskedAlarm())
        assertTrue(ex.userAskedTimer())
        ex.markThirdParty()
        assertFalse(ex.userAskedAlarm())
        assertFalse(ex.userAskedTimer())
        // The recipient of a call is in the user's own words, whatever was read since.
        assertTrue(ex.userAskedCall(listOf("엄마"), number))
    }

    @Test
    fun aContactTheUserTyped() {
        val ex = said("김민수 010-1234-5678 저장해줘")
        assertTrue(ex.userGaveContact("김민수", number, null))
        assertFalse(ex.userGaveContact("김민수", "01099998888", null))
        // A name the user did not write is the model's choice.
        assertFalse(ex.userGaveContact("박민수", number, null))
        // So is an address.
        assertFalse(ex.userGaveContact("김민수", number, "ms@example.com"))
        assertTrue(said("김민수 010-1234-5678 MS@example.com 저장해줘").userGaveContact("김민수", number, "ms@example.com"))
        // Too short to be told apart from any other digits.
        assertFalse(said("안내 114 저장해줘").userGaveContact("안내", "114", null))
    }

    @Test
    fun neverInARunTheUserDidNotType() {
        val ex = said("엄마한테 전화 걸어줘", "7시 알람", "3분 타이머", "김민수 010-1234-5678 저장", trust = false)
        assertFalse(ex.userAskedCall(listOf("엄마"), number))
        assertFalse(ex.userAskedAlarm())
        assertFalse(ex.userAskedTimer())
        assertFalse(ex.userGaveContact("김민수", number, null))
    }
}
