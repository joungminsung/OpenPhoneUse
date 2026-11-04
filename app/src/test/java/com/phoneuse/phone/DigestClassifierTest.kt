package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DigestClassifierTest {
    private fun n(pkg: String, title: String, text: String, group: Boolean = false) =
        StoredNotification("k", pkg, "앱", title, text, 0L, group)

    @Test fun oneToOneMessengerNeedsReply() {
        assertEquals(DigestBucket.REPLY, DigestClassifier.classify(n("com.kakao.talk", "엄마", "저녁 먹고 오니? 반찬 해놨어")))
        assertEquals(DigestBucket.REPLY, DigestClassifier.classify(n("com.samsung.android.messaging", "팀장님", "내일 회의 자료 공유 가능할까요?")))
    }

    @Test fun groupChatIsTheRest() {
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.kakao.talk", "대학 동기방", "민수: 다들 주말에 뭐해", group = true)))
    }

    @Test fun cardApprovalTextIsMoney() {
        val sms = n("com.samsung.android.messaging", "15447200", "[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)11/02 12:34 스타벅스강남")
        assertEquals(DigestBucket.MONEY, DigestClassifier.classify(sms))
        assertEquals(DigestBucket.MONEY, DigestClassifier.classify(n("com.kbcard.cxh.appcard", "KB Pay", "이번 달 이용 내역을 확인하세요")))
        assertEquals(DigestBucket.MONEY, DigestClassifier.classify(n("com.example.bank", "입출금", "급여통장에서 50,000원 출금")))
        assertEquals(DigestBucket.MONEY, DigestClassifier.classify(n("com.baemin", "배달의민족", "결제가 완료됐어요")))
    }

    @Test fun parcelTextArrives() {
        val sms = n("com.google.android.apps.messaging", "CJ대한통운", "[CJ대한통운] 고객님의 상품이 배송될 예정입니다. 운송장번호 : 123456789012")
        assertEquals(DigestBucket.ARRIVAL, DigestClassifier.classify(sms))
        assertEquals(DigestBucket.ARRIVAL, DigestClassifier.classify(n("com.coupang.mobile", "쿠팡", "주문하신 상품이 오늘 도착 예정이에요")))
        assertEquals(DigestBucket.ARRIVAL, DigestClassifier.classify(n("com.shop", "쇼핑", "송장번호가 등록됐어요")))
    }

    @Test fun adsAreTheRestEvenWithMoneyOrParcelWords() {
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.coupang.mobile", "쿠팡", "(광고) 오늘만 특가, 배송비 무료")))
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.kakao.talk", "(광고) 올리브영", "결제 시 30% 할인")))
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.samsung.android.messaging", "16880000", "(광고)[현대카드] 승인 시 적립")))
    }

    @Test fun automatedTextsAndOtherAppsAreTheRest() {
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.samsung.android.messaging", "15990000", "[Web발신]\n인증번호 [123456]를 입력해주세요")))
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.android.vending", "앱 업데이트", "3개 앱이 업데이트됐어요")))
    }

    @Test fun groupsComeInBoardOrder() {
        val list = listOf(
            n("com.android.vending", "앱 업데이트", "업데이트됨"),
            n("com.coupang.mobile", "쿠팡", "오늘 도착 예정"),
            n("com.kakao.talk", "엄마", "밥 먹었니"),
            n("com.baemin", "배민", "결제 완료"),
        )
        assertEquals(
            listOf(DigestBucket.REPLY, DigestBucket.MONEY, DigestBucket.ARRIVAL, DigestBucket.OTHER),
            DigestClassifier.group(list).keys.toList(),
        )
        assertEquals(listOf("답장 필요", "돈 나간 것", "오늘 도착", "나머지"), DigestBucket.entries.map { it.label })
    }

    @Test fun approvalWithoutAnAmountIsNotMoney() {
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.some.sns", "친구", "친구 요청이 승인되었어요")))
        assertEquals(DigestBucket.MONEY, DigestClassifier.classify(n("com.shop.app", "주문", "12,000원 결제가 승인되었어요")))
    }

    @Test fun verificationCodeFromAMessengerIsTheRest() {
        assertEquals(DigestBucket.OTHER, DigestClassifier.classify(n("com.kakao.talk", "카카오", "인증번호 ●●●●●●")))
    }

    @Test fun removedNotificationsLeaveOnlyTheReplyGroup() {
        val reply = n("com.kakao.talk", "엄마", "저녁 먹고 오니?").copy(key = "r", removedAt = 5)
        val pay = n("com.samsung.android.messaging", "15447200", "[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)11/02 12:34 스타벅스강남").copy(key = "p", removedAt = 5)
        val parcel = n("com.google.android.apps.messaging", "CJ대한통운", "[CJ대한통운] 고객님의 상품이 배송될 예정입니다. 운송장번호 : 123456789012").copy(key = "a", removedAt = 5)
        val other = n("com.android.vending", "앱 업데이트", "3개 앱이 업데이트됐어요").copy(key = "o", removedAt = 5)
        val groups = DigestClassifier.group(listOf(reply, pay, parcel, other))
        assertEquals(listOf(DigestBucket.MONEY, DigestBucket.ARRIVAL, DigestBucket.OTHER), groups.keys.toList())
        assertEquals(DigestBucket.REPLY, DigestClassifier.group(listOf(reply.copy(removedAt = null))).keys.single())
    }
}
