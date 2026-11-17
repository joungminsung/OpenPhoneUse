package com.phoneuse.understand

import com.phoneuse.app.AuthMode
import com.phoneuse.app.ModelProviders
import com.phoneuse.i18n.tr

/** The words of Settings "알림 내용 분석" and its one-time notice. */
object UnderstandText {
    val TITLE: String get() = tr("Analyze notification content", "알림 내용 분석")

    private val EXPLAIN: String get() = tr(
        "Sends notification text to your AI model to sort payments, parcels and messages that need a reply. Codes are hidden first.",
        "결제, 택배, 답장할 메시지를 정리하려고 알림 내용을 설정된 AI 모델로 보내요. 인증번호는 보내기 전에 가려요.",
    )

    /** The switch's explanation; a provider without a light model analyses with the chosen model, and says so. */
    fun subtitle(mode: AuthMode): String =
        if (ModelProviders.lightModel(mode) == null) "$EXPLAIN " + tr("Uses your main model.", "기본 모델로 분석해요.") else EXPLAIN

    val NOTICE_TITLE: String get() = tr("Notifications are sorted by AI", "알림을 AI가 정리해요")
    val KEEP: String get() = tr("OK", "확인")
    val TURN_OFF: String get() = tr("Turn off", "끄기")

    /** The notice shows once, while notification access is on. */
    fun showNotice(accessOn: Boolean, shown: Boolean): Boolean = accessOn && !shown
}
