package com.phoneuse.phone

import com.phoneuse.i18n.tr
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** How a call ended up in the log; [wire] is the word the model reads. */
enum class CallKind(val wire: String) {
    MISSED("missed"),
    INCOMING("incoming"),
    OUTGOING("outgoing"),
    REJECTED("rejected"),
    BLOCKED("blocked"),
    VOICEMAIL("voicemail"),

    /** A type only this phone's maker knows. */
    OTHER("other"),
}

/**
 * One call log row. [name] is the contact name the phone cached for it (null when the number is not saved);
 * [number] is empty when the caller hid it; [at] is epoch ms.
 */
data class CallEntry(val name: String?, val number: String, val kind: CallKind, val at: Long, val durationSec: Long)

/** The phone's call history; Android's is [AndroidCallLogSource]. */
interface CallLogSource {
    fun canRead(): Boolean

    /** Calls since [since] (epoch ms) of one of [kinds] (null: every kind), newest first, at most [limit]. */
    fun recent(since: Long, kinds: Set<CallKind>?, limit: Int): List<CallEntry>
}

/** recent_calls. Needs READ_CALL_LOG, asked for on first use. */
class CallLogTool(private val source: CallLogSource, private val now: () -> ZonedDateTime = ZonedDateTime::now) {
    fun recent(type: String?, limit: Int?, days: Int?): PhoneReply {
        val wanted = type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: ALL
        if (wanted != ALL && wanted !in FILTERS) return PhoneReply.Failed("type must be missed, incoming, outgoing or all ($type)")
        val max = limit ?: DEFAULT_LIMIT
        if (max !in 1..MAX_LIMIT) return PhoneReply.Failed("limit must be 1-$MAX_LIMIT")
        val back = days ?: DEFAULT_DAYS
        if (back !in 1..MAX_DAYS) return PhoneReply.Failed("days must be 1-$MAX_DAYS")
        if (!source.canRead()) return PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)
        val clock = now()
        val since = clock.minusDays(back.toLong()).toInstant().toEpochMilli()
        val calls = try {
            source.recent(since, FILTERS[wanted], max)
        } catch (e: SecurityException) {
            // Revoked between the check and the read.
            return PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)
        } catch (e: RuntimeException) {
            return PhoneReply.Failed("통화 기록을 읽지 못했어요")
        }
        if (calls.isEmpty()) return PhoneReply.Ok("결과 없음: 최근 ${back}일 동안 ${EMPTY.getValue(wanted)} 없어요.")
        return PhoneReply.Ok(
            buildJsonArray {
                for (c in calls.take(max)) {
                    add(
                        buildJsonObject {
                            c.name?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
                            put("number", shownNumber(c.number))
                            put("type", c.kind.wire)
                            put("at", AT.format(Instant.ofEpochMilli(c.at).atZone(clock.zone)))
                            put("duration_sec", c.durationSec.coerceAtLeast(0))
                        },
                    )
                }
            }.toString(),
        )
    }

    companion object {
        val LABEL get() = tr("Call history", "통화 기록")
        val PERMISSIONS = listOf("android.permission.READ_CALL_LOG")
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 90

        /** What the model reads in place of a number the caller hid. */
        const val HIDDEN = "발신번호 표시제한"
        private const val ALL = "all"
        private const val MAX_NAME = 60
        private const val MAX_NUMBER = 32

        /**
         * The type argument's filters ("all" has none). "incoming" is every call that came in, answered or not,
         * so "who just called" finds a missed call too; each entry still says which it was.
         */
        private val FILTERS: Map<String, Set<CallKind>> = mapOf(
            "missed" to setOf(CallKind.MISSED),
            "incoming" to setOf(CallKind.INCOMING, CallKind.MISSED, CallKind.REJECTED, CallKind.BLOCKED, CallKind.VOICEMAIL),
            "outgoing" to setOf(CallKind.OUTGOING),
        )
        private val EMPTY = mapOf(ALL to "통화 기록이", "missed" to "부재중 전화가", "incoming" to "걸려 온 전화가", "outgoing" to "건 전화가")
        private val AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        /**
         * [number] as the model reads it. Only dialling characters are kept: a caller can put a SIP address there,
         * and this output is not treated as third-party text.
         */
        fun shownNumber(number: String): String =
            number.filter { it.isDigit() || it in "+*#-() " }.trim().take(MAX_NUMBER).takeIf { n -> n.any { it.isDigit() } } ?: HIDDEN
    }
}
