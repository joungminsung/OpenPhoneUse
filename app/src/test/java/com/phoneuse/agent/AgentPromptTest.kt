package com.phoneuse.agent

import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import com.phoneuse.memory.MemoryKind
import com.phoneuse.memory.MemoryRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTest {
    private val mom = Memory(4, "엄마가 10월부터 등산을 다니심", MemoryKind.PERSON, Confidence.SURE, createdAt = 1, updatedAt = 1)
    private val budget = Memory(9, "선물 예산은 보통 10만 원 안쪽", MemoryKind.HABIT, Confidence.SURE, createdAt = 2, updatedAt = 2)
    private val guess = Memory(5, "엄마 선물은 꽃이 좋다고 함", MemoryKind.PERSON, Confidence.GUESS, createdAt = 3, updatedAt = 3)

    @Test fun includesPersonalizationAndMemoryBlock() {
        val p = Personalization(callName = "지민님", polite = false, length = AnswerLength.SHORT, about = "개발자, 판교 근무, 등산 좋아함")
        val memories = MemoryRanker.top("엄마 선물", listOf(mom, budget, guess))
        val prompt = AgentPrompt.build(p, memoryOn = true, autoRemember = true)
        val note = AgentPrompt.memoryNote(memories, memoryOn = true)!!

        // Personalization comes first, before the rules.
        val rules = prompt.indexOf("You are OpenPhoneUse")
        assertTrue(rules > 0)
        for (s in listOf("지민님", "반말", "Answer length: short", "개발자, 판교 근무, 등산 좋아함")) {
            val at = prompt.indexOf(s)
            assertTrue(s, at in 0 until rules)
        }

        // The memory block is not in the instructions (they must stay the same for the prompt cache); it is
        // its own note, sent just before the user's message, with ids the model can forget by.
        assertFalse(AgentPrompt.MEMORY_HEADER in prompt)
        assertFalse(mom.text in prompt)
        assertTrue(note, note.startsWith(AgentPrompt.MEMORY_HEADER))
        assertTrue(note, "[4]" in note && mom.text in note)
        assertTrue(note, "[9]" in note && budget.text in note)
        // A guess is not used until the user confirms it.
        assertFalse(note, guess.text in note)
        // Equal match, newer first: the budget line, then mom's, which ends the note.
        assertTrue(note.trimEnd().endsWith(mom.text))
        // Different memories, same instructions.
        assertEquals(prompt, AgentPrompt.build(p, memoryOn = true, autoRemember = true))

        // Auto-remember, only from what the user says; and the web-search hint.
        assertTrue(AgentPrompt.AUTO_REMEMBER in prompt)
        assertTrue(AgentPrompt.NO_THIRD_PARTY_MEMORY in prompt)
        assertTrue(AgentPrompt.WEB_SEARCH_HINT in prompt)
        assertFalse(AgentPrompt.WEB_SEARCH_HINT in AgentPrompt.build(p, memoryOn = true, autoRemember = true, webSearch = false))

        // Auto-remember off: the sentence goes, the rest stays.
        val off = AgentPrompt.build(p, memoryOn = true, autoRemember = false)
        assertFalse(AgentPrompt.AUTO_REMEMBER in off)
        assertTrue(AgentPrompt.NO_THIRD_PARTY_MEMORY in off)

        // Memory off: no note and no memory rules.
        val none = AgentPrompt.build(p, memoryOn = false, autoRemember = true)
        assertNull(AgentPrompt.memoryNote(memories, memoryOn = false))
        assertFalse(AgentPrompt.AUTO_REMEMBER in none)
        assertFalse(AgentPrompt.NO_THIRD_PARTY_MEMORY in none)

        // Nothing matched: no note.
        assertNull(AgentPrompt.memoryNote(emptyList(), memoryOn = true))

        // Defaults: polite, normal length, no name or about line.
        val plain = AgentPrompt.build(Personalization(), memoryOn = true, autoRemember = true)
        assertTrue("존댓말" in plain)
        assertTrue("detailed enough, key point first" in plain)
        assertTrue("App language: Korean" in plain)
        assertTrue(plain.contains(AgentPrompt.INSTRUCTIONS))
    }

    @Test fun harnessFavorsFinishedAndCheckedWorkOverSpeed() {
        val i = AgentPrompt.INSTRUCTIONS
        // The task is done for real: verified end state, no handing the last tap to the user, no early give-up.
        assertTrue("Verify before you finish" in i)
        assertTrue("Never end by telling the user to tap something you could have tapped" in i)
        assertTrue("Try at least three different approaches" in i)
        assertTrue("never cut a task short to save time or tokens" in i)
        // Research is cross-checked, not answered from one thin search.
        assertTrue("cross-check key numbers across at least two independent sources" in i)
        assertFalse("about 100 words" in i)
        assertFalse("Be fast getting there" in i)
    }

    @Test fun tidyingAFolderIsDoneNotHandedBack() {
        val prompt = AgentPrompt.build(Personalization(), memoryOn = false, autoRemember = false)
        assertTrue(prompt, "Tidying a folder" in prompt)
        assertTrue(prompt, "never hand it back because of the file count" in prompt)
        assertTrue(prompt, "move_files" in prompt)
    }

    @Test fun assistantPersonaAndAnswerQuality() {
        val i = AgentPrompt.INSTRUCTIONS
        assertTrue(i.startsWith("You are OpenPhoneUse, the user's everyday AI assistant on their phone."))
        assertTrue("Answer quality:" in i)
        assertTrue("Reply in the language of the user's message" in i)
        // finish's summary is the user-facing answer, not a one-liner.
        assertTrue("Never just \"Done\"" in i)
        assertFalse("call finish with a short summary" in i)
        // Cards: summary plus a recommendation with its reason.
        assertTrue("one recommendation with its reason in two to four sentences" in i)
        // Speed hints.
        assertTrue("Do not call get_screen right after an action" in i)
        // Safety rules are still there.
        assertTrue("unless the screen is still loading; then call wait" in i)
        for (rule in listOf(
            "Never type passwords", "user_authorized=true only", "Never guess who receives", "stay_in_app=true only",
            "Call ask_user only when truly blocked", "Risky actions (sending, paying, deleting, security changes) are confirmed by the app automatically",
            "Never ask to confirm something the user already asked for", "Messages that start with \"사용자 추가 지시\"",
            "Never for a task you carried out inside an app",
        )) {
            assertTrue(rule, rule in i)
        }
    }

    @Test fun answerLengthMapsToVerbosityAndPromptLine() {
        assertEquals("low", AnswerLength.SHORT.verbosity)
        assertEquals("medium", AnswerLength.NORMAL.verbosity)
        assertEquals("high", AnswerLength.LONG.verbosity)
        val short = AgentPrompt.build(Personalization(length = AnswerLength.SHORT), memoryOn = false, autoRemember = false)
        assertTrue("only the key point in one or two sentences" in short)
        val long = AgentPrompt.build(Personalization(length = AnswerLength.LONG), memoryOn = false, autoRemember = false)
        assertTrue("thorough" in long)
    }

    @Test fun aboutIsCappedAt500Chars() {
        val p = Personalization(about = "가".repeat(900))
        val prompt = AgentPrompt.build(p, memoryOn = true, autoRemember = true)
        assertEquals(Personalization.ABOUT_MAX, "가".repeat(900).take(Personalization.ABOUT_MAX).length)
        assertFalse("가".repeat(501) in prompt)
        assertTrue("가".repeat(500) in prompt)
    }
}
