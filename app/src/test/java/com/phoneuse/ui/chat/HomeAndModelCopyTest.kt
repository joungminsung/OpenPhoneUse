package com.phoneuse.ui.chat

import com.phoneuse.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAndModelCopyTest {
    private val saved = Lang.korean

    @After
    fun restore() {
        Lang.korean = saved
    }

    @Test
    fun homeTileLabelsFitOneLineOnAPhone() {
        // A phone tile at 411dp (1080x2400, 420dpi) has 93dp for 14sp text, and "When to leave?" (14 characters)
        // measured 94dp. A longer label still shows in full, but it wraps and makes its row of tiles taller.
        for (korean in listOf(false, true)) {
            Lang.korean = korean
            for (action in QUICK_ACTIONS) assertTrue(action.label, action.label.length <= 13)
        }
    }

    @Test
    fun homeTileLabelsFollowTheLanguage() {
        Lang.korean = false
        assertEquals("Sum up alerts", QUICK_ACTIONS[3].label)
        Lang.korean = true
        assertEquals("알림 요약", QUICK_ACTIONS[3].label)
    }

    @Test
    fun modelSettingsUseTheREADMETerms() {
        Lang.korean = true
        assertEquals("빠른 모드", FAST_MODE_LABEL)
        assertEquals("추론 강도", REASONING_EFFORT_LABEL)
        Lang.korean = false
        assertEquals("Fast mode", FAST_MODE_LABEL)
        assertEquals("Reasoning effort", REASONING_EFFORT_LABEL)
    }
}
