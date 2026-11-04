package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Representative Korean card-approval and courier texts, fixed so a parser change cannot silently lose them. */
class SmsParsersTest {
    @Test fun shinhanApprovalOnOneLine() {
        val p = SmsParsers.payment("[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)11/02 12:34 스타벅스강남 누적1,234,567원")
        assertEquals(Payment("스타벅스강남", 12_500, "11/02 12:34"), p)
    }

    @Test fun kbApprovalOverLines() {
        val p = SmsParsers.payment("[Web발신]\nKB국민카드1*2*승인\n홍*동님\n45,000원 일시불\n11/02 18:21\n이마트 성수점\n누적1,234,000원")
        assertEquals(Payment("이마트 성수점", 45_000, "11/02 18:21"), p)
    }

    @Test fun hyundaiApprovalOverLines() {
        val p = SmsParsers.payment("[Web발신]\n[현대카드] 승인\n홍*동\n9,900원 일시불\n11/2 08:10\n넷플릭스\n누적 52,300원")
        assertEquals(Payment("넷플릭스", 9_900, "11/02 08:10"), p)
    }

    @Test fun samsungApprovalMerchantAfterTime() {
        val p = SmsParsers.payment("삼성1234승인 홍*동\n32,000원 일시불\n11/02 12:34 쿠팡(주)\n누적1,000,000원")
        assertEquals(Payment("쿠팡(주)", 32_000, "11/02 12:34"), p)
    }

    @Test fun cancelledApprovalIsNotAPayment() {
        assertNull(SmsParsers.payment("[Web발신]\n신한카드(1234)승인취소 홍*동 12,500원 11/02 12:40 스타벅스강남"))
    }

    @Test fun cjParcel() {
        val p = SmsParsers.parcel("[CJ대한통운] 고객님의 상품이 배송될 예정입니다. 운송장번호 : 123456789012 배송예정시간 : 14~16시")
        assertEquals(Parcel("CJ대한통운", "123456789012", "배송 출발"), p)
    }

    @Test fun postOfficeParcel() {
        val p = SmsParsers.parcel("[우체국택배] 홍*동님 소포(등기번호 6012345678901)를 오늘 13~15시에 배달할 예정입니다.")
        assertEquals(Parcel("우체국택배", "6012345678901", "배송 출발"), p)
    }

    @Test fun hanjinDelivered() {
        val p = SmsParsers.parcel("[한진택배] 고객님의 상품(운송장 4123-4567-8901)이 배송완료되었습니다.")
        assertEquals(Parcel("한진택배", "412345678901", "도착"), p)
    }

    @Test fun adsAreExcluded() {
        assertNull(SmsParsers.payment("(광고)[현대카드] 이번 주 결제하면 5% 할인! 승인 시 12,000원 적립 무료거부 0801234567"))
        assertNull(SmsParsers.parcel("(광고)[CJ대한통운] 택배 할인 이벤트! 지금 접수하면 운송장 123456789012 배송비 무료"))
        assertNull(SmsParsers.payment("[Web발신]\n(광고) 쿠팡 결제 완료 시 30,000원 할인"))
    }

    @Test fun parcelAndPaymentDoNotCrossMatch() {
        assertNull(SmsParsers.parcel("[Web발신]\nKB국민카드1*2*승인\n홍*동님\n45,000원 일시불\n11/02 18:21\n이마트 성수점"))
        assertNull(SmsParsers.payment("[CJ대한통운] 고객님의 상품이 배송될 예정입니다. 운송장번호 : 123456789012"))
        assertNull(SmsParsers.payment("저녁 먹고 오니? 반찬 해놨어"))
        assertNull(SmsParsers.parcel("택배 왔어? 집 앞에 있던데"))
    }

    @Test fun moreCarriers() {
        assertEquals(
            Parcel("쿠팡", null, "도착"),
            SmsParsers.parcel("[쿠팡] 고객님, 주문하신 상품이 배송완료되었습니다. 문 앞에 두었습니다."),
        )
        assertEquals(
            Parcel("롯데택배", "234567890123", "배송 출발"),
            SmsParsers.parcel("[롯데택배] 고객님의 상품이 오늘 배송될 예정입니다. 송장번호 234567890123"),
        )
        assertEquals(
            Parcel("로젠택배", "34567890123", "접수"),
            SmsParsers.parcel("[로젠택배] 고객님의 상품이 접수되었습니다. 운송장번호: 34567890123"),
        )
    }

    @Test fun hubArrivalIsStillMoving() {
        val p = SmsParsers.parcel("[CJ대한통운] 고객님의 상품이 대전HUB 터미널에 도착했습니다. 운송장번호 123456789012")
        assertEquals("이동 중", p?.status)
    }

    @Test fun friendsAreNeitherPaymentsNorParcels() {
        assertNull(SmsParsers.payment("결제 완료했어 30,000원 보내줘"))
        assertNull(SmsParsers.parcel("한진이가 발송했대"))
    }

    @Test fun withdrawalWithAnAccountNumberIsAWithdrawal() {
        val p = SmsParsers.payment("[Web발신]\nKB국민은행\n352-****-1234-53\n출금 50,000원\n잔액 1,234,000원")
        assertEquals("출금", p?.merchant)
        assertEquals(50_000L, p?.amount)
    }

    @Test fun maskCodesOnlyInCodeTexts() {
        assertEquals("[Web발신] 인증번호 [●●●●●●]를 입력해주세요", SmsParsers.maskCodes("[Web발신] 인증번호 [482913]를 입력해주세요"))
        assertEquals("Your verification code is ●●●●", SmsParsers.maskCodes("Your verification code is 7712"))
        // The title can be the context: "인증" title, bare code text.
        assertEquals("●●●●●● 입력", SmsParsers.maskCodes("482913 입력", "본인확인\n482913 입력"))
        // Amounts and ordinary numbers stay.
        assertEquals("12,500원 결제 2026년", SmsParsers.maskCodes("12,500원 결제 2026년"))
        assertEquals("주문번호 1234567", SmsParsers.maskCodes("주문번호 1234567"))
    }
}
