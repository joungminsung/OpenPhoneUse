package com.phoneuse.phone

/** A card approval or withdrawal read from a text: where, how much (won), and "MM/dd HH:mm" when the text says. */
data class Payment(val merchant: String, val amount: Long, val at: String?)

/** A courier text: who carries it, the tracking number (digits only) when given, and one of [SmsParsers.PARCEL_STAGES]. */
data class Parcel(val carrier: String, val tracking: String?, val status: String)

/**
 * Reads payments and parcels out of Korean card-company and courier texts (SMS or notification text). Pure: no
 * Android, so the representative phrasings are fixed by tests. Advertisements ("(광고)") are never either.
 */
object SmsParsers {
    /** The stages of the board's parcel card, in order. */
    val PARCEL_STAGES = listOf("접수", "이동 중", "배송 출발", "도착")

    private val AD = Regex("""[(\[]\s*광고\s*[)\]]""")

    fun isAd(text: String): Boolean = AD.containsMatchIn(text)

    // ---- payments ------------------------------------------------------------------------------

    private val AMOUNT = Regex("""(\d{1,3}(?:,\d{3})+|\d+)\s*원""")
    private val WITHDRAWAL = Regex("""출금\s*(\d{1,3}(?:,\d{3})+|\d+)""")
    private val DATE_TIME = Regex("""(\d{1,2})/(\d{1,2})\s*(\d{1,2}):(\d{2})""")
    private val RUNNING_TOTAL = Regex("""(누적|잔액|잔여|한도)""")
    private val INSTITUTION = Regex("카드|은행|뱅크|페이|[Pp]ay|KB|NH|농협|신한|삼성|현대|롯데|하나|우리|BC|씨티|토스|IBK|기업|SC|Web발신")
    /** An account number ("352-****-1234-53"), never a merchant. */
    private val ACCOUNT = Regex("""\d{2,}[-*][\d*-]{4,}""")
    /** A bank's own name line ("KB국민은행") is the sender, not where the money went. */
    private val BANK = Regex("은행|뱅크")
    private val NOISE_LINE = Regex("""(승인|일시불|할부|누적|잔액|\*|원$|^\d[\d\s/:,-]*$|Web발신)""")

    /** A card approval (not a cancellation), a completed payment, or a withdrawal; null for anything else. */
    fun payment(text: String): Payment? {
        if (isAd(text)) return null
        val t = clean(text)
        val approval = "승인" in t && "취소" !in t && "거절" !in t
        val paid = "결제" in t && ("완료" in t || "되었" in t || "됐" in t) && "취소" !in t
        val withdrawal = "출금" in t
        if (!approval && !paid && !withdrawal) return null
        // A friend's "결제 완료했어 3만원 보내줘" names no card company, bank or pay service.
        if (!INSTITUTION.containsMatchIn(text)) return null
        val amount = amount(t) ?: return null
        val time = DATE_TIME.find(t)
        val at = time?.let {
            val (mo, d, h, mi) = it.destructured
            "%02d/%02d %02d:%s".format(mo.toInt(), d.toInt(), h.toInt(), mi)
        }
        val merchant = merchant(t, time) ?: if (withdrawal && !approval) "출금" else return null
        return Payment(merchant, amount, at)
    }

    /** The first "N원" that is not a running total or balance; for a withdrawal, also "출금 N". */
    private fun amount(t: String): Long? {
        for (m in AMOUNT.findAll(t)) {
            val before = t.substring(maxOf(0, m.range.first - 4), m.range.first)
            if (RUNNING_TOTAL.containsMatchIn(before)) continue
            return m.groupValues[1].replace(",", "").toLongOrNull()?.takeIf { it > 0 }
        }
        return WITHDRAWAL.find(t)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** What follows the date and time on its line, else the next line; failing that, the one line that is no card detail. */
    private fun merchant(t: String, time: MatchResult?): String? {
        if (time != null) {
            val rest = t.substring(time.range.last + 1)
            val sameLine = rest.substringBefore('\n').let { cutTotals(it) }
            name(sameLine)?.let { return it }
            rest.substringAfter('\n', "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { next ->
                if (!RUNNING_TOTAL.containsMatchIn(next)) name(cutTotals(next))?.let { return it }
            }
        }
        return t.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !NOISE_LINE.containsMatchIn(it) && !BANK.containsMatchIn(it) && !it.endsWith("님") }
            .firstNotNullOfOrNull { name(it) }
    }

    private fun cutTotals(s: String): String = RUNNING_TOTAL.find(s)?.let { s.substring(0, it.range.first) } ?: s

    private fun name(s: String): String? = s.trim().takeIf { it.isNotEmpty() && !ACCOUNT.containsMatchIn(it) && it.any { c -> !c.isDigit() && !c.isWhitespace() } }?.take(MAX_NAME)

    // ---- parcels -------------------------------------------------------------------------------

    private val CARRIERS: List<Pair<Regex, String>> = listOf(
        Regex("CJ\\s*대한통운|대한통운|CJ\\s*택배") to "CJ대한통운",
        Regex("한진") to "한진택배",
        Regex("롯데\\s*(택배|글로벌)") to "롯데택배",
        Regex("우체국") to "우체국택배",
        Regex("로젠") to "로젠택배",
        Regex("경동택배") to "경동택배",
        Regex("CU\\s*(편의점)?택배|CU\\s*끼리") to "CU 편의점택배",
        Regex("GS\\s*(25|편의점)?\\s*(반값)?택배|GS\\s*Postbox") to "GS 편의점택배",
        Regex("쿠팡|로켓배송") to "쿠팡",
    )
    private val TRACKING = Regex("""(?:운송장|송장|등기)(?:\s*번호)?[^\d\n]{0,6}(\d[\d-]{8,18}\d)""")
    private val DELIVERED = Regex("배송\\s*완료|배달\\s*완료|전달\\s*완료|도착했|도착하였|배달했|배달하였|배송했|배송하였|문\\s*앞에\\s*(두|놓)")
    private val OUT = Regex("배송\\s*출발|배달\\s*출발|배송될\\s*예정|배달될\\s*예정|배송\\s*예정|배달\\s*예정|배달할\\s*예정|배송할\\s*예정|오늘\\s*도착")
    private val MOVING = Regex("이동\\s*중|간선|터미널|발송|출고|상차|하차")
    private val RECEIVED = Regex("접수|집화|집하")
    private val FINAL = Regex("배송\\s*완료|배달\\s*완료|전달\\s*완료|문\\s*앞에\\s*(두|놓)")
    private val HUB_ARRIVAL = Regex("(터미널|허브|센터|캠프|대리점|영업소)\\S{0,2}\\s*도착")
    private val BRACKETED_CARRIER = Regex("""[\[(][^\])\n]{0,12}(택배|통운|우체국|쿠팡|로젠|한진|롯데|경동|CU|GS)[^\])\n]{0,12}[\])]""")

    // ---- verification codes ----------------------------------------------------------------------

    private val CODE_CONTEXT = Regex("인증\\s*번호|인증\\s*코드|확인\\s*번호|본인\\s*확인|보안\\s*코드|OTP|[Vv]erification|[Cc]ode|코드")
    private val CODE = Regex("""(?<![\d,])\d{4,8}(?![\d,원])""")

    /** True for a one-time code text (인증번호, OTP...). */
    fun isVerification(text: String): Boolean = CODE_CONTEXT.containsMatchIn(text)

    /** In a one-time code text ([context] decides, e.g. title and text together), every 4–8 digit code becomes "●●●●●●"; other texts come back unchanged. */
    fun maskCodes(text: String, context: String = text): String =
        if (!isVerification(context)) text else CODE.replace(text) { "●".repeat(it.value.length) }

    /** A courier's status text; null without a known carrier, or without a tracking number or status. */
    fun parcel(text: String): Parcel? {
        if (isAd(text)) return null
        val t = clean(text)
        val carrier = CARRIERS.firstOrNull { it.first.containsMatchIn(t) }?.second ?: return null
        val tracking = TRACKING.find(t)?.groupValues?.get(1)?.replace("-", "")?.takeIf { it.length in 10..14 }
        // A courier writes formally: a tracking number, its name in brackets, or "고객님". A friend's "한진이가 발송했대" is no parcel.
        if (tracking == null && !BRACKETED_CARRIER.containsMatchIn(t) && "고객" !in t) return null
        val status = when {
            FINAL.containsMatchIn(t) -> "도착"
            // "지역 터미널에 도착했습니다" is a hub, not the door.
            HUB_ARRIVAL.containsMatchIn(t) -> "이동 중"
            DELIVERED.containsMatchIn(t) -> "도착"
            OUT.containsMatchIn(t) -> "배송 출발"
            MOVING.containsMatchIn(t) -> "이동 중"
            RECEIVED.containsMatchIn(t) -> "접수"
            else -> null
        }
        if (status == null && tracking == null) return null
        // A card or bank text naming a shop ("쿠팡") is a payment, not a parcel.
        if (status == null && ("승인" in t || "결제" in t)) return null
        return Parcel(carrier, tracking, status ?: "이동 중")
    }

    private fun clean(text: String): String = text.replace("[Web발신]", "").replace("\r", "").trim()

    private const val MAX_NAME = 40
}
