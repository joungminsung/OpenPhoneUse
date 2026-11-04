package com.phoneuse.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FullWipeTest {
    @Test fun everyPartRunsAndFailuresAreReported() = runTest {
        // One broken store never keeps the rest, and the result says what could not be wiped.
        val ran = mutableListOf<String>()
        val failed = FullWipe.runParts(
            listOf(
                "대화" to { ran += "대화" },
                "기억" to { throw IllegalStateException("disk") },
                "루틴" to { ran += "루틴" },
            ),
        )
        assertEquals(listOf("대화", "루틴"), ran)
        assertEquals(listOf("기억"), failed)
        assertEquals("모든 대화와 기억을 지웠어요", FullWipe.resultText(emptyList()))
        assertEquals("일부를 지우지 못했어요: 기억, 루틴", FullWipe.resultText(listOf("기억", "루틴")))
    }
}
