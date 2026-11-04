package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTest {
    @Test
    fun linksAreReadByTheirText() {
        assertEquals("맑아요 (example.com)", ReadAloud.plain("맑아요 ([example.com](https://example.com/a?utm_source=openai))"))
    }

    @Test
    fun parenthesesInALinkLeaveNothingBehind() {
        assertEquals("서울 끝", ReadAloud.plain("[서울](https://en.wikipedia.org/wiki/Seoul_(city)) 끝"))
    }
}
