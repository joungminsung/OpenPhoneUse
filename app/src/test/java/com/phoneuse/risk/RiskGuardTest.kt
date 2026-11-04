package com.phoneuse.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskGuardTest {
    @Test fun money() {
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("결제하기", null))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("Pay now", null))
    }

    @Test fun delete() = assertEquals(RiskCategory.DELETE, RiskGuard.classify("삭제", null))

    @Test fun send() {
        assertEquals(RiskCategory.SEND, RiskGuard.classify("전송", null))
        assertEquals(RiskCategory.SEND, RiskGuard.classify("보내기", null))
    }

    @Test fun exportIsNotSend() {
        assertNull(RiskGuard.classify("내보내기", null))
        assertNull(RiskGuard.classify("Export", null))
    }

    @Test fun exportThenSendIsSend() =
        assertEquals(RiskCategory.SEND, RiskGuard.classify("내보내기 후 전송", null))

    @Test fun security() =
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("비밀번호 변경", null))

    @Test fun multiWordEnglishPhrase() =
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Sign in", null))

    @Test fun unrelatedAndNull() {
        assertNull(RiskGuard.classify("설정", null))
        assertNull(RiskGuard.classify(null, null))
    }

    @Test fun englishMatchesWordBoundariesOnly() {
        assertNull(RiskGuard.classify("Border color", null))
        assertNull(RiskGuard.classify("Recorder", null))
    }

    @Test fun packageInstallerIsSecurity() =
        assertEquals(
            RiskCategory.SECURITY,
            RiskGuard.classify("설치", "com.google.android.packageinstaller"),
        )

    @Test fun priorityMoneyOverDelete() =
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("구독 결제 삭제", null))

    @Test fun englishPrefixAndEmbeddedInKorean() {
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("삼성Pay", null))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("Payments", null))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("btn_pay", null))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Permissions", "com.google.android.permissioncontroller"))
        assertEquals(RiskCategory.DELETE, RiskGuard.classify("Delete all", null))
        assertEquals(RiskCategory.SEND, RiskGuard.classify("Send", null))
    }

    @Test fun phraseSeparatorVariants() {
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Login", null))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Sign-in", null))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("signin", null))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Log-in", null))
    }

    @Test fun packageInstallerOverridesMoneyLabel() =
        assertEquals(
            RiskCategory.SECURITY,
            RiskGuard.classify("결제", "com.google.android.packageinstaller"),
        )

    @Test fun prioritySecurityOverDelete() =
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("비밀번호 삭제", null))

    // ---- narrowed triggers: harmless buttons no longer ask ------------------------------------

    @Test fun shareAndReplyAreNotSend() {
        assertNull(RiskGuard.classify("공유", null))
        assertNull(RiskGuard.classify("Share", null))
        assertNull(RiskGuard.classify("답장", null))
        assertNull(RiskGuard.classify("Reply", null))
    }

    @Test fun sendWordsStillSend() {
        assertEquals(RiskCategory.SEND, RiskGuard.classify("게시", null))
        assertEquals(RiskCategory.SEND, RiskGuard.classify("Post", null))
        assertEquals(RiskCategory.SEND, RiskGuard.classify("답장 보내기", null))
    }

    @Test fun bareSubscribeIsNotMoney() {
        assertNull(RiskGuard.classify("구독", null))
        assertNull(RiskGuard.classify("Subscribe", null))
    }

    @Test fun bareChargingIsNotMoney() {
        assertNull(RiskGuard.classify("충전", null))
        assertNull(RiskGuard.classify("고속 충전", null))
    }

    @Test fun moneyWordsStillMoney() {
        for (label in listOf("구매", "주문", "송금", "이체", "충전하기", "정기결제", "구독 결제")) {
            assertEquals(label, RiskCategory.MONEY, RiskGuard.classify(label, null))
        }
        for (label in listOf("Buy", "Purchase", "Checkout", "Transfer", "Place order", "Orders")) {
            assertEquals(label, RiskCategory.MONEY, RiskGuard.classify(label, null))
        }
    }

    @Test fun orderNeedsWholeWord() {
        assertNull(RiskGuard.classify("Recorder", null))
        assertNull(RiskGuard.classify("Border", null))
        assertNull(RiskGuard.classify("Orderly list", null))
    }

    @Test fun clearingSearchFieldIsNotDelete() {
        assertNull(RiskGuard.classify("검색어 지우기", null))
        assertNull(RiskGuard.classify("Clear search", null))
        assertNull(RiskGuard.classify("Clear text", null))
        assertNull(RiskGuard.classify("입력 내용 지우기", null))
    }

    @Test fun deletingSearchHistoryIsStillDelete() {
        assertEquals(RiskCategory.DELETE, RiskGuard.classify("검색 기록 삭제", null))
        assertEquals(RiskCategory.DELETE, RiskGuard.classify("Clear search history", null))
        assertEquals(RiskCategory.DELETE, RiskGuard.classify("지우기", null))
    }

    @Test fun inAppAllowIsNotSecurity() {
        assertNull(RiskGuard.classify("알림 허용", null))
        assertNull(RiskGuard.classify("Allow", "com.example.app"))
        assertNull(RiskGuard.classify("권한 설정", "com.example.app"))
    }

    @Test fun decliningAPermissionIsNotSecurity() {
        val pkg = "com.google.android.permissioncontroller"
        assertNull(RiskGuard.classify("Don’t allow", pkg))
        assertNull(RiskGuard.classify("Don't allow", pkg))
        assertNull(RiskGuard.classify("허용 안함", pkg))
        assertNull(RiskGuard.classify("허용하지 않음", pkg))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Allow", pkg))
    }

    @Test fun permissionControllerAllowIsSecurity() {
        for (pkg in listOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
        )) {
            assertEquals(pkg, RiskCategory.SECURITY, RiskGuard.classify("허용", pkg))
            assertEquals(pkg, RiskCategory.SECURITY, RiskGuard.classify("Allow", pkg))
        }
    }

    @Test fun grantingAccessIsSecurity() {
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("로그인 허용", null))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("접근 허용", null))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Allow access", null))
    }

    @Test fun paymentAndPasswordAlwaysConfirm() {
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("결제", "com.example.app"))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("결제", "com.google.android.permissioncontroller"))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("비밀번호", "com.example.app"))
        assertEquals(RiskCategory.SECURITY, RiskGuard.classify("Password", null))
    }

    // ---- review fix round 1 ------------------------------------------------------------------

    @Test fun mustConfirmLabels() {
        val cases = mapOf(
            "송금하기" to RiskCategory.MONEY, "결제 진행" to RiskCategory.MONEY, "Pay now" to RiskCategory.MONEY,
            "Place order" to RiskCategory.MONEY, "Confirm purchase" to RiskCategory.MONEY,
            "카드 등록" to RiskCategory.MONEY, "카드등록" to RiskCategory.MONEY, "선물하기" to RiskCategory.MONEY,
            "예약하기" to RiskCategory.MONEY, "Check out" to RiskCategory.MONEY, "Checkout" to RiskCategory.MONEY,
            "Continue ordering" to RiskCategory.MONEY, "Order now" to RiskCategory.MONEY,
            "Send message" to RiskCategory.SEND, "Submit" to RiskCategory.SEND, "Publish" to RiskCategory.SEND,
            "계정 삭제" to RiskCategory.DELETE, "모두 지우기" to RiskCategory.DELETE, "삭제" to RiskCategory.DELETE,
            "비밀번호 변경" to RiskCategory.SECURITY, "Sign out" to RiskCategory.SECURITY,
            "Log out" to RiskCategory.SECURITY, "로그아웃" to RiskCategory.SECURITY,
        )
        for ((label, category) in cases) assertEquals(label, category, RiskGuard.classify(label, "com.example.app"))
    }

    // "등록"/"댓글 등록" used to be asserted null; review #6 makes them SEND (Naver comment / cafe post submit).
    @Test fun registerIsSend() {
        assertEquals(RiskCategory.SEND, RiskGuard.classify("등록", null))
        assertEquals(RiskCategory.SEND, RiskGuard.classify("댓글 등록", null))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("카드 등록", null))
    }

    @Test fun orderingStillNotBorderOrRecorder() {
        assertNull(RiskGuard.classify("Border", null))
        assertNull(RiskGuard.classify("Recorder", null))
        assertNull(RiskGuard.classify("Reorder list", null))
    }

    @Test fun bulkClearInOwnLabelStillConfirms() {
        assertEquals(RiskCategory.DELETE, RiskGuard.classify("Clear all text messages", null))
        assertEquals(RiskCategory.DELETE, RiskGuard.classify("검색 기록 모두 삭제", null))
        assertEquals(RiskCategory.DELETE, RiskGuard.classify("입력한 문자 전체 삭제", null))
    }

    @Test fun fieldClearExemptionOnlyForOwnLabel() {
        assertNull(RiskGuard.classify("검색어 지우기", null))
        assertEquals(
            RiskCategory.DELETE,
            RiskGuard.classify("검색어 지우기", null, allowFieldClear = false),
        )
        assertEquals(
            RiskCategory.DELETE,
            RiskGuard.classify("대화방을 삭제할까요? 검색", null, allowFieldClear = false),
        )
    }

    @Test fun allowInSystemSettingsIsSecurity() {
        for (pkg in listOf("com.android.settings", "com.samsung.android.settings", "com.samsung.android.settings.intelligence", "com.google.android.gms")) {
            assertEquals(pkg, RiskCategory.SECURITY, RiskGuard.classify("허용", pkg))
            assertEquals(pkg, RiskCategory.SECURITY, RiskGuard.classify("Allow", pkg))
            assertEquals(pkg, RiskCategory.SECURITY, RiskGuard.classify("이 출처 허용", pkg))
        }
        assertNull(RiskGuard.classify("알림 허용", "com.example.app"))
    }

    @Test fun bareSubscribeAndChargeAreMoneyInStoreAndPayApps() {
        for (pkg in listOf("com.android.vending", "com.samsung.android.spay", "com.kakaopay.app", "viva.republica.toss", "com.naverfin.payapp")) {
            assertEquals(pkg, RiskCategory.MONEY, RiskGuard.classify("구독", pkg))
            assertEquals(pkg, RiskCategory.MONEY, RiskGuard.classify("Subscribe", pkg))
            assertEquals(pkg, RiskCategory.MONEY, RiskGuard.classify("충전", pkg))
        }
        assertNull(RiskGuard.classify("구독", "com.google.android.youtube"))
        assertNull(RiskGuard.classify("충전", "com.android.settings"))
    }

    @Test fun productCardsThatMentionPastPurchasesAreBrowsing() {
        val pkg = "com.coupang.mobile"
        assertNull(RiskGuard.classify("세라비 모이스춰라이징 로션 473ml 19,900원 4.8 (12,345) 1만명 이상 구매", pkg))
        assertNull(RiskGuard.classify("구매후기 3,201개", pkg))
        assertNull(RiskGuard.classify("최근 구매 많은 상품", pkg))
        assertNull(RiskGuard.classify("주문내역", pkg))
        assertNull(RiskGuard.classify("장바구니 담기", pkg))
    }

    @Test fun buyingButtonsStayMoney() {
        val pkg = "com.coupang.mobile"
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("바로구매", pkg))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("2개 구매하기", pkg))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("구매하기", pkg))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("주문하기 19,900원", pkg))
        assertEquals(RiskCategory.MONEY, RiskGuard.classify("1만명 이상 구매 · 결제하기", pkg))
    }
}
