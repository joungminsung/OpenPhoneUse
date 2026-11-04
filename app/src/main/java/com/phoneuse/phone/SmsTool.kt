package com.phoneuse.phone

import com.phoneuse.i18n.tr
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime

/** One text message; [address] is the other party's number (sender when [incoming], else recipient). */
data class SmsMessage(val address: String, val body: String, val at: Long, val incoming: Boolean)

/** The phone's texts; Android's is [AndroidSmsSource]. */
interface SmsSource {
    fun canRead(): Boolean

    fun canSend(): Boolean

    /** Messages since [since] (epoch ms) whose body or address contains [query], newest first, at most [limit]. */
    suspend fun query(since: Long, query: String?, limit: Int): List<SmsMessage>

    suspend fun send(to: String, body: String)
}

/**
 * sms_search and send_sms. Reading needs READ_SMS and sending SEND_SMS, asked for on first use. Every send
 * shows the SEND confirmation with the number and the text, whoever asked for it.
 */
class SmsTool(private val source: SmsSource, private val now: () -> ZonedDateTime = ZonedDateTime::now) {
    suspend fun search(query: String?, days: Int): MessagingReply {
        if (!source.canRead()) return READ_NEEDED
        val d = days.coerceIn(1, MAX_DAYS)
        val clock = now()
        val since = clock.minusDays(d.toLong()).toInstant().toEpochMilli()
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val hits = try {
            source.query(since, q, MAX_RESULTS)
        } catch (e: SecurityException) {
            return READ_NEEDED
        }
        if (hits.isEmpty()) return MessagingReply.Ok(if (q == null) "최근 ${d}일 동안 문자가 없어요." else "최근 ${d}일 문자 중 '$q'가 들어간 것이 없어요.")
        return MessagingReply.Ok(
            buildJsonArray {
                for (m in hits) {
                    add(
                        buildJsonObject {
                            put(if (m.incoming) "from" else "to", m.address)
                            put("at", NotificationTool.clockText(m.at, clock))
                            put("text", SmsParsers.maskCodes(m.body).take(MAX_BODY))
                            SmsParsers.payment(m.body)?.let { p ->
                                put("payment", buildJsonObject {
                                    put("merchant", p.merchant)
                                    put("amount", p.amount)
                                    p.at?.let { put("at", it) }
                                })
                            }
                            SmsParsers.parcel(m.body)?.let { p ->
                                put("parcel", buildJsonObject {
                                    put("carrier", p.carrier)
                                    p.tracking?.let { put("tracking", it) }
                                    put("status", p.status)
                                })
                            }
                        },
                    )
                }
            }.toString(),
        )
    }

    /** [confirm] shows the SEND card with its summary; nothing is sent unless it returns true. */
    suspend fun send(to: String, body: String, confirm: suspend (String) -> Boolean): MessagingReply {
        val number = normalizeNumber(to) ?: return MessagingReply.Failed("to는 전화번호여야 해요 ($to). 이름이면 연락처에서 번호를 먼저 찾으세요.")
        val text = body.trim()
        if (text.isEmpty()) return MessagingReply.Failed("body가 비었어요")
        if (text.length > MAX_SEND) return MessagingReply.Failed("문자가 너무 길어요 (최대 ${MAX_SEND}자)")
        if (!source.canSend()) return SEND_NEEDED
        // The whole text and the number actually sent: the card is the only check on what goes out.
        if (!confirm(confirmSummary(number, text))) return MessagingReply.Cancelled
        return try {
            source.send(number, text)
            MessagingReply.Ok("OK: ${number}에 문자를 보냈어요.")
        } catch (e: SecurityException) {
            SEND_NEEDED
        } catch (e: java.io.IOException) {
            MessagingReply.Failed("문자를 보내지 못했어요 (${e.message ?: "전송 실패"})")
        }
    }

    companion object {
        val READ_NEEDED get() = MessagingReply.PermissionNeeded(tr("Read texts", "문자 읽기"), listOf("android.permission.READ_SMS"))
        val SEND_NEEDED get() = MessagingReply.PermissionNeeded(tr("Send texts", "문자 보내기"), listOf("android.permission.SEND_SMS"))
        const val MAX_DAYS = 365
        private const val MAX_RESULTS = 30
        private const val MAX_BODY = 300
        private const val MAX_SEND = 1000
        /**
         * Starts the SEND summary of a text; ConfirmCopy shows the number and the whole body from it. It begins with
         * a private-use marker, so a summary that merely reads "문자 보내기 → …" is not drawn as an SMS card.
         */
        val CONFIRM_PREFIX get() = "" + tr("Send text → ", "문자 보내기 → ")

        /** "문자 보내기 → 01012345678", then the whole body on the following lines. */
        fun confirmSummary(number: String, body: String) = "$CONFIRM_PREFIX$number\n$body"

        private val NUMBER = Regex("""\+?[\d\s()-]+""")

        /** Digits (with a leading +) of a phone number written with spaces, dashes or brackets; null otherwise. */
        fun normalizeNumber(s: String): String? {
            val t = s.trim()
            if (!NUMBER.matches(t)) return null
            val digits = t.filter { it.isDigit() }
            if (digits.length !in 3..15) return null
            return if (t.startsWith("+")) "+$digits" else digits
        }
    }
}

/**
 * Long texts (LMS) and MMS live in the MMS provider, not the SMS table: their text parts are joined and they are
 * merged with the SMS rows by date. Pure, so the provider's quirks (dates in seconds, several parts) are tested.
 */
object MmsMerge {
    /** MMS message boxes (Telephony.BaseMmsColumns.MESSAGE_BOX). */
    const val BOX_INBOX = 1
    const val BOX_SENT = 2

    /** One MMS row as a [SmsMessage]; [dateSec] is in seconds. Null without text or outside inbox and sent. */
    fun message(dateSec: Long, box: Int, address: String?, textParts: List<String?>): SmsMessage? {
        if (box != BOX_INBOX && box != BOX_SENT) return null
        val body = textParts.mapNotNull { it?.trim()?.takeIf { t -> t.isNotEmpty() } }.joinToString("\n")
        if (body.isEmpty()) return null
        return SmsMessage(address?.trim().orEmpty(), body, dateSec * 1000, incoming = box == BOX_INBOX)
    }

    /** Whether [m] has [query] in its body or address (ignoring case); a null query matches all. */
    fun matches(m: SmsMessage, query: String?): Boolean =
        query == null || m.body.contains(query, ignoreCase = true) || m.address.contains(query, ignoreCase = true)

    /** SMS and MMS rows since [since] that match [query], newest first, at most [limit]. */
    fun merge(sms: List<SmsMessage>, mms: List<SmsMessage>, since: Long, query: String?, limit: Int): List<SmsMessage> =
        (sms + mms).filter { it.at >= since && matches(it, query) }.sortedByDescending { it.at }.take(limit)
}
