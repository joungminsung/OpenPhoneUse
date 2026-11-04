package com.phoneuse.app

import com.phoneuse.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EnglishTextTest {
    private var saved = true

    @Before
    fun english() {
        saved = Lang.korean
        Lang.korean = false
    }

    @After
    fun restore() {
        Lang.korean = saved
    }

    @Test
    fun fallbackTextReadsInEnglish() {
        assertEquals("Task finished · Booked it", ReturnPolicy.fallbackText("Booked it"))
        assertEquals("Task finished · Done", ReturnPolicy.fallbackText(null))
    }
}
