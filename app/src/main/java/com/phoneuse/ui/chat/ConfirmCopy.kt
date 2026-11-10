package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import com.phoneuse.phone.AlarmTool
import com.phoneuse.phone.CallTool
import com.phoneuse.phone.ContactAdder
import com.phoneuse.phone.SmsTool
import com.phoneuse.risk.RiskCategory

/** Wording of a confirmation card, shared by the chat screen and the overlay. */
data class ConfirmCopy(
    /** The small coloured label, e.g. "삭제 확인". */
    val kicker: String,
    val question: String,
    /** Detail lines for the grey box under the question. */
    val details: List<String>,
    /** Details are paths and are drawn in the mono font. */
    val monoDetails: Boolean,
    val note: String?,
    val allowLabel: String,
)

private const val TRASH_PREFIX = "휴지통으로 이동: "
private val MORE = Regex("""\(외 (\d+)개\)$""")

fun confirmCopy(category: RiskCategory, summary: String): ConfirmCopy {
    if (category == RiskCategory.DELETE && summary.startsWith(TRASH_PREFIX)) {
        var list = summary.removePrefix(TRASH_PREFIX)
        val more = MORE.find(list)
        if (more != null) list = list.substring(0, more.range.first)
        val paths = list.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        val total = paths.size + (more?.groupValues?.get(1)?.toInt() ?: 0)
        return ConfirmCopy(
            kicker = tr("Confirm delete", "삭제 확인"),
            question = tr("Move $total file${if (total == 1) "" else "s"} to the trash?", "파일 ${total}개를 휴지통으로 옮길까요?"),
            details = paths + listOfNotNull(more?.let { tr("${it.groupValues[1]} more", "외 ${it.groupValues[1]}개") }),
            monoDetails = true,
            note = tr("Moves them to the app's trash folder (.PhoneUseTrash). You can restore them later.", "앱 휴지통 폴더(.PhoneUseTrash)로 옮겨요. 나중에 되돌릴 수 있어요."),
            allowLabel = tr("Move to trash", "휴지통으로 옮기기"),
        )
    }
    if (category == RiskCategory.SEND && summary.startsWith(SmsTool.CONFIRM_PREFIX)) {
        // Who it goes to (the number, or "name (number)"), then the whole text as it will go out (the details box scrolls).
        val rest = summary.removePrefix(SmsTool.CONFIRM_PREFIX)
        val to = rest.substringBefore('\n').trim()
        val named = to.any(Char::isLetter)
        val body = rest.substringAfter('\n', "")
        return ConfirmCopy(
            kicker = tr("Confirm text message", "문자 전송 확인"),
            question = tr("Send this text to $to?", if (named) "${to}에게 이 문자를 보낼까요?" else "${to}에 이 문자를 보낼까요?"),
            details = listOf(body),
            monoDetails = false,
            note = tr("To $to · ${body.length} characters", "${if (named) "받는 사람" else "받는 번호"} $to · ${body.length}자"),
            allowLabel = tr("Send", "보내기"),
        )
    }
    if (category == RiskCategory.SEND && summary.startsWith(CallTool.CONFIRM_PREFIX)) {
        val rest = summary.removePrefix(CallTool.CONFIRM_PREFIX)
        val number = rest.substringBefore('\n').trim()
        val name = rest.substringAfter('\n', "").trim()
        return ConfirmCopy(
            kicker = tr("Confirm call", "전화 확인"),
            question = if (name.isEmpty()) tr("Call $number?", "${number}에 전화할까요?") else tr("Call $name?", "${name}에게 전화할까요?"),
            details = if (name.isEmpty()) emptyList() else listOf(number),
            monoDetails = false,
            note = tr("The call starts right away.", "허용하면 바로 전화가 걸려요."),
            allowLabel = tr("Call", "전화 걸기"),
        )
    }
    if (category == RiskCategory.CHANGE && summary.startsWith(AlarmTool.ALARM_PREFIX)) {
        // The time, then the repeat days and the name when there are any.
        val lines = summary.removePrefix(AlarmTool.ALARM_PREFIX).split('\n').map { it.trim() }
        val time = lines.first()
        return ConfirmCopy(
            kicker = tr("Confirm alarm", "알람 확인"),
            question = tr("Set an alarm for $time?", "${time}에 알람을 맞출까요?"),
            details = lines.drop(1).filter { it.isNotEmpty() },
            monoDetails = false,
            note = null,
            allowLabel = tr("Set", "맞추기"),
        )
    }
    if (category == RiskCategory.CHANGE && summary.startsWith(AlarmTool.TIMER_PREFIX)) {
        val lines = summary.removePrefix(AlarmTool.TIMER_PREFIX).split('\n').map { it.trim() }
        val length = lines.first()
        return ConfirmCopy(
            kicker = tr("Confirm timer", "타이머 확인"),
            question = tr("Start a $length timer?", "$length 타이머를 시작할까요?"),
            details = lines.drop(1).filter { it.isNotEmpty() },
            monoDetails = false,
            note = null,
            allowLabel = tr("Start", "시작"),
        )
    }
    if (category == RiskCategory.CHANGE && summary.startsWith(ContactAdder.CONFIRM_PREFIX)) {
        return ConfirmCopy(
            kicker = tr("Confirm new contact", "연락처 저장 확인"),
            question = tr("Save this contact?", "이 연락처를 저장할까요?"),
            details = summary.removePrefix(ContactAdder.CONFIRM_PREFIX).split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            monoDetails = false,
            note = null,
            allowLabel = tr("Save", "저장"),
        )
    }
    val details = listOf(summary.trim()).filter { it.isNotEmpty() }
    return when (category) {
        RiskCategory.MONEY -> ConfirmCopy(tr("Confirm payment", "결제 확인"), tr("This leads to a payment. Continue?", "결제로 이어지는 동작이에요. 진행할까요?"), details, false, null, tr("Allow payment", "결제 허용"))
        RiskCategory.SECURITY -> ConfirmCopy(tr("Confirm security action", "보안 확인"), tr("This affects your account or permissions. Continue?", "계정·권한에 관한 동작이에요. 진행할까요?"), details, false, null, tr("Allow", "허용"))
        RiskCategory.DELETE -> ConfirmCopy(tr("Confirm delete", "삭제 확인"), tr("This deletes something. Continue?", "지우는 동작이에요. 진행할까요?"), details, false, null, tr("Allow delete", "삭제 허용"))
        RiskCategory.SEND -> ConfirmCopy(tr("Confirm send", "전송 확인"), tr("Send it as is?", "이대로 보낼까요?"), details, false, null, tr("Send", "보내기"))
        RiskCategory.CHANGE -> ConfirmCopy(tr("Confirm change", "변경 확인"), tr("This changes a phone setting. Go ahead?", "폰 설정을 바꿔요. 이대로 할까요?"), details, false, null, tr("Continue", "진행"))
    }
}
