package com.phoneuse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionLayoutTest {
    @Test fun shortFewOptionsShareARow() {
        assertTrue(optionsFitOneRow(listOf("네", "아니요")))
        assertTrue(optionsFitOneRow(listOf("김민수", "이민수", "박민수")))
    }

    @Test fun longOrManyOptionsStackFullWidth() {
        assertFalse(optionsFitOneRow(listOf("회사 동료 김민수", "대학 친구 이민수")))
        assertFalse(optionsFitOneRow(listOf("1", "2", "3", "4")))
        assertFalse(optionsFitOneRow(emptyList()))
    }
}
