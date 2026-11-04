package com.phoneuse.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review findings #1, #3, #4, #6, #11, #12, #13. */
class RiskGuardReviewTest {
    private fun c(label: String, pkg: String? = null) = RiskGuard.classify(label, pkg)

    @Test fun sendInPayAndBankAppsIsMoney() {
        val pkgs = listOf(
            "viva.republica.toss", "com.kakaopay.app", "com.kbstar.kbbank", "com.shinhan.sbanking",
            "com.kakaobank.channel", "com.wooribank.smart.npib", "com.kebhana.hanapush", "com.nh.smart.banking",
        )
        for (pkg in pkgs) {
            assertEquals(pkg, RiskCategory.MONEY, c("보내기", pkg))
            assertEquals(pkg, RiskCategory.MONEY, c("전송", pkg))
            assertEquals(pkg, RiskCategory.MONEY, c("Send", pkg))
        }
        assertEquals(RiskCategory.SEND, c("보내기", "com.kakao.talk"))
        assertTrue(RiskGuard.isPayPackage("com.kbstar.kbbank"))
    }

    @Test fun amountWithAffirmativeIsMoney() {
        assertEquals(RiskCategory.MONEY, c("확인 홍길동님에게 10,000원을 보낼까요?"))
        assertEquals(RiskCategory.MONEY, c("OK Send ₩5,000 to Kim?"))
        assertEquals(RiskCategory.MONEY, c("Confirm $12.50"))
        assertEquals(RiskCategory.MONEY, c("확인 3,000円"))
        assertNull(c("확인 사진 3장을 선택했습니다"))
        assertNull(c("확인"))
    }

    @Test fun priceOnlyButtonIsMoney() {
        for (l in listOf("₩4,900", "$0.99", "4,900원", "€2,99", "1,200円", "₩4,900/월")) assertEquals(l, RiskCategory.MONEY, c(l))
        assertNull(c("4.8 별점"))
        assertNull(c("세라비 로션 19,900원 4.8"))
    }

    @Test fun trialAndMembershipAreMoney() {
        assertEquals(RiskCategory.MONEY, c("무료 체험 시작"))
        assertEquals(RiskCategory.MONEY, c("Start free trial"))
        assertEquals(RiskCategory.MONEY, c("Subscribe now"))
        assertEquals(RiskCategory.MONEY, c("멤버십 가입"))
        assertNull(c("Subscribe"))
    }

    @Test fun japaneseKeywords() {
        assertEquals(RiskCategory.MONEY, c("購入する"))
        assertEquals(RiskCategory.MONEY, c("支払う"))
        assertEquals(RiskCategory.MONEY, c("送金"))
        assertEquals(RiskCategory.DELETE, c("削除"))
        assertEquals(RiskCategory.DELETE, c("ゴミ箱に移動"))
        assertEquals(RiskCategory.SEND, c("送信"))
        assertEquals(RiskCategory.SEND, c("投稿"))
        assertEquals(RiskCategory.SECURITY, c("ログイン"))
        assertEquals(RiskCategory.SECURITY, c("パスワード"))
        val pc = "com.google.android.permissioncontroller"
        assertEquals(RiskCategory.SECURITY, c("許可", pc))
        assertNull(c("許可しない", pc))
        assertNull(c("設定"))
    }

    @Test fun chineseKeywords() {
        assertEquals(RiskCategory.MONEY, c("付款"))
        assertEquals(RiskCategory.MONEY, c("立即购买"))
        assertEquals(RiskCategory.MONEY, c("轉帳"))
        assertEquals(RiskCategory.DELETE, c("删除"))
        assertEquals(RiskCategory.DELETE, c("刪除"))
        assertEquals(RiskCategory.DELETE, c("清空回收站"))
        assertEquals(RiskCategory.SEND, c("发送"))
        assertEquals(RiskCategory.SEND, c("發佈"))
        assertEquals(RiskCategory.SECURITY, c("登录"))
        assertEquals(RiskCategory.SECURITY, c("登入"))
        assertEquals(RiskCategory.SECURITY, c("密碼"))
        val pc = "com.android.permissioncontroller"
        assertEquals(RiskCategory.SECURITY, c("允许", pc))
        assertNull(c("不允许", pc))
        assertNull(c("拒绝", pc))
        assertNull(c("设置"))
        assertNull(c("清除搜索"))
    }

    @Test fun moreDeleteWords() {
        for (l in listOf("휴지통으로 이동", "Move to trash", "Move to bin", "초기화", "Reset", "포맷", "Format SD card", "Discard", "Wipe data", "버리기")) {
            assertEquals(l, RiskCategory.DELETE, c(l))
        }
        assertNull(c("Format"))
        assertNull(c("Trash folder"))
    }

    @Test fun publicPostWords() {
        for (l in listOf("제출", "올리기", "작성 완료", "Submit")) assertEquals(l, RiskCategory.SEND, c(l))
        assertEquals(RiskCategory.SEND, c("공유", "com.instagram.android"))
        assertEquals(RiskCategory.SEND, c("Share", "com.instagram.android"))
        assertNull(c("공유", "com.android.intentresolver"))
        assertNull(c("Share", "com.example.app"))
    }

    @Test fun deviceAdminAndProtectionSwitchesAreSecurity() {
        assertEquals(RiskCategory.SECURITY, c("이 기기 관리자 앱 활성화", "com.android.settings"))
        assertEquals(RiskCategory.SECURITY, c("Activate this device admin app"))
        assertEquals(RiskCategory.SECURITY, c("사용 중지", "com.google.android.gms"))
        assertEquals(RiskCategory.SECURITY, c("Turn off", "com.google.android.gms"))
        assertEquals(RiskCategory.SECURITY, c("끄기", "com.android.vending"))
        assertNull(c("Turn off", "com.example.app"))
        assertNull(c("끄기", "com.example.app"))
    }

    @Test fun falsePositivesNoLongerAsk() {
        for (l in listOf("게시판", "게시물", "게시글 보기", "Posts", "Postal code", "Post office", "Postcode")) assertNull(l, c(l))
        for (l in listOf("Your Orders", "Order history", "Order status", "Order details", "Sort order")) assertNull(l, c(l))
        for (l in listOf("File transfer", "Transfer photos")) assertNull(l, c(l))
        for (l in listOf("인증샷", "정품 인증")) assertNull(l, c(l))
        // still asked
        assertEquals(RiskCategory.SEND, c("Post"))
        assertEquals(RiskCategory.SEND, c("게시하기"))
        assertEquals(RiskCategory.MONEY, c("Place order"))
        assertEquals(RiskCategory.MONEY, c("Transfer $50"))
        assertEquals(RiskCategory.MONEY, c("Transfer to account"))
        assertEquals(RiskCategory.MONEY, c("Transfer funds"))
        assertEquals(RiskCategory.MONEY, c("Transfer", "viva.republica.toss"))
        assertEquals(RiskCategory.SECURITY, c("본인 인증"))
    }
}
