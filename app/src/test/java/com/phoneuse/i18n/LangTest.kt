package com.phoneuse.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LangTest {
    private val before = Lang.korean

    @After fun restore() {
        Lang.korean = before
    }

    @Test fun testsRunInKorean() {
        assertTrue(Lang.korean)
        assertEquals("검색", tr("Search", "검색"))
    }

    @Test fun anyOtherLanguageIsEnglish() {
        assertTrue(Lang.isKorean(Locale.KOREA))
        assertFalse(Lang.isKorean(Locale.US))
        assertFalse(Lang.isKorean(Locale.JAPAN))
        Lang.korean = false
        assertEquals("Search", tr("Search", "검색"))
        assertEquals("en", Lang.tag)
    }
}
