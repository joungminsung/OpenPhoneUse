package com.phoneuse.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mic stays open through pauses for the chosen wait, and Done and Cancel never wait it out. */
class DictationFlowTest {
    /** A recognition service, a clock with one timer, and a record of what the mic UI was told. */
    private class FakeHost : DictationFlow.Host {
        var time = 0L
        var canStart = true
        var starts = 0
        val service = mutableListOf<String>()
        val ui = mutableListOf<String>()
        private var timer: Pair<Long, () -> Unit>? = null

        val timerSet: Boolean get() = timer != null

        override fun now(): Long = time
        override fun start(turn: Int): Boolean {
            starts = turn
            service += "start"
            return canStart
        }
        override fun stop() { service += "stop" }
        override fun cancel() { service += "cancel" }
        override fun after(delayMs: Long, action: () -> Unit) { timer = (time + delayMs) to action }
        override fun clearTimer() { timer = null }
        override fun ready() { ui += "ready" }
        override fun beginningOfSpeech() { ui += "speech" }
        override fun partial(text: String) { ui += "partial:$text" }
        override fun endOfSpeech() { ui += "end" }
        override fun results(text: String) { ui += "results:$text" }
        override fun error(code: Int) { ui += "error:$code" }

        /** Moves the clock to [to], running the timer each time it falls due on the way. */
        fun advanceTo(to: Long) {
            while (true) {
                val (due, action) = timer ?: break
                if (due > to) break
                timer = null
                time = due
                action()
            }
            time = to
        }
    }

    private val host = FakeHost()
    private val flow = DictationFlow(6_000, host)

    private val noMatch = 7
    private val network = 2
    private val audio = 3
    private val busy = 8

    /** Says [words] in utterance [turn]: heard at [at], and returned by the service one second later. */
    private fun say(turn: Int, words: String, at: Long) {
        host.advanceTo(at)
        flow.beginningOfSpeech(turn)
        flow.partial(turn, words)
        host.advanceTo(at + 900)
        flow.endOfSpeech(turn)
        host.advanceTo(at + 1_000)
        flow.results(turn, words)
        host.advanceTo(at + 1_000)
    }

    @Test fun aPauseKeepsTheMicOpenAndJoinsThePhrasesWithoutRepeatingAny() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "turn off", at = 800)
        assertEquals(2, host.starts)

        host.advanceTo(2_000)
        flow.ready(2)
        say(2, "the lights", at = 4_000)
        assertEquals(3, host.starts)
        host.advanceTo(5_200)
        flow.ready(3)

        // Six seconds after "the lights" was heard.
        host.advanceTo(9_999)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(10_000)

        assertEquals(
            listOf(
                "partial:turn off", "partial:turn off",
                "partial:turn off the lights", "partial:turn off the lights",
                "end", "results:turn off the lights",
            ),
            host.ui.filter { it.startsWith("partial") || it == "end" || it.startsWith("results") },
        )
        assertEquals(3, host.starts)
    }

    // The request asked the service to keep one utterance open through pauses and to return each phrase.
    private val listensOn = DictationFlow(6_000, host, segmented = true)

    /** Says [words] in utterance [turn] of [listensOn]: heard at [at], and returned as a finished phrase. */
    private fun phrase(turn: Int, words: String, at: Long) {
        host.advanceTo(at)
        listensOn.beginningOfSpeech(turn)
        host.advanceTo(at + 100)
        listensOn.partial(turn, words)
        host.advanceTo(at + 900)
        listensOn.endOfSpeech(turn)
        host.advanceTo(at + 950)
        listensOn.segment(turn, words)
        host.advanceTo(at + 950)
    }

    @Test fun aServiceThatListensOnKeepsOneUtteranceOpenAndItsPhrasesAreJoined() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "turn off", at = 800)
        assertEquals("partial:turn off", host.ui.last())
        phrase(1, " the lights", at = 4_000)
        assertEquals("partial:turn off the lights", host.ui.last())

        // Six seconds after "the lights" was heard, at 4_100. Nothing was started again in between.
        host.advanceTo(10_099)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(10_100)
        assertEquals(listOf("end", "results:turn off the lights"), host.ui.takeLast(2))
        assertEquals("cancel", host.service.last())
        assertEquals(1, host.starts)
    }

    @Test fun doneAfterAFinishedPhraseEndsAtOnce() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "hello", at = 500)

        host.advanceTo(2_000)
        listensOn.stop()
        assertEquals("results:hello", host.ui.last())
        assertEquals("cancel", host.service.last())
        assertFalse(host.timerSet)

        listensOn.segment(1, "more")
        listensOn.endOfSegments(1)
        host.advanceTo(60_000)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun doneWhileSpeakingWaitsOnlyForThatPhrase() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "note this", at = 500)
        host.advanceTo(3_000)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, " and send")

        host.advanceTo(3_500)
        listensOn.stop()
        assertEquals("stop", host.service.last())
        assertFalse(host.ui.any { it.startsWith("results") })

        host.advanceTo(3_700)
        listensOn.segment(1, " and send it")
        assertEquals("results:note this and send it", host.ui.last())
        assertEquals("cancel", host.service.last())
    }

    @Test fun doneWhileSpeakingEndsWhenTheServiceEndsTheUtteranceWithoutThoseWords() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "note this", at = 500)
        host.advanceTo(3_000)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, " and send")
        host.advanceTo(3_500)
        listensOn.stop()

        // The service closes the utterance without returning the last phrase. A draft keeps the words it showed.
        host.advanceTo(3_600)
        listensOn.endOfSegments(1)
        assertEquals("results:note this and send", host.ui.last())
        assertEquals("stop", host.service.last())
    }

    @Test fun doneJustAfterTheEndOfSpeechTellsAServiceThatListensOnToFinish() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        host.advanceTo(500)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, "hello")
        host.advanceTo(1_400)
        listensOn.endOfSpeech(1)

        host.advanceTo(1_410)
        listensOn.stop()
        assertEquals("stop", host.service.last())
        host.advanceTo(1_450)
        listensOn.segment(1, "hello there")
        assertEquals("results:hello there", host.ui.last())
    }

    @Test fun doneJustAfterTheEndOfSpeechOfAPlainUtteranceWaitsForItsFinal() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        host.advanceTo(500)
        flow.beginningOfSpeech(1)
        flow.partial(1, "hello")
        host.advanceTo(1_400)
        flow.endOfSpeech(1)

        // The service has stopped listening by itself, so it is not asked to.
        host.advanceTo(1_410)
        flow.stop()
        assertEquals("start", host.service.last())
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(1_450)
        flow.results(1, "hello there")
        assertEquals("results:hello there", host.ui.last())
    }

    @Test fun cancelWhileAServiceListensOnDropsEverythingAtOnce() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "hello", at = 500)
        val told = host.ui.toList()

        host.advanceTo(2_000)
        listensOn.close()
        assertEquals("cancel", host.service.last())
        assertFalse(host.timerSet)

        listensOn.partial(1, " more")
        listensOn.segment(1, " more")
        listensOn.endOfSegments(1)
        listensOn.results(1, "more")
        host.advanceTo(60_000)
        assertEquals(told, host.ui)
        assertEquals(1, host.starts)
    }

    @Test fun anUtteranceTheServiceClosesInsideThePauseIsOpenedAgainWithItsPhrasesKept() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "note this", at = 500)

        host.advanceTo(3_000)
        listensOn.endOfSegments(1)
        host.advanceTo(3_000)
        assertEquals(2, host.starts)
        assertEquals("partial:note this", host.ui.last())
        host.advanceTo(3_200)
        listensOn.ready(2)
        phrase(2, "and send it", at = 4_000)
        assertEquals("partial:note this and send it", host.ui.last())

        host.advanceTo(10_099)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(10_100)
        assertEquals("results:note this and send it", host.ui.last())
    }

    @Test fun noMatchWhileAServiceListensOnKeepsItsPhrases() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "note this", at = 500)
        host.advanceTo(2_000)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(2_900)
        listensOn.endOfSpeech(1)
        host.advanceTo(2_940)
        listensOn.error(1, noMatch)
        host.advanceTo(2_940)

        assertEquals(2, host.starts)
        assertEquals("partial:note this", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("error") })
    }

    @Test fun aFailureWhileAServiceListensOnDeliversItsPhrasesWithoutTheHalfPhrase() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "note this", at = 500)
        host.advanceTo(2_500)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, " and send it to")
        assertEquals("partial:note this and send it to", host.ui.last())

        host.advanceTo(2_800)
        listensOn.error(1, audio)
        assertEquals("results:note this", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("error") })
        assertFalse(host.timerSet)
    }

    @Test fun silenceWhileAServiceListensOnLastsTheChosenWait() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        host.advanceTo(6_099)
        assertFalse("end" in host.ui)
        host.advanceTo(6_100)
        assertEquals(listOf("ready", "end", "results:"), host.ui)
        assertEquals("cancel", host.service.last())
        assertEquals(1, host.starts)
    }

    @Test fun aSoundWithNoWordsAfterAPhraseEndsThePauseAfterItAndNotTheSession() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "turn off", at = 800)
        // A cough. The service hears it begin and end, and returns nothing for it.
        host.advanceTo(3_000)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(3_900)
        listensOn.endOfSpeech(1)

        // Six seconds after the cough. The service is not waited for: it has had the whole pause.
        host.advanceTo(9_899)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(9_900)
        assertEquals(listOf("end", "results:turn off"), host.ui.takeLast(2))
        assertEquals("cancel", host.service.last())
        assertFalse("stop" in host.service)
    }

    @Test fun aPhraseThatComesBackAfterSpeechBeganAgainDoesNotStartThePause() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        host.advanceTo(800)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, "turn off")
        host.advanceTo(1_700)
        listensOn.endOfSpeech(1)
        host.advanceTo(1_730)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(1_740)
        listensOn.segment(1, "turn off")
        assertEquals("partial:turn off", host.ui.last())

        // The speaker goes on for seven seconds before any text shows. Nothing ends meanwhile.
        host.advanceTo(9_000)
        assertFalse("end" in host.ui)
        listensOn.partial(1, " the lights in the kitchen")
        host.advanceTo(9_500)
        listensOn.endOfSpeech(1)
        host.advanceTo(9_550)
        listensOn.segment(1, " the lights in the kitchen")
        assertEquals("partial:turn off the lights in the kitchen", host.ui.last())

        host.advanceTo(14_999)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(15_000)
        assertEquals("results:turn off the lights in the kitchen", host.ui.last())
        assertEquals(1, host.starts)
    }

    @Test fun aServiceThatListensOnEndsAtTheSessionLimitHoweverMuchIsSaid() {
        listensOn.listen()
        listensOn.ready(1)
        var at = 500L
        while (at < 58_000) {
            phrase(1, " more", at)
            at += 2_000
        }
        host.advanceTo(59_000)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, " and more")

        host.advanceTo(59_999)
        assertFalse("end" in host.ui)
        host.advanceTo(DictationFlow.MAX_SESSION_MS)
        assertEquals("end", host.ui.last())
        assertEquals("stop", host.service.last())

        // Only the phrase in flight is waited for, as with Done.
        listensOn.segment(1, " and more")
        assertTrue(host.ui.last().startsWith("results:more more"))
        assertTrue(host.ui.last().endsWith("and more"))
        host.advanceTo(120_000)
        assertEquals(1, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun aPhraseWithoutTextChangesNothing() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        host.advanceTo(800)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, "turn off")
        val told = host.ui.toList()

        listensOn.segment(1, " ")
        assertEquals(told, host.ui)
        listensOn.partial(1, "turn off the")
        assertEquals("partial:turn off the", host.ui.last())
    }

    @Test fun aServiceThatListensOnWithoutReturningPhrasesKeepsTheWordsBeforeThePause() {
        // As recorded on a phone, from a request with a minimum length and no segmented session. At the pause the
        // service reports the end of speech and goes on listening. Then its partial text starts over, with a space
        // in front, and its final is empty.
        listensOn.listen()
        host.advanceTo(51)
        listensOn.ready(1)
        host.advanceTo(1_254)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(3_532)
        listensOn.partial(1, "remind me to call")
        host.advanceTo(3_952)
        listensOn.endOfSpeech(1)
        host.advanceTo(3_962)
        listensOn.partial(1, "remind me to call")

        host.advanceTo(5_619)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(6_396)
        listensOn.partial(1, " the")
        assertEquals("partial:remind me to call the", host.ui.last())
        host.advanceTo(7_552)
        listensOn.partial(1, " the dentist tomorrow")
        host.advanceTo(7_919)
        listensOn.endOfSpeech(1)
        host.advanceTo(7_925)
        listensOn.partial(1, " the dentist tomorrow")
        host.advanceTo(7_953)
        listensOn.results(1, null)
        host.advanceTo(7_953)
        assertEquals("partial:remind me to call the dentist tomorrow", host.ui.last())
        assertEquals(2, host.starts)
        host.advanceTo(8_033)
        listensOn.ready(2)

        // Six seconds after the last words were heard, at 7_552, and not six seconds after the first pause.
        host.advanceTo(13_551)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(13_552)
        assertEquals("results:remind me to call the dentist tomorrow", host.ui.last())
    }

    @Test fun aWordReportedOnlyAfterItsEndOfSpeechIsAddedToThePhraseBeforeThePause() {
        // The same phone. The word after the pause arrived as partial text only after its end of speech.
        listensOn.listen()
        host.advanceTo(61)
        listensOn.ready(1)
        host.advanceTo(1_518)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(3_635)
        listensOn.partial(1, "testing 1 2")
        host.advanceTo(3_820)
        listensOn.endOfSpeech(1)
        host.advanceTo(3_827)
        listensOn.partial(1, "testing 1 2 3")

        host.advanceTo(5_215)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(6_129)
        listensOn.endOfSpeech(1)
        host.advanceTo(6_138)
        listensOn.partial(1, " 4")
        assertEquals("partial:testing 1 2 3 4", host.ui.last())
        host.advanceTo(6_165)
        listensOn.results(1, null)
        host.advanceTo(6_165)
        assertEquals(2, host.starts)
        host.advanceTo(6_247)
        listensOn.ready(2)

        // Six seconds after that word ended, at 6_129.
        host.advanceTo(12_128)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(12_129)
        assertEquals("results:testing 1 2 3 4", host.ui.last())
    }

    @Test fun aServiceThatRepeatsTheEarlierWordsAfterThePauseDoesNotDoubleThem() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        host.advanceTo(1_000)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, "remind me to call")
        host.advanceTo(3_000)
        listensOn.endOfSpeech(1)

        // This one goes on with the whole text so far, not only the new words.
        host.advanceTo(5_000)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(5_500)
        listensOn.partial(1, "remind me to call the dentist")
        assertEquals("partial:remind me to call the dentist", host.ui.last())
        host.advanceTo(7_000)
        listensOn.endOfSpeech(1)
        host.advanceTo(7_100)
        listensOn.results(1, "remind me to call the dentist tomorrow")
        host.advanceTo(7_100)
        assertEquals("partial:remind me to call the dentist tomorrow", host.ui.last())
    }

    @Test fun aPlainServiceThatReportsSpeechAgainJustBeforeItsFinalIsNotDoubled() {
        // As recorded on the same phone from a request with no hints: the speaker went on while the service ended
        // the utterance, so a beginning of speech came between its end of speech and its final.
        listensOn.listen()
        host.advanceTo(61)
        listensOn.ready(1)
        host.advanceTo(2_106)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(4_762)
        listensOn.partial(1, "testing 1 2")
        host.advanceTo(4_985)
        listensOn.endOfSpeech(1)
        host.advanceTo(5_018)
        listensOn.beginningOfSpeech(1)
        host.advanceTo(5_021)
        listensOn.results(1, "testing 1 2")
        host.advanceTo(5_021)

        assertEquals("partial:testing 1 2", host.ui.last())
        assertEquals(2, host.starts)
    }

    @Test fun aFinalForTheWholeUtteranceIsNotAddedToItsPhrases() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "turn off", at = 800)
        phrase(1, " the lights", at = 3_000)
        host.advanceTo(4_000)
        listensOn.results(1, "Turn off the lights")
        assertEquals("partial:Turn off the lights", host.ui.last())
    }

    @Test fun aFinalWithOnlyTheLastWordsIsAddedAfterThePhrases() {
        listensOn.listen()
        host.advanceTo(100)
        listensOn.ready(1)
        phrase(1, "turn off", at = 800)
        host.advanceTo(3_000)
        listensOn.beginningOfSpeech(1)
        listensOn.partial(1, " the")
        host.advanceTo(3_900)
        listensOn.endOfSpeech(1)
        host.advanceTo(3_950)
        listensOn.results(1, "the lights")
        assertEquals("partial:turn off the lights", host.ui.last())
    }

    @Test fun withNoPauseSpeechAfterTheEndOfSpeechCountsForNothing() {
        val handsFree = DictationFlow(6_000, host, pauseMs = 0)
        handsFree.listen()
        host.advanceTo(100)
        handsFree.ready(1)
        host.advanceTo(1_000)
        handsFree.beginningOfSpeech(1)
        handsFree.partial(1, "what time is it")
        host.advanceTo(2_400)
        handsFree.endOfSpeech(1)
        host.advanceTo(2_400)
        assertEquals("end", host.ui.last())

        // The request went out without asking the service to listen on. A sound after the end changes nothing.
        host.advanceTo(2_420)
        handsFree.beginningOfSpeech(1)
        assertEquals(1, host.ui.count { it == "speech" })
        host.advanceTo(2_450)
        handsFree.results(1, "what time is it")
        assertEquals("results:what time is it", host.ui.last())
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
    }

    @Test fun silenceLastsTheChosenWaitHoweverEarlyTheServiceGivesUp() {
        flow.listen()
        host.advanceTo(200)
        flow.ready(1)
        // The service stops after five seconds of silence. The wait began when the mic opened, at 200.
        host.advanceTo(5_200)
        flow.error(1, noMatch)
        host.advanceTo(5_200)
        assertEquals(2, host.starts)
        host.advanceTo(5_400)
        flow.ready(2)

        host.advanceTo(6_199)
        assertFalse("end" in host.ui)
        host.advanceTo(6_200)
        assertEquals(listOf("ready", "ready", "end", "results:"), host.ui)
        assertEquals("cancel", host.service.last())
    }

    @Test fun theMicIsNotOpenedAgainForTheLastMomentOfTheWait() {
        flow.listen()
        host.advanceTo(200)
        flow.ready(1)
        // The service gives up a fifth of a second before the wait ends.
        host.advanceTo(6_000)
        flow.error(1, noMatch)
        assertEquals(listOf("ready", "results:"), host.ui)
        assertEquals(1, host.starts)
        assertFalse(host.timerSet)
    }

    @Test fun doneDuringTheWaitEndsAtOnce() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "hello", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)

        // Nothing is being said. Done must not wait for the rest of the six seconds, or for the service.
        host.advanceTo(2_000)
        flow.stop()
        assertEquals("results:hello", host.ui.last())
        assertEquals("cancel", host.service.last())
        assertFalse(host.timerSet)

        host.advanceTo(60_000)
        assertEquals(2, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun doneBetweenTwoUtterancesEndsAtOnce() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        host.advanceTo(150)
        flow.error(1, noMatch)
        assertTrue(host.timerSet)

        flow.stop()
        assertEquals(listOf("ready", "results:"), host.ui)
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
    }

    @Test fun doneWhileSpeakingWaitsOnlyForThoseWords() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        host.advanceTo(1_000)
        flow.partial(1, "send it")
        host.advanceTo(1_500)
        flow.stop()
        assertEquals("stop", host.service.last())
        assertFalse(host.ui.any { it.startsWith("results") })

        host.advanceTo(1_700)
        flow.results(1, "send it now")
        assertEquals("results:send it now", host.ui.last())
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
    }

    @Test fun cancelDuringTheWaitDropsEverythingAtOnce() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "hello", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)
        val told = host.ui.toList()

        host.advanceTo(2_000)
        flow.close()
        assertEquals("cancel", host.service.last())
        assertFalse(host.timerSet)

        // Nothing the service still sends, and no timer, reaches the mic UI after a cancel.
        flow.partial(2, "more")
        flow.results(2, "more")
        flow.error(2, network)
        host.advanceTo(60_000)
        assertEquals(told, host.ui)
        assertEquals(2, host.starts)
    }

    @Test fun aServiceThatReturnsAtOnceIsNotRestartedInATightLoop() {
        flow.listen()
        flow.ready(1)
        host.advanceTo(10)
        flow.error(1, noMatch)
        host.advanceTo(249)
        assertEquals(1, host.starts)
        host.advanceTo(250)
        assertEquals(2, host.starts)

        host.advanceTo(260)
        flow.error(2, noMatch)
        host.advanceTo(499)
        assertEquals(2, host.starts)
        host.advanceTo(500)
        assertEquals(3, host.starts)
    }

    @Test fun aBusyServiceIsAskedAgainAndALaterFailureKeepsTheWords() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "note this", at = 500)
        assertEquals(2, host.starts)

        host.advanceTo(1_510)
        flow.error(2, busy)
        host.advanceTo(1_750)
        assertEquals(3, host.starts)

        host.advanceTo(2_000)
        flow.error(3, network)
        assertEquals("results:note this", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("error") })
    }

    @Test fun aLaterFailureInMidPhraseDeliversOnlyTheConfirmedWords() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "note this", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)

        // A call comes in while the second phrase is being said. Its words were never confirmed.
        host.advanceTo(2_500)
        flow.beginningOfSpeech(2)
        flow.partial(2, "and send it to")
        assertEquals("partial:note this and send it to", host.ui.last())
        host.advanceTo(2_800)
        flow.error(2, audio)

        assertEquals("results:note this", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("error") })
        assertFalse(host.timerSet)
    }

    @Test fun aLaterFailureInMidPhraseWithNothingConfirmedIsAFailure() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        // Silence, and the service gives up. The second utterance fails while the first words are being said.
        host.advanceTo(3_000)
        flow.error(1, noMatch)
        host.advanceTo(3_300)
        flow.ready(2)
        host.advanceTo(4_000)
        flow.partial(2, "send it to")
        host.advanceTo(4_200)
        flow.error(2, audio)

        // Reported as a failure, so the half phrase goes to onInterrupted and is never sent as the text.
        assertEquals("error:$audio", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("results") })
        assertFalse(host.timerSet)
    }

    @Test fun oneTapEndsAtTheSessionLimitHoweverMuchIsSaid() {
        flow.listen()
        // Someone, or a television, keeps talking: a phrase every two seconds, each restarting the wait.
        var turn = 1
        var at = 500L
        while (at < 58_000) {
            flow.ready(turn)
            say(turn, "more", at)
            turn = host.starts
            at += 2_000
        }
        flow.ready(turn)
        host.advanceTo(59_000)
        flow.beginningOfSpeech(turn)
        flow.partial(turn, "and more")

        host.advanceTo(59_999)
        assertFalse("end" in host.ui)
        host.advanceTo(DictationFlow.MAX_SESSION_MS)
        assertEquals("end", host.ui.last())
        assertEquals("stop", host.service.last())

        // Only the words in flight are waited for, as with Done.
        flow.results(turn, "and more")
        assertTrue(host.ui.last().startsWith("results:more more"))
        assertTrue(host.ui.last().endsWith("and more"))
        host.advanceTo(120_000)
        assertEquals(turn, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun silenceCannotOutlastTheSessionLimit() {
        val short = DictationFlow(6_000, host, maxMs = 4_000)
        short.listen()
        host.advanceTo(100)
        short.ready(1)
        host.advanceTo(3_999)
        assertFalse("end" in host.ui)
        host.advanceTo(4_000)
        assertEquals(listOf("ready", "end", "results:"), host.ui)
    }

    @Test fun withNoPauseTheWaitCoversOnlyTheFirstWord() {
        val handsFree = DictationFlow(6_000, host, pauseMs = 0)
        handsFree.listen()
        host.advanceTo(100)
        handsFree.ready(1)
        // The service gives up on the silence early. The mic is opened again, because nothing was said yet.
        host.advanceTo(3_000)
        handsFree.error(1, noMatch)
        host.advanceTo(3_000)
        assertEquals(2, host.starts)
        host.advanceTo(3_200)
        handsFree.ready(2)

        host.advanceTo(5_000)
        handsFree.beginningOfSpeech(2)
        handsFree.partial(2, "what time is it")
        // The service decides the speaker has finished. Nothing more is waited for, and the mic is not reopened.
        host.advanceTo(6_400)
        handsFree.endOfSpeech(2)
        host.advanceTo(6_400)
        assertEquals("end", host.ui.last())
        host.advanceTo(6_500)
        handsFree.results(2, "what time is it")

        assertEquals("results:what time is it", host.ui.last())
        host.advanceTo(60_000)
        assertEquals(2, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun withNoPauseSilenceStillLastsTheChosenWait() {
        val handsFree = DictationFlow(6_000, host, pauseMs = 0)
        handsFree.listen()
        host.advanceTo(100)
        handsFree.ready(1)
        host.advanceTo(6_099)
        assertFalse("end" in host.ui)
        host.advanceTo(6_100)
        assertEquals(listOf("ready", "end", "results:"), host.ui)
    }

    @Test fun withNoPauseACoughBeforeTheFirstWordDoesNotEndTheSession() {
        val handsFree = DictationFlow(6_000, host, pauseMs = 0)
        handsFree.listen()
        host.advanceTo(100)
        handsFree.ready(1)
        // The service hears a sound begin and end, and finds no words in it.
        host.advanceTo(1_000)
        handsFree.beginningOfSpeech(1)
        host.advanceTo(1_400)
        handsFree.endOfSpeech(1)
        host.advanceTo(1_600)
        handsFree.error(1, noMatch)
        host.advanceTo(1_600)

        // No "didn't catch that": the mic is opened again for what is left of the wait for the first words.
        assertEquals(listOf("ready", "speech"), host.ui)
        assertEquals(2, host.starts)
        host.advanceTo(1_800)
        handsFree.ready(2)

        // That wait began when the mic first opened, at 100. The cough did not start it again.
        host.advanceTo(6_099)
        assertFalse("end" in host.ui)
        host.advanceTo(6_100)
        assertEquals(listOf("ready", "speech", "ready", "end", "results:"), host.ui)
    }

    @Test fun withNoPauseWordsTheServiceSentOnlyAsAFinalAreSentAtOnce() {
        val handsFree = DictationFlow(6_000, host, pauseMs = 0)
        handsFree.listen()
        host.advanceTo(100)
        handsFree.ready(1)
        // No partial text from this service, so until its final a phrase looks the same as a cough.
        host.advanceTo(1_000)
        handsFree.beginningOfSpeech(1)
        host.advanceTo(2_400)
        handsFree.endOfSpeech(1)
        host.advanceTo(2_500)
        handsFree.results(1, "what time is it")

        // Nothing waits for the rest of the six seconds.
        assertEquals("results:what time is it", host.ui.last())
        assertFalse(host.timerSet)
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun wordsTheServiceSentOnlyAsAFinalStillGetThePause() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        // No partial text again, and the phrase ends after the six seconds for the first words have passed.
        host.advanceTo(5_000)
        flow.beginningOfSpeech(1)
        host.advanceTo(7_400)
        flow.endOfSpeech(1)
        host.advanceTo(7_500)
        flow.results(1, "note this")
        host.advanceTo(7_500)

        // The mic is opened again, and stays open until six seconds after the phrase ended.
        assertEquals("partial:note this", host.ui.last())
        assertEquals(2, host.starts)
        host.advanceTo(7_700)
        flow.ready(2)
        host.advanceTo(13_399)
        assertFalse("end" in host.ui)
        host.advanceTo(13_400)
        assertEquals(listOf("end", "results:note this"), host.ui.takeLast(2))
    }

    @Test fun aFailureWithNothingHeardIsReported() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        host.advanceTo(300)
        flow.error(1, network)
        assertEquals(listOf("ready", "error:$network"), host.ui)
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
    }

    @Test fun aStartThatFailsIsReportedOnce() {
        host.canStart = false
        flow.listen()
        assertEquals(listOf("error:5"), host.ui)
        assertFalse(host.timerSet)
    }

    @Test fun callbacksOfAnEarlierUtteranceAreIgnored() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "first", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)
        val told = host.ui.toList()

        flow.partial(1, "stale")
        flow.results(1, "stale")
        flow.error(1, network)
        assertEquals(told, host.ui)
    }
}
