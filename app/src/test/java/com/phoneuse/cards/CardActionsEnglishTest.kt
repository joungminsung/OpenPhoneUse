package com.phoneuse.cards

import com.phoneuse.i18n.Lang
import com.phoneuse.memory.MemoryKind
import com.phoneuse.phone.ConfirmGate
import com.phoneuse.phone.ConfirmKind
import com.phoneuse.phone.RunTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CardActionsEnglishTest {
    private var saved = true

    @Before fun english() {
        saved = Lang.korean
        Lang.korean = false
    }

    @After fun restore() {
        Lang.korean = saved
    }

    @Test fun promptsAreEnglish() {
        assertEquals("Save this event as is", CardActions.saveEvent().text)
        assertEquals("To-do", MemoryKind.TODO.label)
    }

    @Test fun englishButtonsStillConfirm() {
        val save = RunTrigger.CardButton(CardActions.saveEvent().text)
        assertNull(ConfirmGate.refusal(ConfirmKind.EVENT, save, tainted = false))
        val album = RunTrigger.CardButton("Make an album from photos 1, 3")
        assertNull(ConfirmGate.refusal(ConfirmKind.ALBUM, album, tainted = false))
    }
}
