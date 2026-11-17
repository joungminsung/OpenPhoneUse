package com.phoneuse.understand

import com.phoneuse.app.AuthMode
import com.phoneuse.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderstandTextTest {
    @After fun restoreLanguage() { Lang.korean = true }

    @Test fun switchExplainsWhatIsSent() {
        Lang.korean = true
        assertEquals("알림 내용 분석", UnderstandText.TITLE)
        assertEquals("결제, 택배, 답장할 메시지를 정리하려고 알림 내용을 설정된 AI 모델로 보내요. 인증번호는 보내기 전에 가려요.", UnderstandText.subtitle(AuthMode.CHATGPT))
        Lang.korean = false
        assertTrue(UnderstandText.subtitle(AuthMode.CLAUDE).startsWith("Sends notification text to your AI model"))
    }

    @Test fun providersWithoutALightModelSayTheMainModelIsUsed() {
        assertTrue(UnderstandText.subtitle(AuthMode.GEMINI).endsWith("기본 모델로 분석해요."))
        assertTrue(UnderstandText.subtitle(AuthMode.COMPATIBLE).endsWith("기본 모델로 분석해요."))
        assertFalse(UnderstandText.subtitle(AuthMode.CHATGPT).contains("기본 모델"))
    }

    @Test fun noticeIsShownOnceWhileNotificationAccessIsOn() {
        assertTrue(UnderstandText.showNotice(accessOn = true, shown = false))
        assertFalse(UnderstandText.showNotice(accessOn = true, shown = true))
        assertFalse(UnderstandText.showNotice(accessOn = false, shown = false))
    }
}
