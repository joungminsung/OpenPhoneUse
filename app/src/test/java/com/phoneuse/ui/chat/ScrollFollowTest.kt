package com.phoneuse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowTest {
    @Test fun followsOncePerNewKey() {
        val follow = ScrollFollow()
        assertTrue(follow.shouldFollow(Triple(1L, 10L, false)))
        assertTrue(follow.shouldFollow(Triple(1L, 11L, false)))
        assertTrue(follow.shouldFollow(Triple(1L, 11L, true)))
    }

    @Test fun comingBackFromFlexModeKeepsTheScrollPosition() {
        val follow = ScrollFollow()
        assertTrue(follow.shouldFollow(Triple(1L, 10L, false)))
        // The chat pane left composition (flex mode) and came back with nothing new: no jump to the newest message.
        assertFalse(follow.shouldFollow(Triple(1L, 10L, false)))
    }

    @Test fun aReplyThatArrivedDuringFlexModeIsFollowed() {
        val follow = ScrollFollow()
        follow.shouldFollow(Triple(1L, 10L, false))
        assertTrue(follow.shouldFollow(Triple(1L, 12L, false)))
    }

    @Test fun theFirstKeyIsFollowedEvenWhenNull() {
        assertTrue(ScrollFollow().shouldFollow(null))
    }
}
