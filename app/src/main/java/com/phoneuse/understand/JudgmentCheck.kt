package com.phoneuse.understand

import com.phoneuse.phone.StoredNotification
import java.math.BigDecimal

/**
 * Checks a model judgment against the notification it is about. This is not understanding: the model decides what a
 * notification means, and this only refuses an amount the text never shows, so an invented number never becomes
 * spending.
 */
object JudgmentCheck {
    /** A number standing alone (not part of a longer one), with an optional Korean 만/천 multiplier after it. */
    private val NUMBER = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?(?![\d,]|\.\d)\s*(만|천)?""")

    /** Whether [text] shows [amountMinor] in [currency]: "4,500", "4500원", "10만 원", "1.2만", "$12.50", "USD 12.50". */
    fun amountAppears(amountMinor: Long, currency: String, text: String): Boolean {
        val digits = Money.minorDigits(currency)
        return NUMBER.findAll(text).any { m ->
            val whole = m.groupValues[1].replace(",", "")
            val value = BigDecimal(whole + m.groupValues[2])
            val factor = when (m.groupValues[3]) {
                "만" -> BigDecimal(10_000)
                "천" -> BigDecimal(1_000)
                else -> BigDecimal.ONE
            }
            val minor = runCatching { value.multiply(factor).movePointRight(digits).longValueExact() }.getOrNull()
            minor == amountMinor
        }
    }

    /**
     * [j] as it may be stored: a money kind whose amount is missing or not in the notification's title or text
     * becomes [NoticeKind.OTHER] with no amount, merchant or link, so it never counts as money.
     */
    fun clean(j: NoticeJudgment, n: StoredNotification): NoticeJudgment {
        if (!j.kind.isMoney) return j
        val amount = j.amountMinor
        val currency = j.currency
        if (amount != null && currency != null && amountAppears(amount, currency, "${n.title}\n${n.text}")) return j
        return j.copy(kind = NoticeKind.OTHER, amountMinor = null, currency = null, merchant = null, instrument = null, sameAs = null, cancels = null)
    }
}
