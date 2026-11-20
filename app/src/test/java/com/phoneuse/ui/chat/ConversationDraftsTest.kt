package com.phoneuse.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.phoneuse.assist.VoiceBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDraftsTest {
    @After fun clear() {
        VoiceBridge.activeSession.value?.let { VoiceBridge.deliver(it, null) }
        ConversationDrafts.clear()
    }

    @Test fun returnedChatKeepsTypedTextAndTheLatestDictationFromItsDock() {
        val dock = PanelDraft(11)
        dock.value = TextFieldValue("Change the date to")
        val id = VoiceBridge.request { words -> if (words != null) dock.appendSpeech(words) }
        VoiceBridge.update(id, "next")
        VoiceBridge.update(id, "next Friday")
        VoiceBridge.interrupt(id)
        dock.close()
        VoiceBridge.deliver(id, "duplicate late result")

        val chat = ConversationDrafts.state(11, TextFieldValue("older activity snapshot"))
        assertEquals("Change the date to next Friday", chat.value.text)
        assertEquals(TextRange(chat.value.text.length), chat.value.selection)
        assertEquals("", ConversationDrafts.state(12).value.text)
    }

    @Test fun successivePhrasesAppendOnceAndCancellingTheNextOneKeepsTheDraft() {
        val dock = PanelDraft(12)
        fun listen() = VoiceBridge.request { words -> if (words != null) dock.appendSpeech(words) }
        VoiceBridge.deliver(listen(), "Show directions")
        dock.value = TextFieldValue("Show walking directions\n")
        VoiceBridge.deliver(listen(), "to the station")
        val id = listen()
        VoiceBridge.update(id, "discard this")
        VoiceBridge.deliver(id, null)
        dock.close()
        assertEquals("Show walking directions\nto the station", ConversationDrafts.state(12).value.text)
    }

    @Test fun aPanelStartsEmptyAndSendsOnlyItsOwnWordsEvenWhenAChatHasADraft() {
        val chat = ConversationDrafts.state(11, TextFieldValue("Unsent chat instruction"))
        val dock = PanelDraft(11)
        assertEquals("", dock.value.text)
        dock.appendSpeech("Pan left")
        assertTrue(dock.submit { text -> text == "Pan left" })
        assertEquals("", dock.value.text)
        dock.close()
        assertEquals("Unsent chat instruction", chat.value.text)
    }

    @Test fun refusedPanelSendTransfersOnceToItsChatAlongsideTheCurrentDraft() {
        val chat = ConversationDrafts.state(11, TextFieldValue("Use"))
        val other = ConversationDrafts.state(12, TextFieldValue("Other chat"))
        val dock = PanelDraft(11)
        dock.appendSpeech("the train")
        assertFalse(dock.submit { false })
        assertEquals("the train", dock.value.text)
        assertEquals("Use", chat.value.text)
        chat.value = TextFieldValue("Please take")
        dock.close()
        dock.close()
        assertEquals("Please take the train", chat.value.text)
        assertEquals("Other chat", other.value.text)
    }

    @Test fun resultAlreadyPostedWhenThePanelClosedGoesToTheOriginalChat() {
        val dock = PanelDraft(11)
        dock.value = TextFieldValue("Zoom")
        dock.close()
        val next = PanelDraft(12)
        dock.appendSpeech("out")
        assertEquals("Zoom out", ConversationDrafts.state(11).value.text)
        assertEquals("", next.value.text)
        next.close()
        assertEquals("", ConversationDrafts.state(12).value.text)
    }

    @Test fun newChatAssignmentKeepsLateSpeechInTheConversationWhenItsDockAlreadyExists() {
        val homeComposer = ConversationDrafts.state(null)
        homeComposer.value = TextFieldValue("Use")
        val actionDock = ConversationDrafts.state(11)
        actionDock.value = TextFieldValue("walking directions")
        ConversationDrafts.move(null, 11)
        homeComposer.value = homeComposer.value.appendSpeech("to the station")
        assertEquals("Use walking directions to the station", actionDock.value.text)
        assertEquals("", ConversationDrafts.state(null).value.text)
    }
}
