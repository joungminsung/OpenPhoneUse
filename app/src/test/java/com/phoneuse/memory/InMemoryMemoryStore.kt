package com.phoneuse.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Keeps memories in memory; for tests. Writes are serialised by [lock]. */
class InMemoryMemoryStore(private val now: () -> Long = { System.currentTimeMillis() }) : MemoryStore {
    private val items = MutableStateFlow<List<Memory>>(emptyList())
    private val lock = Any()
    private var nextId = 1L

    private fun sorted(list: List<Memory>) =
        list.sortedWith(compareByDescending<Memory> { it.updatedAt }.thenByDescending { it.id })

    override fun observe(): Flow<List<Memory>> = items.map { sorted(it) }

    override suspend fun observeOnce(): List<Memory> = sorted(items.value)

    override suspend fun add(memory: NewMemory): AddResult {
        val text = MemoryStore.clean(memory.text)
        require(text.isNotEmpty()) { "empty memory" }
        MemorySecrets.check(memory.text)
        val at = now()
        synchronized(lock) {
            val list = items.value
            val same = list.firstOrNull { MemoryStore.sameText(it.text, text) }
            if (same != null) {
                items.value = list.map {
                    if (it.id != same.id) {
                        it
                    } else {
                        it.copy(
                            confidence = if (memory.confidence == Confidence.SURE) Confidence.SURE else it.confidence,
                            updatedAt = at,
                            date = it.date ?: memory.date,
                            due = it.due ?: memory.due,
                        )
                    }
                }
                return AddResult(same.id, created = false)
            }
            val id = nextId++
            items.value = list + Memory(
                id, text, memory.kind, memory.confidence, at, at,
                memory.conversationId, memory.origin, memory.date, memory.due,
            )
            return AddResult(id, created = true)
        }
    }

    override suspend fun get(id: Long): Memory? = items.value.firstOrNull { it.id == id }

    override suspend fun update(id: Long, text: String, kind: MemoryKind): Boolean {
        val clean = MemoryStore.clean(text)
        if (clean.isEmpty()) return false
        MemorySecrets.check(text)
        val at = now()
        synchronized(lock) {
            val list = items.value
            if (list.none { it.id == id }) return false
            val other = list.firstOrNull { it.id != id && MemoryStore.sameText(it.text, clean) }
            items.value = if (other != null) {
                list.filter { it.id != id }.map {
                    if (it.id == other.id) it.copy(kind = kind, confidence = Confidence.SURE, updatedAt = at) else it
                }
            } else {
                list.map { if (it.id == id) it.copy(text = clean, kind = kind, confidence = Confidence.SURE, updatedAt = at) else it }
            }
            return true
        }
    }

    override suspend fun confirm(id: Long): Boolean = synchronized(lock) {
        val list = items.value
        if (list.none { it.id == id }) return false
        items.value = list.map { if (it.id == id) it.copy(confidence = Confidence.SURE) else it }
        true
    }

    override suspend fun delete(id: Long): Boolean = synchronized(lock) {
        val list = items.value
        if (list.none { it.id == id }) return false
        items.value = list.filter { it.id != id }
        true
    }

    override suspend fun deleteAll() {
        synchronized(lock) { items.value = emptyList() }
    }

    override suspend fun candidates(query: String, limit: Int): List<Memory> {
        val all = observeOnce()
        if (MemoryText.terms(query).isEmpty()) return all.take(limit)
        return MemoryRanker.rank(query, all).filter { it.score > 0 }.take(limit).map { it.memory }
    }
}

class InMemoryAppTipStore(private val now: () -> Long = { System.currentTimeMillis() }) : AppTipStore {
    private val tips = ArrayList<AppTip>()
    private var nextId = 1L

    override suspend fun add(packageName: String, task: String, steps: String): Long = synchronized(tips) {
        val t = MemoryStore.clean(task).take(AppTipStore.MAX_TASK)
        tips.removeAll { it.packageName == packageName && it.task.equals(t, ignoreCase = true) }
        val tip = AppTip(nextId++, packageName, t, steps.take(AppTipStore.MAX_STEPS), now())
        tips += tip
        val mine = tips.filter { it.packageName == packageName }.sortedByDescending { it.createdAt }
        if (mine.size > AppTipStore.PER_APP) tips.removeAll(mine.drop(AppTipStore.PER_APP).toSet())
        tip.id
    }

    override suspend fun top(packageName: String, query: String, n: Int): List<AppTip> = synchronized(tips) {
        AppTips.rank(query, tips.filter { it.packageName == packageName }, n)
    }

    override suspend fun count(): Int = synchronized(tips) { tips.size }

    override suspend fun deleteAllTips() {
        synchronized(tips) { tips.clear() }
    }
}
