package com.phoneuse.understand

import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelException
import com.phoneuse.phone.StoredNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/** What [UnderstandRun] needs from the app; a fake in tests. */
interface UnderstandDeps {
    /** Settings "알림 내용 분석". */
    suspend fun enabled(): Boolean

    /** The light model and its id, or null when nothing is set up or automatic requests are paused. */
    suspend fun client(): Pair<ModelClient, String>?
    val store: UnderstandingStore
    suspend fun notifications(keys: List<String>): List<StoredNotification>
    suspend fun recordUsage(inputTokens: Int, outputTokens: Int)
    fun appLanguage(): String
    fun zone(): ZoneId
    fun now(): Long
    suspend fun pokeProactive()
}

sealed interface RunResult {
    data object Disabled : RunResult
    data object NoBackend : RunResult
    data object NothingReady : RunResult
    data class Done(val analysed: Int, val more: Boolean) : RunResult
    data class OverBudget(val until: Long) : RunResult
    data class TooSoon(val at: Long) : RunResult
    data class Paused(val until: Long) : RunResult
    data class Failed(val keys: Int) : RunResult
}

/**
 * One analysis call: up to [UnderstandingStore.BATCH] waiting notifications, with the recent events, to the light model;
 * the answer is checked ([JudgmentCheck]), merged ([EventMerger]) and stored. Nothing is sent while the setting is
 * off, and at most [DAILY_CALLS] calls a day, [MIN_GAP_MS] apart.
 */
class UnderstandRun(private val deps: UnderstandDeps) {
    suspend fun once(): RunResult {
        if (!deps.enabled()) return RunResult.Disabled
        val (model, modelId) = deps.client() ?: return RunResult.NoBackend
        val store = deps.store
        val now = deps.now()
        val today = Instant.ofEpochMilli(now).atZone(deps.zone()).toLocalDate()
        if (store.callsOn(today.toString()) >= DAILY_CALLS) {
            return RunResult.OverBudget(today.plusDays(1).atStartOfDay(deps.zone()).toInstant().toEpochMilli())
        }
        store.lastCallAt()?.let { last -> if (now - last < MIN_GAP_MS) return RunResult.TooSoon(last + MIN_GAP_MS) }

        val keys = store.pendingBatch(now)
        if (keys.isEmpty()) return RunResult.NothingReady
        val found = deps.notifications(keys).associateBy { it.key }
        if (found.size < keys.size) store.dropOrphans()
        val batch = keys.mapNotNull { found[it] }
        if (batch.isEmpty()) return RunResult.NothingReady

        val turn = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                model.next(UnderstandPrompt.INSTRUCTIONS, UnderstandPrompt.input(batch, store.recentEvents(now), deps.zone(), deps.appLanguage()), emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            store.countCall(today.toString(), now)
            if (e.code == ModelException.PLAN_USAGE_LIMIT) {
                store.postpone(batch.map { it.key }, now + PAUSE_MS)
                return RunResult.Paused(now + PAUSE_MS)
            }
            null
        } catch (e: Exception) {
            null
        }
        if (turn != null) {
            store.countCall(today.toString(), now)
            deps.recordUsage(turn.usage?.inputTokens ?: 0, turn.usage?.outputTokens ?: 0)
        }
        val judgments = turn?.let { UnderstandPrompt.parse(it.text, batch.size) }
        if (judgments == null) {
            store.fail(batch.map { it.key }, now)
            return RunResult.Failed(batch.size)
        }
        val checked = judgments.mapValues { (i, j) -> JudgmentCheck.clean(j, batch[i]) }
        store.fail(batch.indices.filter { it !in checked }.map { batch[it].key }, now)
        store.apply(EventMerger.plan(batch, checked, store.recentEvents(now)), modelId, now)
        deps.pokeProactive()
        return RunResult.Done(checked.size, more = store.pendingBatch(now, 1).isNotEmpty())
    }

    companion object {
        const val DAILY_CALLS = 200
        const val MIN_GAP_MS = 60_000L
        const val TIMEOUT_MS = 30_000L
        const val PAUSE_MS = 60 * 60_000L
    }
}
