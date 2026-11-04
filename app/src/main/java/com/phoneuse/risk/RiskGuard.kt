package com.phoneuse.risk

/** [CHANGE] is a change to the phone the user asked for (an alarm, the wallpaper); never from RiskGuard.classify. */
enum class RiskCategory { MONEY, SECURITY, DELETE, SEND, CHANGE }

/**
 * A keyword match and how sure it is. A [strong] match (high-precision words: 결제, 송금, 삭제, 비밀번호, a price-only
 * button, a send in a pay or bank app) always asks the user, whatever the AI risk judge says; a weak one ("주문",
 * "게시", a dialog's "인증", a granted permission) may be cleared by the judge (see agent/RiskJudge.kt RiskCombine).
 */
data class RiskMatch(val category: RiskCategory, val strong: Boolean)

object RiskGuard {
    /**
     * Korean, Japanese and Chinese keywords match as substrings. English phrases must start a word (so "border" is
     * not "order"); those in [wholeEnglish] must also end one, allowing "s"/"ing" ("orders", "ordering", not
     * "orderly"); those in [exactEnglish] must be the whole word ("post", not "posts" or "postal").
     */
    private class Keywords(
        private val cjk: List<String>,
        englishWords: List<String>,
        wholeEnglish: List<String> = emptyList(),
        exactEnglish: List<String> = emptyList(),
    ) {
        private val english = englishWords.map { Regex("""(?<![a-z])""" + body(it)) } +
            wholeEnglish.map { Regex("""(?<![a-z])""" + body(it) + """(?:s|ing)?(?![a-z])""") } +
            exactEnglish.map { Regex("""(?<![a-z])""" + body(it) + """(?![a-z])""") }

        private fun body(phrase: String) = phrase.split(" ").joinToString("""[\s\-_]*""") { Regex.escape(it) }

        fun matches(text: String): Boolean =
            cjk.any { text.contains(it) } || english.any { it.containsMatchIn(text) }
    }

    // Bare "구독"/"subscribe" (a channel subscribe) and bare "충전" (battery/charging screens) are harmless,
    // except in a store or pay app ([paidSubscribe]). "check out" also matches "checkout". "transfer" needs
    // money context ([isMoneyTransfer]): "File transfer" is not a payment.
    private val money = Keywords(
        listOf(
            "결제", "구매", "주문", "송금", "이체", "충전하기", "정기결제", "해지", "카드 등록", "카드등록", "카드 추가", "카드추가", "선물하기", "예약하기",
            "무료 체험", "무료체험", "멤버십 가입", "멤버십가입",
            // Japanese
            "購入", "支払", "決済", "送金", "注文", "振込", "振り込み", "課金", "解約", "カード登録", "定期購入", "無料体験", "無料トライアル",
            // Chinese (simplified, traditional)
            "支付", "付款", "购买", "購買", "下单", "下單", "转账", "轉帳", "汇款", "匯款", "订购", "訂購", "结算", "結算", "結帳",
            "充值", "储值", "儲值", "提现", "提現", "退订", "退訂", "免费试用", "免費試用",
        ),
        listOf("pay", "buy", "purchase", "check out", "start free trial", "free trial", "subscribe now", "membership"),
        wholeEnglish = listOf("order"),
    )
    private val transferWord = Regex("""(?<![a-z])transfer(?:s|ring)?(?![a-z])""")
    private val transferContext = Regex(
        """account|money|funds?|balance|bank|wire|계좌|잔액|통장|은행|口座|账户|帳戶|账号""",
    )

    /** "Your orders", "Order history/status/details", "Sort order": browsing, not buying. */
    private val orderNoise = Regex(
        """(?<![a-z])(?:your|my) orders?(?![a-z])|(?<![a-z])order[\s\-_]*(?:history|status|details?|summary|number|no)(?![a-z])|(?<![a-z])sort[\s\-_]*order(?![a-z])""",
    )

    /**
     * Words about past purchases on shopping pages ("구매후기", "1만명 구매", "주문내역"): a product card carrying
     * them is browsing, not buying. Removed before the money words are matched.
     */
    private val purchaseInfo = Regex(
        """구매\s*(후기|평|자|수|율|건|횟수|많은|순|만족|인증|내역|목록)|[0-9][0-9,.]*\s*[만천백]?\+?\s*(명|개|건|회)?\s*(이상\s*|이\s*)?구매(?!\s*하)|구매한|구매하신|주문\s*(내역|목록|수|건|조회)""",
    )
    private val paidSubscribe = Keywords(
        listOf("구독", "충전", "신청하기", "チャージ", "購読", "订阅", "訂閱"),
        listOf("subscribe"),
    )

    /** A price: "₩4,900", "$0.99", "4,900원", "1,200円", with an optional "/월". */
    private const val AMOUNT = """(?:[₩$€¥￥£]\s*[0-9][0-9,.]*|[0-9][0-9,.]*\s*(?:원|₩|\$|€|¥|￥|円|元|usd|krw|eur|jpy))"""
    private val amount = Regex(AMOUNT)
    private val priceOnly = Regex("""^\s*""" + AMOUNT + """(?:\s*/\s*\S{1,6})?\s*$""")

    /** A bare "확인"/"OK" in text that also names a sum is the confirm button of a payment dialog. */
    private val affirmative = Regex(
        """(?<![가-힣a-z])(?:확인|동의|예|네|계속|ok|yes|confirm|continue|accept|done|はい|同意|确认|確認|确定|確定)(?![가-힣a-z])""",
    )

    private val security = Keywords(
        listOf(
            "비밀번호", "암호", "로그인", "로그아웃", "인증", "탈퇴", "기기 관리자", "기기관리자",
            "パスワード", "ログイン", "ログアウト", "サインイン", "サインアウト", "認証", "退会", "暗証番号", "デバイス管理",
            "密码", "密碼", "登录", "登入", "登出", "注销", "註銷", "验证", "驗證", "设备管理", "裝置管理",
        ),
        listOf("password", "sign in", "log in", "sign out", "log out", "device admin"),
    )
    private val securityNoise = Regex("""인증\s*샷|정품\s*인증""")

    /** "허용"/"권한" words: security only in system surfaces ([grantPackage]) or when granting account/app access. */
    private val grant = Keywords(
        listOf("허용", "권한", "許可", "権限", "允许", "允許", "权限", "權限"),
        listOf("allow", "permission"),
    )
    private val accessGrant = Keywords(
        listOf("접근", "액세스", "엑세스", "アクセス", "访问", "訪問", "存取"),
        listOf("access", "grant"),
    )

    /** Declining a grant ("허용 안함", "Don't allow") hands nothing over, so it is never a security step. */
    private val refuseGrant = Keywords(
        listOf(
            "허용 안", "허용안", "허용하지", "거부",
            "許可しない", "許可しません", "拒否", "今はしない",
            "不允许", "不允許", "拒绝", "拒絕", "禁止", "暂不", "暫不",
        ),
        listOf("don't allow", "don’t allow", "do not allow", "deny", "not now"),
    )

    /** Turning off or activating a protection in a system surface ([grantPackage], the Play Store). */
    private val protectionSwitch = Keywords(
        listOf("활성화", "사용 중지", "사용중지", "끄기", "有効にする", "無効にする", "オフにする", "激活", "停用", "关闭", "關閉"),
        listOf("activate", "turn off", "disable"),
    )

    private val delete = Keywords(
        listOf(
            "삭제", "지우기", "제거", "비우기", "영구", "휴지통으로", "휴지통 이동", "휴지통에", "초기화", "포맷", "버리기",
            "削除", "消去", "初期化", "ゴミ箱", "空にする", "完全に",
            "删除", "刪除", "清空", "清除", "移除", "卸载", "卸載", "重置", "恢复出厂", "恢復原廠", "回收站", "資源回收",
        ),
        listOf(
            "delete", "remove", "erase", "uninstall", "clear", "move to trash", "move to bin", "send to trash",
            "empty trash", "empty bin", "format sd", "format card", "format storage", "format usb", "wipe", "discard",
        ),
        exactEnglish = listOf("reset"),
    )

    /** Clearing what is typed in a search or input field ("검색어 지우기", "Clear search") deletes nothing. */
    private val inputField = Keywords(
        listOf("검색", "입력", "検索", "入力", "搜索", "搜尋", "输入", "輸入"),
        listOf("search", "text", "query"),
    )
    private val fieldClear = Keywords(
        listOf("지우기", "삭제", "지움", "消去", "清除", "清空"),
        listOf("clear"),
    )
    private val history = Keywords(
        listOf(
            "기록", "내역", "모두", "전체", "메시지", "대화", "문자",
            "履歴", "すべて", "全て", "メッセージ", "记录", "記錄", "全部", "所有", "消息", "訊息", "短信",
        ),
        listOf("history", "all", "message", "messages", "everything"),
    )

    // "공유"/"share" opens a share sheet and "답장"/"reply" a composer; the actual send is confirmed there.
    // Only in a social app ([socialPackages]) is a bare "공유"/"Share" the final post button.
    private val send = Keywords(
        listOf(
            "전송", "보내기", "게시", "등록", "제출", "올리기", "작성 완료", "작성완료",
            "送信", "投稿", "提出", "公開",
            "发送", "發送", "傳送", "送出", "发布", "發布", "發佈", "发表", "發表", "提交", "发帖",
        ),
        listOf("send", "submit", "publish"),
        exactEnglish = listOf("post"),
    )
    private val boardWords = Regex("""게시판|게시물|게시글|(?<![a-z])post[\s\-_]*(?:office|box|code|al)(?![a-z])""")
    private val shareLabel = Regex("""^\s*(?:공유|공유\s*하기|share|share now|共有|分享|分享到)\s*$""")
    private val exportWord = Regex("""\bexport\b""")

    // The high-precision part of each list ([RiskMatch.strong]): these always ask, whatever the AI judge says.
    private val strongMoney = Keywords(
        listOf(
            "결제", "구매", "송금", "이체", "주문하기",
            "購入", "支払", "決済", "送金", "振込", "振り込み", "課金",
            "支付", "付款", "购买", "購買", "下单", "下單", "转账", "轉帳", "汇款", "匯款",
        ),
        listOf("pay", "purchase", "buy"),
    )
    private val strongSecurity = Keywords(
        listOf(
            "비밀번호", "탈퇴", "기기 관리자", "기기관리자",
            "パスワード", "退会", "暗証番号", "デバイス管理",
            "密码", "密碼", "注销", "註銷", "设备管理", "裝置管理",
        ),
        listOf("password", "device admin"),
    )
    private val strongDelete = Keywords(
        listOf("삭제", "영구", "削除", "删除", "刪除", "卸载", "卸載", "恢复出厂", "恢復原廠", "휴지통 비우기", "휴지통비우기"),
        listOf(
            "delete", "uninstall", "format sd", "format card", "format storage", "format usb",
            "empty trash", "empty bin", "permanently", "wipe",
        ),
    )

    /**
     * Deletions that cannot be undone or take more than one item: a DELETE matching these always keeps its card,
     * even when the user asked for a deletion and the AI judge calls it a single recoverable item (see
     * ToolExecutor). Includes the uninstall/package-installer and account-leaving words.
     */
    private val irreversibleDelete = Keywords(
        listOf(
            "영구", "휴지통 비우기", "휴지통비우기", "비우기", "초기화", "포맷", "탈퇴", "모두 삭제", "모두삭제", "전체 삭제", "전체삭제",
            "모두 지우기", "전체 지우기", "전체 선택", "전체선택", "완전 삭제", "완전히 삭제",
            "完全に", "空にする", "初期化", "すべて削除", "全て削除", "退会",
            "永久", "清空", "全部删除", "全部刪除", "卸载", "卸載", "重置", "恢复出厂", "恢復原廠", "注销", "註銷",
        ),
        listOf(
            "permanent", "empty trash", "empty bin", "uninstall", "format", "wipe", "factory", "delete all", "remove all",
            "clear all", "erase all", "delete everything", "select all", "delete account", "packageinstaller",
        ),
        exactEnglish = listOf("reset"),
    )

    /** Whether any of [texts] names a deletion that is permanent or takes many items ([irreversibleDelete]). */
    fun irreversible(vararg texts: String?): Boolean =
        texts.any { t -> t != null && irreversibleDelete.matches(t.lowercase()) }
    private val strongSend = Keywords(listOf("전송", "보내기", "送信", "发送", "發送", "傳送"), listOf("send"))

    private val permissionControllers = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
    )

    /** System surfaces where "허용"/"Allow" hands over control of the phone or an account. */
    private fun grantPackage(pkg: String): Boolean =
        pkg in permissionControllers || pkg == "com.android.settings" ||
            pkg.startsWith("com.samsung.android.settings") || pkg == "com.google.android.gms"

    /** Store, pay and bank apps, where a bare "구독"/"Subscribe" or "충전" is a real charge and any send moves money. */
    private val payPackages = setOf(
        "com.android.vending", // Google Play billing
        "com.samsung.android.spay", // Samsung Wallet / Pay
        "com.kakaopay.app", // Kakao Pay
        "viva.republica.toss", // Toss (and Toss Bank)
        "com.naverfin.payapp", // Naver Pay
        "com.kbstar.kbbank", // KB Kookmin
        "com.shinhan.sbanking", // Shinhan
        "com.kakaobank.channel", // KakaoBank
        "com.wooribank.smart.npib", // Woori
        "com.kebhana.hanapush", // Hana
        "com.nh.smart.banking", // NH
    )

    /** Social apps where a bare "공유"/"Share" is the final post button, not a share-sheet opener. */
    private val socialPackages = setOf(
        "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.nhn.android.navercafe",
        "com.nhn.android.band", "com.kakao.story", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
    )

    /** A store or pay app: an unlabeled control there may be the pay button. */
    fun isPayPackage(packageName: String?): Boolean = packageName?.lowercase() in payPackages

    private fun isMoneyTransfer(text: String, pay: Boolean): Boolean {
        if (!transferWord.containsMatchIn(text)) return false
        return pay || text.trim().matches(Regex("""transfers?(?:\s+now)?""")) ||
            transferContext.containsMatchIn(text) || amount.containsMatchIn(text)
    }

    /**
     * [allowFieldClear] false (the caller classifies a dialog's surrounding text, not a button's own label)
     * keeps "삭제" a deletion even when that text also mentions 검색/입력.
     */
    fun classify(label: String?, packageName: String?, allowFieldClear: Boolean = true): RiskCategory? =
        match(label, packageName, allowFieldClear)?.category

    /**
     * [classify] with how sure the match is ([RiskMatch.strong]). Strong: the high-precision words, the package
     * installer, a price-only label, a sum with a confirm word, a charge or send in a pay/bank app, a send of an
     * amount ("10,000원 보내기"), a protection switched in a system surface. Every other match is weak.
     */
    fun match(label: String?, packageName: String?, allowFieldClear: Boolean = true): RiskMatch? {
        val pkg = packageName?.lowercase()
        if (pkg?.contains("packageinstaller") == true) return RiskMatch(RiskCategory.SECURITY, true)
        val text = label?.lowercase() ?: return null
        val pay = pkg in payPackages
        val moneyText = text.replace(purchaseInfo, " ").replace(orderNoise, " ")
        val protection = pkg != null && (grantPackage(pkg) || pkg == "com.android.vending")
        val sendText = text.replace("내보내기", " ").replace(exportWord, " ").replace(boardWords, " ")
        val securityText = text.replace(securityNoise, " ")
        return when {
            money.matches(moneyText) || isMoneyTransfer(moneyText, pay) -> RiskMatch(RiskCategory.MONEY, pay || strongMoney.matches(moneyText))
            priceOnly.matches(text) -> RiskMatch(RiskCategory.MONEY, true)
            amount.containsMatchIn(text) && affirmative.containsMatchIn(text) -> RiskMatch(RiskCategory.MONEY, true)
            pay && paidSubscribe.matches(text) -> RiskMatch(RiskCategory.MONEY, true)
            security.matches(securityText) -> RiskMatch(RiskCategory.SECURITY, strongSecurity.matches(securityText))
            grant.matches(text) && !refuseGrant.matches(text) &&
                (pkg != null && grantPackage(pkg) || accessGrant.matches(text)) -> RiskMatch(RiskCategory.SECURITY, false)
            protection && protectionSwitch.matches(text) && !refuseGrant.matches(text) -> RiskMatch(RiskCategory.SECURITY, true)
            delete.matches(text) && !(allowFieldClear && isFieldClear(text)) -> RiskMatch(RiskCategory.DELETE, strongDelete.matches(text))
            send.matches(sendText) ->
                // A send in a pay or bank app, or of an amount, moves money.
                if (pay || amount.containsMatchIn(sendText)) {
                    RiskMatch(RiskCategory.MONEY, true)
                } else {
                    RiskMatch(RiskCategory.SEND, strongSend.matches(sendText))
                }
            pkg in socialPackages && shareLabel.matches(text) -> RiskMatch(RiskCategory.SEND, false)
            else -> null
        }
    }

    private fun isFieldClear(text: String): Boolean =
        inputField.matches(text) && fieldClear.matches(text) && !history.matches(text)
}
