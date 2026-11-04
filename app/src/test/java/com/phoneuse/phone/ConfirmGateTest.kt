package com.phoneuse.phone

import com.phoneuse.cards.CardActions
import com.phoneuse.cards.PhotosCard
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmGateTest {
    private val photos = PhotosCard("t", listOf("content://media/external/images/media/1", "content://media/external/images/media/2"))

    @Test
    fun onlyTheUserStartsAConfirmedWrite() {
        // Typed by the user, or that card's own button.
        assertNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.Typed, tainted = false))
        assertNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.CardButton(CardActions.saveEvent().text), tainted = false))
        assertNull(ConfirmGate.refusal(ConfirmKind.ALBUM, RunTrigger.CardButton(CardActions.makeAlbum(photos, emptySet()).text), false))
        assertNull(ConfirmGate.refusal(ConfirmKind.ALBUM, RunTrigger.CardButton(CardActions.makeAlbum(photos, setOf(photos.uris[1])).text), false))
        // Another card's button, the overlay or a routine: refused.
        assertNotNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.CardButton(CardActions.makeAlbum(photos, emptySet()).text), false))
        assertNotNull(ConfirmGate.refusal(ConfirmKind.ALBUM, RunTrigger.CardButton(CardActions.saveEvent().text), false))
        assertNotNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.CardButton(CardActions.findFreeTime().text), false))
        assertNotNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.Other, false))
    }

    @Test
    fun aTaintedRunNeverConfirms() {
        assertNotNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.Typed, tainted = true))
        assertNotNull(ConfirmGate.refusal(ConfirmKind.ALBUM, RunTrigger.CardButton(CardActions.makeAlbum(photos, emptySet()).text), tainted = true))
    }

    @Test fun historyTaintedRefusesTypedButNotTheOwnButton() {
        assertNotNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.Typed, tainted = false, historyTainted = true))
        assertNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.CardButton(CardActions.saveEvent().text), tainted = false, historyTainted = true))
        // Another card's button is still refused, and so is a run tainted by itself.
        assertNotNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.CardButton(CardActions.findFreeTime().text), false, historyTainted = true))
        assertNotNull(ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.CardButton(CardActions.saveEvent().text), tainted = true, historyTainted = true))
    }
}
