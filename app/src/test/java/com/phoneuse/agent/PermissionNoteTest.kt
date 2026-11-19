package com.phoneuse.agent

import com.phoneuse.i18n.Lang
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PermissionNoteTest {
    private val HANGUL = Regex("[가-힣]")
    private var saved = true

    @Before fun remember() { saved = Lang.korean }
    @After fun restore() { Lang.korean = saved }

    @Test fun koreanNamesTheKoreanButton() {
        Lang.korean = true
        val t = PermissionNote.text("권한 필요: 위치")
        assertTrue(t, t.contains("[권한 허용]"))
        assertFalse(t, t.contains("—"))
    }

    @Test fun englishNamesTheEnglishButtonAndHasNoHangul() {
        Lang.korean = false
        val t = PermissionNote.text("Permission needed")
        assertTrue(t, t.contains("[Allow permission]"))
        assertFalse(t, HANGUL.containsMatchIn(t.substringAfter("Permission needed")))
        assertFalse(t, t.contains("—"))
    }
}
