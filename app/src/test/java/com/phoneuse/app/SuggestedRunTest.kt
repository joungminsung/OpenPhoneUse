package com.phoneuse.app

import com.phoneuse.chat.Attachment
import com.phoneuse.phone.RunTrigger
import com.phoneuse.routine.RoutineRun
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestedRunTest {
    @Test fun suggestedTextNeverDrivesTheTrigger() {
        // A side-button suggestion was written from screen text, so it never counts as typed by the user
        // (which would enable phone-data tools) or as a card button, whatever source carries it.
        for (source in Source.entries) assertEquals(source.name, RunTrigger.Other, source.trigger("문자 보내 줘", suggested = true))
        assertEquals(RunTrigger.Typed, Source.APP.trigger("문자 보내 줘"))
        assertEquals(RunTrigger.CardButton("x"), Source.CARD.trigger("x"))
    }

    @Test fun userMessageCarryingOutsideTextFlagsTheConversation() {
        // The flag goes with the user message itself, so an early exit (signed out, no service) never
        // leaves third-party text stored in a clean conversation.
        val quoted = RoutineRun(1, "택배 알림", trustSend = false, thirdParty = true)
        val own = RoutineRun(2, "아침", trustSend = false, thirdParty = false)
        assertTrue(Source.ROUTINE.messageIsThirdParty(quoted, suggested = false, attachments = emptyList()))
        assertFalse(Source.ROUTINE.messageIsThirdParty(own, suggested = false, attachments = emptyList()))
        assertTrue(Source.ASSIST.messageIsThirdParty(null, suggested = true, attachments = emptyList()))
        val photo = Attachment.image("a.jpg")
        assertTrue(Source.APP.messageIsThirdParty(null, suggested = false, attachments = listOf(photo)))
        // A card button never carries attachments (Source.allowed), so they do not flag it.
        assertFalse(Source.CARD.messageIsThirdParty(null, suggested = false, attachments = listOf(photo)))
        assertFalse(Source.APP.messageIsThirdParty(null, suggested = false, attachments = emptyList()))
    }
}
