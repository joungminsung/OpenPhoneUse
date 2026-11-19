package com.phoneuse.agent

import com.phoneuse.i18n.Lang
import com.phoneuse.model.ModelException
import com.phoneuse.model.ModelFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRetryMessageTest {
    private fun network() = ModelException(null, "Network error: timeout", failure = ModelFailure.NETWORK)
    private fun ended() = ModelException(null, "stream ended early", failure = ModelFailure.STREAM_ENDED)
    private fun stopped() = ModelException(200, "The model stopped early (content_filter)", code = "incomplete:content_filter", retryable = false, failure = ModelFailure.STOPPED_EARLY)
    private fun bad() = ModelException(200, "Failed to parse response: x", failure = ModelFailure.BAD_RESPONSE)

    @Test fun networkFailureIsSaidInKorean() {
        assertEquals("모델에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.", ModelRetry.message(network()))
    }

    @Test fun streamEndIsSaidInKorean() {
        assertEquals("모델 응답이 중간에 끊겼어요. 다시 시도해 주세요.", ModelRetry.message(ended()))
    }

    @Test fun stoppedEarlyKeepsTheReason() {
        assertEquals("모델이 답을 끝내기 전에 멈췄어요 (content_filter). 다시 시도해 주세요.", ModelRetry.message(stopped()))
    }

    @Test fun stoppedEarlyWithoutACodeOmitsTheParentheses() {
        val e = ModelException(200, "The model stopped early", retryable = false, failure = ModelFailure.STOPPED_EARLY)
        assertEquals("모델이 답을 끝내기 전에 멈췄어요. 다시 시도해 주세요.", ModelRetry.message(e))
    }

    @Test fun unreadableAnswerIsSaidInKorean() {
        assertEquals("모델 응답을 읽지 못했어요. 다시 시도해 주세요.", ModelRetry.message(bad()))
    }

    @Test fun serverTextIsStillShownAsIs() {
        assertEquals("모델 호출 실패: bad request", ModelRetry.message(ModelException(400, "bad request")))
    }

    @Test fun englishFailuresAreEnglish() {
        val before = Lang.korean
        Lang.korean = false
        try {
            assertEquals("Couldn't reach the model. Check your internet connection and try again.", ModelRetry.message(network()))
            assertEquals("The model's answer was cut off. Please try again.", ModelRetry.message(ended()))
            assertEquals("The model stopped before finishing its answer (content_filter). Please try again.", ModelRetry.message(stopped()))
            assertEquals("The model's answer couldn't be read. Please try again.", ModelRetry.message(bad()))
        } finally {
            Lang.korean = before
        }
    }
}
