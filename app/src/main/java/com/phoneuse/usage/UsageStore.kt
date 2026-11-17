package com.phoneuse.usage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What a model request was for (board Usage's breakdown). */
enum class RequestKind { CHAT, DEVICE, SEARCH, ROUTINE, UNDERSTAND }

/** One stored row: requests of one kind on one day, and the tokens they used (0 when the backend did not say). */
data class UsageRow(val date: LocalDate, val kind: RequestKind, val count: Int, val inputTokens: Int = 0, val outputTokens: Int = 0)

/** Requests made on one day, by kind, and the tokens they used. */
data class DayUsage(val date: LocalDate, val counts: Map<RequestKind, Int>, val inputTokens: Int = 0, val outputTokens: Int = 0) {
    val total: Int get() = counts.values.sum()
}

object UsageDays {
    /** One entry per day for the [days] days ending [today], oldest first; days without rows count zero. */
    fun fill(today: LocalDate, days: Int, rows: List<UsageRow>): List<DayUsage> =
        (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val day = rows.filter { it.date == date }
            val counts = day.groupBy { it.kind }.mapValues { (_, r) -> r.sumOf { it.count } }
            DayUsage(date, counts, day.sumOf { it.inputTokens }, day.sumOf { it.outputTokens })
        }
}

/**
 * Usage on the device: a request log of daily counts by kind (`usage.db`, a separate small database; rows older
 * than [KEEP_DAYS] are dropped).
 */
class UsageStore(context: Context, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val helper = Helper(context.applicationContext)

    /** Serialises every write. */
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _version = MutableStateFlow(0L)

    /** Bumped after every [record], so screens re-read [daily]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        // Earlier versions kept the last limits report and its alert marks here. Runs at every start and does
        // nothing once they are gone; a failure just tries again next start.
        scope.launch {
            writeLock.withLock {
                guarded { helper.writableDatabase.delete("kv", "key IN (?, ?)", arrayOf(RETIRED_LATEST, RETIRED_NOTIFIED)) }
            }
        }
    }

    suspend fun record(kind: RequestKind, at: Long = System.currentTimeMillis(), count: Int = 1, inputTokens: Int = 0, outputTokens: Int = 0) {
        if (count <= 0) return
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                guarded {
                    val day = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate()
                    val db = helper.writableDatabase
                    db.beginTransaction()
                    try {
                        db.execSQL(
                            "INSERT INTO daily(day, kind, n, in_tokens, out_tokens) VALUES(?, ?, ?, ?, ?) ON CONFLICT(day, kind) " +
                                "DO UPDATE SET n = n + excluded.n, in_tokens = in_tokens + excluded.in_tokens, out_tokens = out_tokens + excluded.out_tokens",
                            arrayOf<Any>(day.toString(), kind.name, count, inputTokens, outputTokens),
                        )
                        db.delete("daily", "day < ?", arrayOf(day.minusDays(KEEP_DAYS).toString()))
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
        }
        _version.update { it + 1 }
    }

    /** Settings "모든 데이터 지우기": the daily request counts go. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                guarded { helper.writableDatabase.delete("daily", null, null) }
            }
        }
        _version.update { it + 1 }
    }

    /** The last [days] days including today, oldest first. */
    suspend fun daily(days: Int = 7): List<DayUsage> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zone())
        val rows = guarded {
            helper.readableDatabase.query(
                "daily", arrayOf("day", "kind", "n", "in_tokens", "out_tokens"), "day >= ?", arrayOf(today.minusDays(days - 1L).toString()),
                null, null, null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val kind = RequestKind.entries.firstOrNull { it.name == c.getString(1) }
                        val date = runCatching { LocalDate.parse(c.getString(0)) }.getOrNull()
                        if (kind != null && date != null) add(UsageRow(date, kind, c.getInt(2), c.getInt(3), c.getInt(4)))
                    }
                }
            }
        }.orEmpty()
        UsageDays.fill(today, days, rows)
    }

    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.e("PhoneUse", "usage store failed", e)
        null
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE daily (day TEXT NOT NULL, kind TEXT NOT NULL, n INTEGER NOT NULL, " +
                    "in_tokens INTEGER NOT NULL DEFAULT 0, out_tokens INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, kind))",
            )
            // Empty now; kept so every install has the same schema and the start-up cleanup always finds it.
            db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2: tokens per day and kind (notification analysis reports them).
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE daily ADD COLUMN in_tokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily ADD COLUMN out_tokens INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private companion object {
        const val DB_NAME = "usage.db"
        const val RETIRED_LATEST = "latest"
        const val RETIRED_NOTIFIED = "notified"
        const val KEEP_DAYS = 30L
    }
}
