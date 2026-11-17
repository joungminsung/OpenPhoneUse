package com.phoneuse.understand

import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** What a notification is about, as the model judged it. [wire] is the value in the model's JSON and in chat.db. */
enum class NoticeKind(val wire: String) {
    SPEND("spend"),
    REFUND("refund"),
    INCOME("income"),
    OWN_TRANSFER("own_transfer"),
    BILL_PAYMENT("bill_payment"),
    REWARD("reward"),
    PARCEL("parcel"),
    FOOD_DELIVERY("food_delivery"),
    MESSAGE("message"),
    SERVICE("service"),
    PROMO("promo"),
    OTHER("other"),
    ;

    /** Money moved or was credited (only [SPEND] counts as spending). */
    val isMoney: Boolean get() = this in MONEY

    companion object {
        private val MONEY = setOf(SPEND, REFUND, INCOME, OWN_TRANSFER, BILL_PAYMENT, REWARD)

        fun of(wire: String?): NoticeKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Who a notification comes from. */
enum class Sender(val wire: String) {
    PERSON("person"),
    GROUP("group"),
    BUSINESS("business"),
    SYSTEM("system"),
    ;

    companion object {
        fun of(wire: String?): Sender? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where a parcel is. [rank] only grows as a parcel moves on: a later notice with a lower rank never moves it back. */
enum class ParcelStage(val wire: String, val rank: Int) {
    RECEIVED("received", 0),
    IN_TRANSIT("in_transit", 1),
    ACTION_NEEDED("action_needed", 1),
    OUT_FOR_DELIVERY("out_for_delivery", 2),
    PICKUP_READY("pickup_ready", 3),
    DELIVERED("delivered", 4),
    RETURNING("returning", 0),
    ;

    fun label(): String = when (this) {
        RECEIVED -> tr("Received", "접수")
        IN_TRANSIT -> tr("In transit", "이동 중")
        ACTION_NEEDED -> tr("Action needed", "확인 필요")
        OUT_FOR_DELIVERY -> tr("Out for delivery", "배송 출발")
        PICKUP_READY -> tr("Ready for pickup", "찾아가기")
        DELIVERED -> tr("Delivered", "도착")
        RETURNING -> tr("Returning", "반품 중")
    }

    companion object {
        fun of(wire: String?): ParcelStage? = entries.firstOrNull { it.wire == wire }
    }
}

/** A parcel notice: carrier, tracking number, what was ordered, and the stage it reports. */
data class ParcelInfo(val carrier: String?, val tracking: String?, val itemHint: String?, val stage: ParcelStage)

/** What a judgment says it belongs with: a stored event, or another notification of the same batch. */
sealed interface Ref {
    data class Event(val id: Long) : Ref
    data class Batch(val index: Int) : Ref
}

/**
 * The model's reading of notification [index] of a batch. [amountMinor] is in the currency's minor unit (won, cents).
 * [cancels] is the payment a refund undoes: a stored event, or another notification of the same batch.
 */
data class NoticeJudgment(
    val index: Int,
    val kind: NoticeKind,
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val instrument: String?,
    val sameAs: Ref?,
    val cancels: Ref?,
    val parcel: ParcelInfo?,
    val sender: Sender,
    val needsReply: Boolean,
    val summary: String,
)

/** Amounts in minor units, shown in the app language. */
object Money {
    fun minorDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2

    fun format(amountMinor: Long, currency: String): String {
        val digits = minorDigits(currency)
        val f = NumberFormat.getCurrencyInstance(if (Lang.korean) Locale.KOREA else Locale.US)
        runCatching { f.currency = Currency.getInstance(currency) }
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        return f.format(java.math.BigDecimal.valueOf(amountMinor, digits))
    }
}
