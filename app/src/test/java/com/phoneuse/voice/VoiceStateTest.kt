package com.phoneuse.voice

import com.phoneuse.app.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceStateTest {
    private fun run(vararg events: VoiceEvent): VoiceState = events.fold(VoiceState()) { s, e -> s.reduce(e) }

    @Test fun partialResultsShowLiveAndFinalEndsTheSession() {
        val listening = run(VoiceEvent.Start, VoiceEvent.Ready, VoiceEvent.Partial("내일 날씨"), VoiceEvent.Partial("내일 날씨 알려줘 "))
        assertEquals(VoicePhase.LISTENING, listening.phase)
        assertEquals("내일 날씨 알려줘", listening.partial)

        val ending = listening.reduce(VoiceEvent.EndOfSpeech)
        assertEquals(VoicePhase.PROCESSING, ending.phase)
        assertEquals(0f, ending.level)
        // A late partial while finishing still updates the words.
        assertEquals("내일 날씨 알려줘요", ending.reduce(VoiceEvent.Partial("내일 날씨 알려줘요")).partial)

        assertEquals(VoiceState(), ending.reduce(VoiceEvent.Final(listOf("내일 날씨 알려줘"))))
    }

    @Test fun anEmptyPartialKeepsTheWordsAlreadyShown() {
        val s = run(VoiceEvent.Start, VoiceEvent.Ready, VoiceEvent.Partial("버스"), VoiceEvent.Partial(" "))
        assertEquals("버스", s.partial)
    }

    @Test fun startingIsListeningButNotYetReady() {
        val s = run(VoiceEvent.Start)
        assertEquals(VoicePhase.STARTING, s.phase)
        assertTrue(s.listening)
        assertTrue(s.active)
        assertFalse(VoiceState().active)
    }

    @Test fun loudnessIsTrackedOnlyWhileListening() {
        val s = run(VoiceEvent.Start, VoiceEvent.Ready, VoiceEvent.Rms(4f))
        assertEquals(0.5f, s.level, 0.001f)
        val idle = VoiceState()
        assertSame(idle, idle.reduce(VoiceEvent.Rms(8f)))
    }

    @Test fun rmsMapsToZeroToOne() {
        assertEquals(0f, rmsLevel(-10f))
        assertEquals(0f, rmsLevel(-2f))
        assertEquals(1f, rmsLevel(10f))
        assertEquals(1f, rmsLevel(30f))
    }

    @Test fun eventsAfterCancelAreIgnored() {
        val idle = run(VoiceEvent.Start, VoiceEvent.Ready, VoiceEvent.Reset)
        assertEquals(VoiceState(), idle)
        assertEquals(VoiceState(), idle.reduce(VoiceEvent.Partial("늦게 온 말")))
        assertEquals(VoiceState(), idle.reduce(VoiceEvent.EndOfSpeech))
        assertEquals(VoiceState(), idle.reduce(VoiceEvent.Failed(VoiceError.NETWORK)))
    }

    @Test fun failureShowsTheErrorAndDropsTheWords() {
        val s = run(VoiceEvent.Start, VoiceEvent.Ready, VoiceEvent.Partial("abc"), VoiceEvent.Failed(VoiceError.NETWORK))
        assertEquals(VoicePhase.ERROR, s.phase)
        assertEquals(VoiceError.NETWORK, s.error)
        assertEquals("", s.partial)
        assertTrue(s.active)
        assertFalse(s.listening)
        assertEquals(VoiceState(), s.reduce(VoiceEvent.Reset))
        // Trying again from the error starts a fresh session.
        assertEquals(VoiceState(phase = VoicePhase.STARTING), s.reduce(VoiceEvent.Start))
    }

    @Test fun finalTextPrefersTheResultThenWhatWasHeard() {
        val heard = VoiceState(phase = VoicePhase.PROCESSING, partial = "지하철만")
        assertEquals("버스 말고 지하철만", finalText(heard, listOf("  ", " 버스 말고 지하철만 ")))
        assertEquals("지하철만", finalText(heard, emptyList()))
        assertNull(finalText(VoiceState(phase = VoicePhase.PROCESSING), emptyList()))
        assertNull(finalText(VoiceState(phase = VoicePhase.PROCESSING), listOf("")))
    }

    @Test fun errorCodesMapToTheInlineMessages() {
        // Values of SpeechRecognizer.ERROR_*.
        assertEquals(VoiceError.NETWORK, voiceErrorOf(1))
        assertEquals(VoiceError.NETWORK, voiceErrorOf(2))
        assertEquals(VoiceError.AUDIO, voiceErrorOf(3))
        assertEquals(VoiceError.NETWORK, voiceErrorOf(4))
        assertEquals(VoiceError.UNAVAILABLE, voiceErrorOf(5))
        assertEquals(VoiceError.NO_MATCH, voiceErrorOf(6))
        assertEquals(VoiceError.NO_MATCH, voiceErrorOf(7))
        assertEquals(VoiceError.BUSY, voiceErrorOf(8))
        assertEquals(VoiceError.PERMISSION, voiceErrorOf(9))
        assertEquals(VoiceError.BUSY, voiceErrorOf(10))
        assertEquals(VoiceError.NETWORK, voiceErrorOf(11))
        assertEquals(VoiceError.LANGUAGE, voiceErrorOf(12))
        assertEquals(VoiceError.LANGUAGE, voiceErrorOf(13))
        assertEquals(VoiceError.UNAVAILABLE, voiceErrorOf(99))
    }

    @Test fun everyErrorHasAShortKoreanMessage() {
        for (e in VoiceError.values()) {
            assertTrue(e.name, e.message.isNotBlank() && e.message.length < 40)
        }
        assertEquals("잘 못 들었어요. 다시 말해 주세요", VoiceError.NO_MATCH.message)
    }

    @Test fun languageFollowsTheVoiceSettingThenTheDevice() {
        assertEquals("ko-KR", speechLanguageTag(VoiceLanguage.KOREAN, Locale.US))
        assertEquals("en-US", speechLanguageTag(VoiceLanguage.ENGLISH, Locale.KOREA))
        assertEquals("ja-JP", speechLanguageTag(VoiceLanguage.SYSTEM, Locale.JAPAN))
    }

    @Test fun googleRecognizerIsNamedExplicitlyNeverOurStub() {
        val own = "com.phoneuse" to "com.phoneuse.assist.StubRecognitionService"
        val tts = "com.google.android.tts" to "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
        val gsa = "com.google.android.googlequicksearchbox" to "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        val other = "com.samsung.android.bixby.agent" to "com.samsung.Recognizer"

        assertEquals(VoiceEngine.Service(gsa.first, gsa.second), pickVoiceEngine(listOf(own, other, tts, gsa), "com.phoneuse", true))
        assertEquals(VoiceEngine.Service(tts.first, tts.second), pickVoiceEngine(listOf(own, other, tts), "com.phoneuse", true))
        assertEquals(VoiceEngine.Service(other.first, other.second), pickVoiceEngine(listOf(own, other), "com.phoneuse", false))
    }

    @Test fun withoutAServiceUseOnDeviceThenTheDialog() {
        val own = "com.phoneuse" to "com.phoneuse.assist.StubRecognitionService"
        assertEquals(VoiceEngine.OnDevice, pickVoiceEngine(listOf(own), "com.phoneuse", onDeviceAvailable = true))
        assertEquals(VoiceEngine.Dialog, pickVoiceEngine(listOf(own), "com.phoneuse", onDeviceAvailable = false))
        assertEquals(VoiceEngine.Dialog, pickVoiceEngine(emptyList(), "com.phoneuse", onDeviceAvailable = false))
    }
}
