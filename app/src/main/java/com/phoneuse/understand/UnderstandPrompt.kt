package com.phoneuse.understand

import com.phoneuse.model.InputItem
import com.phoneuse.phone.StoredNotification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A stored payment or parcel the model may tie a new notification to. */
data class RecentEvent(
    val id: Long,
    val kind: NoticeKind,
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val instrument: String?,
    val at: Long,
    val tracking: String?,
    val stage: ParcelStage?,
)

/** What the light model is told about a batch of notifications, and how its JSON answer is read. */
object UnderstandPrompt {
    val INSTRUCTIONS: String = """
        You sort phone notifications for the phone's owner. For every notification in the batch, decide what it is
        and answer with JSON only, no prose:
        {"items":[{"i":<index>,"kind":"…","amount":"…","currency":"…","merchant":"…","instrument":"…",
          "same_as":{"event":<id>}|{"item":<index>}|null,"cancels":{"event":<id>}|{"item":<index>}|null,
          "parcel":{"carrier":"…","tracking":"…","item":"…","stage":"…"}|null,
          "sender":"…","needs_reply":true|false,"summary":"…"}]}
        Give exactly one item per notification, using its index.

        kind, one of:
        - spend: the owner paid for something (card approval, pay service payment, bank withdrawal for a purchase).
        - refund: a payment was cancelled or refunded to the owner.
        - income: money came in from someone else (a transfer received, a deposit, salary).
        - own_transfer: money moved between the owner's own accounts.
        - bill_payment: a withdrawal that pays an earlier spend, such as a card bill or loan repayment; it is not new spending.
        - reward: points, cashback, interest, step-counter or quiz rewards, coupons credited. Never spending.
        - parcel: a shipment to or from the owner (courier, shopping app delivery status).
        - food_delivery: a meal or quick-commerce order (Baemin, Coupang Eats, Yogiyo); never a parcel.
        - message: something a person wrote to the owner (a chat or text from someone they know, or a group chat).
        - service: a business or system notice that is not marketing: bookings, appointments, verification codes,
          account notices, KakaoTalk business messages (알림톡), app updates.
        - promo: advertising or marketing, with or without an "(광고)" mark: sales, coupons offered, "buy now".
        - other: anything else.
        Read the meaning, not single words: "결제 수단을 등록하면 혜택" is promo, "출금 가능 금액이 변경" is service,
        a friend writing "결제 완료됐어 12,000원 나왔어" is a message, "도착" for a taxi or a document is service.

        amount and currency: only for money kinds. amount is the number exactly as the notification shows it ("4,500",
        "12.50", "10만"), currency an ISO code (KRW for 원 or ₩). Never guess an amount that is not in the text.
        merchant: where the money went or came from as a person would say it (the shop, not the card company or pay
        service: "스타벅스", not "토스페이"). instrument: how it was paid ("삼성카드", "토스페이", "KB 계좌").

        same_as: one payment often arrives as several notifications (card text, card app, pay app, bank withdrawal).
        When this notification reports the same payment or parcel as a recent event or as another item of this batch
        (same amount and currency within minutes, or the same tracking number), point to it; otherwise null.
        cancels: for a refund, the spend it undoes: a recent event, or another item of this batch (an approval and its
        cancellation can arrive together); otherwise null.

        parcel.stage, one of: received (order or pickup registered), in_transit (moving between hubs), action_needed
        (the owner must act, such as customs or a failed delivery), out_for_delivery (on the way today), pickup_ready
        (waiting at a store or locker to be picked up), delivered (handed over or left at the door), returning (a return
        or pickup going back). "배송 완료 예정" is out_for_delivery, not delivered. Reminders to confirm a purchase or to
        write a review are promo, not parcels. tracking: the tracking number, if shown.

        sender, one of: person (one person writing), group (a group chat), business (a company, shop, bank, courier,
        KakaoTalk channel or business message), system (the phone or an app about itself).
        needs_reply: true only when a person (sender person or group) asked or said something the owner should
        answer. Acknowledgements ("ㅇㅋ", "ㅋㅋ", an emoji), "sent a photo" notices, likes and follows are false.

        summary: one short line in the language given, saying what happened ("스타벅스에서 4,500원 결제",
        "Your parcel is out for delivery").
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun input(batch: List<StoredNotification>, recent: List<RecentEvent>, zone: ZoneId, appLanguage: String): List<InputItem> {
        fun at(ms: Long) = clock.format(Instant.ofEpochMilli(ms).atZone(zone))
        val text = buildString {
            appendLine("Language for summary: $appLanguage")
            appendLine()
            appendLine("Recent events (for same_as and cancels):")
            if (recent.isEmpty()) appendLine("none")
            for (e in recent) {
                append("event ${e.id}: ${e.kind.wire}")
                e.amountMinor?.let { a -> append(", ${BigDecimal.valueOf(a, Money.minorDigits(e.currency ?: "KRW")).toPlainString()} ${e.currency}") }
                e.merchant?.let { append(", merchant $it") }
                e.instrument?.let { append(", via $it") }
                e.tracking?.let { append(", tracking $it") }
                e.stage?.let { append(", stage ${it.wire}") }
                appendLine(", at ${at(e.at)}")
            }
            appendLine()
            appendLine("Notifications:")
            batch.forEachIndexed { i, n ->
                appendLine("[$i] app ${n.appLabel} (${n.packageName})${if (n.group) ", group conversation" else ""}, at ${at(n.postedAt)}")
                appendLine("title: ${n.title}")
                appendLine("text: ${n.text}")
            }
        }
        return listOf(InputItem.UserText(text))
    }

    /**
     * The judgments by batch index, or null when the answer holds no `items` list. Items with an index outside the
     * batch or an unknown kind, sender or stage are left out (their notifications count as failed).
     */
    fun parse(text: String?, batchSize: Int): Map<Int, NoticeJudgment>? {
        val raw = text?.trim() ?: return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = try {
            json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val items = root["items"] as? JsonArray ?: return null
        val out = LinkedHashMap<Int, NoticeJudgment>()
        for (el in items) {
            val o = el as? JsonObject ?: continue
            val j = item(o, batchSize) ?: continue
            out.putIfAbsent(j.index, j)
        }
        return out
    }

    private fun item(o: JsonObject, batchSize: Int): NoticeJudgment? {
        val i = o.prim("i")?.intOrNull ?: return null
        if (i !in 0 until batchSize) return null
        val kind = NoticeKind.of(o.str("kind")) ?: return null
        val sender = Sender.of(o.str("sender")) ?: return null
        val parcel = when (val p = o["parcel"]) {
            is JsonObject -> {
                val stage = ParcelStage.of(p.str("stage")) ?: return null
                ParcelInfo(p.str("carrier"), p.str("tracking")?.let(::tracking), p.str("item"), stage)
            }
            else -> null
        }
        val currency = o.str("currency")?.uppercase()
        val amount = if (currency == null) null else o.str("amount")?.let { minor(it, currency) }
        val sameAs = when (val s = o["same_as"]) {
            is JsonObject -> s.prim("event")?.longOrNull?.let { Ref.Event(it) } ?: s.prim("item")?.intOrNull?.let { Ref.Batch(it) }
            else -> null
        }
        return NoticeJudgment(
            index = i,
            kind = kind,
            amountMinor = amount,
            currency = currency.takeIf { amount != null },
            merchant = o.str("merchant"),
            instrument = o.str("instrument"),
            sameAs = sameAs,
            cancels = when (val c = o["cancels"]) {
                is JsonObject -> c.prim("event")?.longOrNull?.let { Ref.Event(it) } ?: c.prim("item")?.intOrNull?.let { Ref.Batch(it) }
                is JsonPrimitive -> c.longOrNull?.let { Ref.Event(it) }
                else -> null
            },
            parcel = parcel,
            sender = sender,
            needsReply = o.prim("needs_reply")?.booleanOrNull ?: false,
            summary = o.str("summary").orEmpty(),
        )
    }

    /** "4,500" → 4500 won; "12.50" → 1250 cents; "10만" → 100000. Null when it is no plain amount. */
    private fun minor(amount: String, currency: String): Long? {
        var s = amount.replace(",", "").replace(" ", "").replace("원", "")
        var factor = BigDecimal.ONE
        when {
            s.endsWith("만") -> { factor = BigDecimal(10_000); s = s.dropLast(1) }
            s.endsWith("천") -> { factor = BigDecimal(1_000); s = s.dropLast(1) }
        }
        val value = s.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0) return null
        return runCatching { value.multiply(factor).movePointRight(Money.minorDigits(currency)).longValueExact() }.getOrNull()
    }

    private fun tracking(s: String): String? = s.filter { it.isLetterOrDigit() }.uppercase().takeIf { it.isNotEmpty() }

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.str(key: String): String? = prim(key)?.content?.trim()?.takeIf { it.isNotEmpty() }
}
