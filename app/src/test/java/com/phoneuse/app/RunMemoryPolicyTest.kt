package com.phoneuse.app

import com.phoneuse.chat.ConversationSearch
import com.phoneuse.memory.InMemoryMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunMemoryPolicyTest {
    private val store = InMemoryMemoryStore()
    private val search = ConversationSearch { _, _, _ -> emptyList() }

    @Test fun memoryOffTurnsEverythingOff() {
        for (source in Source.entries) {
            val p = RunMemoryPolicy.of(memoryOn = false, autoRemember = true, source = source)
            assertEquals(RunMemoryPolicy(inject = false, remember = false, forget = false, searchConversations = false, appTips = false), p)
            val a = p.access(store, search, 1, "t")
            assertNull(a.store)
            assertNull(a.search)
            assertFalse(a.allowRemember)
            assertFalse(a.allowForget)
        }
    }

    @Test fun routineRunsNeitherRememberNorForget() {
        val p = RunMemoryPolicy.of(memoryOn = true, autoRemember = true, source = Source.ROUTINE)
        assertTrue(p.inject && p.searchConversations && p.appTips)
        assertFalse(p.remember)
        assertFalse(p.forget)
    }

    @Test fun cardRunsNeitherRememberNorForget() {
        val p = RunMemoryPolicy.of(memoryOn = true, autoRemember = true, source = Source.CARD)
        assertTrue(p.inject && p.searchConversations && p.appTips)
        assertFalse(p.remember)
        assertFalse(p.forget)
        val a = p.access(store, search, 1, "t")
        assertNotNull(a.store)
        assertFalse(a.allowRemember)
        assertFalse(a.allowForget)
        // The tools stay offered (refused when called), so the tool list matches a typed run's.
        assertTrue(p.offerRemember && p.offerForget)
        assertTrue(a.offerRemember && a.offerForget)
        val off = RunMemoryPolicy.of(memoryOn = false, autoRemember = true, source = Source.CARD)
        assertFalse(off.offerRemember || off.offerForget)
    }

    @Test fun userRunsFollowAutoRemember() {
        val user = Source.entries.first { it != Source.CARD }
        val on = RunMemoryPolicy.of(memoryOn = true, autoRemember = true, source = user)
        assertTrue(on.remember && on.forget && on.searchConversations && on.appTips && on.inject)
        val a = on.access(store, search, 7, "제목")
        assertNotNull(a.search)
        assertEquals(7L, a.conversationId)
        assertEquals("제목", a.origin)
        val noAuto = RunMemoryPolicy.of(memoryOn = true, autoRemember = false, source = user)
        assertFalse(noAuto.remember)
        assertTrue(noAuto.forget)
    }
}
