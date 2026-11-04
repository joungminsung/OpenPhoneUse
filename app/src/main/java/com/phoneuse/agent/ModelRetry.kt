package com.phoneuse.agent

import com.phoneuse.i18n.tr
import com.phoneuse.model.ModelException
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * When and how long to retry a failed model call; shared by the main loop and the delegate helper.
 *
 * - A network error (no status) is retried [NETWORK_RETRIES] times, but only once when the stream had already sent
 *   output (a long turn that broke midway would run, and bill, all over again).
 * - A 429 or 5xx is retried [RATE_RETRIES] times with exponential backoff and jitter, waiting at least what the server
 *   asked for (Retry-After or a rate-limit reset header), never more than [MAX_WAIT_MS] at a time.
 * - A usage cap (the subscription is exhausted for hours), any other 4xx, a context overflow and a response the
 *   model ended early are never retried: the same request cannot succeed now.
 */
internal object ModelRetry {
    const val NETWORK_RETRIES = 2
    const val RATE_RETRIES = 4
    const val BASE_MS = 1000L
    const val MAX_WAIT_MS = 30_000L

    fun usageLimit(e: ModelException): Boolean {
        if (e.code in USAGE_CODES) return true
        val m = e.message.orEmpty()
        return e.status == 429 && (USAGE_CODES.any { m.contains(it) } || m.contains("usage limit", ignoreCase = true))
    }

    fun contextOverflow(e: ModelException): Boolean =
        e.code == "context_length_exceeded" || e.message.orEmpty().contains("context_length_exceeded") ||
            e.message.orEmpty().contains("maximum context length", ignoreCase = true) ||
            e.message.orEmpty().contains("exceeds the context window", ignoreCase = true)

    /** Whether attempt number [attempt] (0 = the first retry) may go after [e]. */
    fun shouldRetry(e: ModelException, attempt: Int): Boolean {
        if (!e.retryable || usageLimit(e) || contextOverflow(e)) return false
        val status = e.status
        return when {
            status == null -> attempt < (if (e.afterOutput) 1 else NETWORK_RETRIES)
            status == 429 || status >= 500 -> attempt < RATE_RETRIES
            else -> false
        }
    }

    /** The wait before retry [attempt]: 1 s, 2 s, 4 s, ... plus up to 50% jitter, at least Retry-After, capped. */
    fun delayMs(e: ModelException, attempt: Int, random: Random = Random.Default): Long {
        val backoff = BASE_MS shl attempt.coerceAtMost(5)
        val jittered = backoff + (backoff * 0.5 * random.nextDouble()).toLong()
        return maxOf(jittered, e.retryAfterMs ?: 0L).coerceAtMost(MAX_WAIT_MS)
    }

    /** Calls [block], retrying as above; [onRetry] runs before each wait. Throws the last [ModelException]. */
    suspend fun <T> call(onRetry: () -> Unit = {}, random: Random = Random.Default, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ModelException) {
                if (!shouldRetry(e, attempt)) throw e
                onRetry()
                delay(delayMs(e, attempt, random))
                attempt++
            }
        }
    }

    /** What the user is told when a model call finally failed with [e]. */
    fun message(e: ModelException): String = when {
        // Already said in full by the plan route (planFailure), and it must not promise a reset or a quick retry.
        e.code == ModelException.PLAN_USAGE_LIMIT -> e.message.orEmpty()
        usageLimit(e) -> tr(
            "You've reached your model usage limit. Try again after it resets, or switch the model or sign-in in Settings.",
            "모델 사용 한도에 도달했어요. 한도가 초기화된 뒤 다시 시도하거나 설정에서 모델이나 로그인 방식을 바꿔 주세요.",
        )
        e.status == 429 -> tr("The model is busy (rate limited). Please try again in a moment.", "모델 요청이 너무 많아요(속도 제한). 잠시 후 다시 시도해 주세요.")
        contextOverflow(e) -> tr("This conversation got too long for the model. Please start a new chat.", "대화가 너무 길어져 모델이 처리할 수 없어요. 새 대화에서 다시 시도해 주세요.")
        else -> tr("Model call failed: ${e.message}", "모델 호출 실패: ${e.message}")
    }

    private val USAGE_CODES = setOf("usage_limit_reached", "usage_not_included", "insufficient_quota")
}
