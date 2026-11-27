package com.phoneuse.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationWindowTest {
    @Test fun openingTheMicGetsTheFullWaitButEarlyEmptyRestartsDoNotExtendIt() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.ready(500)
        window.complete(null, 1_250)
        window.ready(1_500)
        window.endOfSpeech(2_000)
        window.complete(" ", 2_100)

        assertEquals(4_400L, window.remaining(2_100))
        assertEquals(0L, window.remaining(6_500))
        assertEquals("", window.text)
    }

    @Test fun partialsAreReplacedWithinAnUtteranceAndPhrasesAreJoinedOnce() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("turn of", 700)
        window.partial("turn of", 1_200)
        window.endOfSpeech(1_500)
        window.complete("turn off", 1_600)
        assertEquals("turn off", window.text)
        assertEquals("turn off the", window.partial("the", 3_000))
        assertEquals("turn off the lights", window.partial("the lights", 3_300))
        window.endOfSpeech(4_000)
        window.complete(null, 4_200)
        window.complete(null, 6_500)
        assertEquals("turn off the lights", window.text)
        assertEquals(2_800L, window.remaining(6_500))
    }

    @Test fun aServiceThatAlreadyWaitedForSilenceDoesNotAddTheSamePauseAgain() {
        val window = DictationWindow(3_000, startedAt = 0)
        window.partial("lights", 1_000)
        window.endOfSpeech(4_000)
        window.complete("lights", 4_200)
        assertEquals(0L, window.remaining(4_200))
    }

    @Test fun servicesWithoutPartialsUseTheEndOfSpeechOrFinalTime() {
        val window = DictationWindow(10_000, startedAt = 0)
        window.endOfSpeech(12_000)
        window.complete("first", 14_000)
        assertEquals(8_000L, window.remaining(14_000))
        window.complete("another phrase", 18_000)
        assertEquals(10_000L, window.remaining(18_000))
        assertEquals("first another phrase", window.text)
    }

    @Test fun aShorterPauseAppliesOnlyAfterWords() {
        val window = DictationWindow(6_000, pauseMs = 2_000, startedAt = 0)
        window.ready(500)
        assertEquals(6_000L, window.remaining(500))
        window.partial("lights", 1_000)
        window.endOfSpeech(1_800)
        window.complete("lights", 1_900)
        assertEquals(1_100L, window.remaining(1_900))
    }

    @Test fun wordsOfAFailedUtteranceAreDropped() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.complete("note this", 1_000)
        window.partial("and send", 2_000)
        window.dropUnconfirmed()
        assertEquals("note this", window.text)
    }

    @Test fun phrasesAreJoinedWithASpaceExceptInChineseAndJapanese() {
        assertEquals("turn off the lights", joinPhrases("turn off", "the lights"))
        assertEquals("불을 꺼 줘", joinPhrases("불을", "꺼 줘"))
        assertEquals("電気を消して", joinPhrases("電気を", "消して"))
        assertEquals("把灯关掉", joinPhrases("把灯", "关掉"))
        assertEquals("カメラを開いて", joinPhrases("カメラを", "開いて"))
        assertEquals("わかりました。OK", joinPhrases("わかりました。", "OK"))
        assertEquals("Wi-Fiをオフ", joinPhrases("Wi-Fi", "をオフ"))
        assertEquals("only", joinPhrases("", "only"))
        assertEquals("only", joinPhrases("only", ""))
    }

    @Test fun anEarlyPartialMustNotUseUpThePauseWhileTheUserIsStillSpeaking() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("please", 1_000)
        window.endOfSpeech(12_000)
        window.complete("please switch off the kitchen lights", 12_200)
        assertEquals(5_800L, window.remaining(12_200))
    }

    @Test fun aFinishedPhraseIsKeptAndTheNextPartialStartsAfresh() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("turn of", 700)
        window.endOfSpeech(1_500)
        window.phrase("turn off", 1_550)
        assertEquals("turn off", window.text)
        // The phrase is not the partial text "turn of", so the end of speech says when it was heard.
        assertEquals(5_950L, window.remaining(1_550))

        assertEquals("turn off the", window.partial(" the", 3_000))
        window.partial(" the lights", 3_300)
        window.endOfSpeech(4_000)
        window.phrase(" the lights", 4_050)
        assertEquals("turn off the lights", window.text)
        // This phrase matches its partial text, last heard at 3_300.
        assertEquals(5_250L, window.remaining(4_050))
    }

    @Test fun anEmptyEndingKeepsThePhrasesAndBuysNoWait() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("turn off", 700)
        window.endOfSpeech(1_400)
        window.phrase("turn off", 1_500)
        val left = window.remaining(3_000)

        window.complete(null, 3_000)
        assertEquals("turn off", window.text)
        assertEquals(left, window.remaining(3_000))
        assertTrue(window.hasConfirmedWords)
    }

    @Test fun speechThatResumesWithoutFinishedPhrasesKeepsTheTextBeforeIt() {
        // A service that listens on and returns no phrases starts its partial text over, with a space in front.
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("testing 1 2", 3_635)
        window.endOfSpeech(3_820)
        window.partial("testing 1 2 3", 3_827)
        window.resumed()
        assertEquals("testing 1 2 3", window.text)

        window.endOfSpeech(6_129)
        assertEquals("testing 1 2 3 4", window.partial(" 4", 6_138))
        window.complete(null, 6_165)
        assertEquals("testing 1 2 3 4", window.text)
        // Six seconds from the end of speech of that word.
        assertEquals(5_964L, window.remaining(6_165))
    }

    @Test fun aServiceThatRepeatsTheEarlierWordsWhenSpeechResumesIsNotKeptTwice() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("remind me to call", 1_000)
        window.endOfSpeech(3_000)
        window.resumed()
        assertEquals("remind me to call the dentist", window.partial("remind me to call the dentist", 5_500))
    }

    @Test fun aPhraseKeptByGuessworkIsNotDoubledWhenTheServiceReturnsItLate() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("turn off", 700)
        window.endOfSpeech(1_400)
        window.resumed()
        assertEquals("turn off the", window.partial(" the", 2_000))

        // The finished phrase arrives after the next phrase's first words. It replaces the copy that was kept.
        window.phrase("Turn off", 2_100)
        assertEquals("Turn off the", window.text)
        window.endOfSpeech(2_900)
        window.phrase(" the lights", 2_950)
        assertEquals("Turn off the lights", window.text)
    }

    @Test fun afterAFinishedPhraseSpeechThatResumesKeepsNothingByGuesswork() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("turn off", 700)
        window.endOfSpeech(1_400)
        window.phrase("turn off", 1_450)
        window.partial(" the", 3_000)
        window.endOfSpeech(3_500)
        window.resumed()

        // This service returns its phrases itself, so partial text in flight is only ever replaced.
        assertEquals("turn off kitchen", window.partial(" kitchen", 4_000))
        window.phrase(" the lights", 4_100)
        assertEquals("turn off the lights", window.text)
        assertEquals("turn off the lights kitchen", window.partial(" kitchen", 4_200))
    }

    @Test fun aFinalStandsForThePhrasesItRepeatsAndIsAddedWhenItDoesNot() {
        val whole = DictationWindow(6_000, startedAt = 0)
        whole.phrase("turn off", 1_000)
        whole.phrase("the lights", 2_000)
        whole.complete("Turn off the lights", 2_100)
        assertEquals("Turn off the lights", whole.text)

        val lastWords = DictationWindow(6_000, startedAt = 0)
        lastWords.phrase("turn off", 1_000)
        lastWords.complete("the lights", 2_100)
        assertEquals("turn off the lights", lastWords.text)
    }

    @Test fun wordsOfAFailedUtteranceAreDroppedButItsFinishedPhrasesAreKept() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.phrase("note this", 1_000)
        window.partial(" and send", 2_000)
        window.dropUnconfirmed()

        assertEquals("note this", window.text)
        assertTrue(window.hasConfirmedWords)
        assertFalse(window.hasUnconfirmedWords)
    }
}
