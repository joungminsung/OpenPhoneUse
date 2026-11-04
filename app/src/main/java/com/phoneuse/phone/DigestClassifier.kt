package com.phoneuse.phone

/** The groups of the board "Digest", in its order; [tone] is the digest card's group tone. */
enum class DigestBucket(val label: String, val tone: String) {
    REPLY("답장 필요", "urgent"),
    MONEY("돈 나간 것", "money"),
    ARRIVAL("오늘 도착", "info"),
    OTHER("나머지", "muted"),
}

/**
 * Sorts a notification into a [DigestBucket] by fixed rules; the model only writes the summary sentences.
 * Advertisements are always the rest. A messenger's 1:1 message needs a reply unless it is a card or courier
 * text (SMS apps carry those) or an automated one ("[Web발신]", 인증번호). Elsewhere, finance apps and payment
 * words are money, and delivery words are arrivals.
 */
object DigestClassifier {
    /** Chat and SMS apps whose notifications are messages from people. */
    val MESSENGERS: Set<String> = setOf(
        "com.kakao.talk",
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.messaging",
        "jp.naver.line.android",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.whatsapp",
        "com.instagram.android",
        "com.discord",
        "com.tencent.mm",
        "com.nhn.android.band",
    )

    /** Card, bank and pay apps: what they notify about is money. */
    val FINANCE: Set<String> = setOf(
        "com.kbcard.cxh.appcard", "com.kbstar.kbbank", "com.kbstar.liivbank",
        "com.shcard.smartpay", "com.shinhan.sbanking", "com.shinhancard.smartshinhan",
        "kr.co.samsungcard.mpocket", "com.samsung.android.spay",
        "com.hyundaicard.appcard", "com.lcacApp", "com.lottecard.lcap",
        "com.wooricard.smartapp", "com.wooribank.smart.npib",
        "com.hanaskcard.paycla", "com.hanabank.ebk.channel.android.hananbank",
        "nh.smart.nhallonepay", "com.nh.cashcardapp", "nh.smart.banking",
        "com.ibk.android.ionebank", "com.bccard.mobilecard",
        "com.kakaobank.channel", "com.kakaopay.app", "viva.republica.toss",
        "com.nhnent.payapp", "com.naverfin.payapp", "com.kbankwith.smartbank",
    )

    private val MONEY_WORDS = Regex("결제|출금")

    /** "승인" is money only with an amount ("친구 요청이 승인되었어요" is not). */
    private val APPROVAL_WITH_AMOUNT = Regex("""승인[\s\S]*\d\s*원|\d\s*원[\s\S]*승인""")
    private val PARCEL_WORDS = Regex("배송|도착|송장")
    private val AUTOMATED = Regex("Web발신|인증번호|인증 번호|본인확인")

    fun classify(n: StoredNotification): DigestBucket {
        val text = "${n.title}\n${n.text}"
        if (SmsParsers.isAd(text)) return DigestBucket.OTHER
        if (n.packageName in MESSENGERS) {
            if (SmsParsers.payment(n.text) != null) return DigestBucket.MONEY
            if (SmsParsers.parcel(n.text) != null) return DigestBucket.ARRIVAL
            if (n.group || AUTOMATED.containsMatchIn(text) || SmsParsers.isVerification(text)) return DigestBucket.OTHER
            return DigestBucket.REPLY
        }
        if (n.packageName in FINANCE || MONEY_WORDS.containsMatchIn(text) || APPROVAL_WITH_AMOUNT.containsMatchIn(text)) {
            return DigestBucket.MONEY
        }
        if (PARCEL_WORDS.containsMatchIn(text)) return DigestBucket.ARRIVAL
        return DigestBucket.OTHER
    }

    /**
     * Non-empty buckets in board order; each keeps the given (newest-first) order. A message already gone from the
     * shade (removedAt) needs no reply and is left out; every other bucket keeps removed rows.
     */
    fun group(list: List<StoredNotification>): Map<DigestBucket, List<StoredNotification>> {
        val by = list.map { it to classify(it) }
            .filterNot { (n, b) -> b == DigestBucket.REPLY && n.removedAt != null }
            .groupBy({ it.second }, { it.first })
        return DigestBucket.entries.mapNotNull { b -> by[b]?.let { b to it } }.toMap(LinkedHashMap())
    }
}
