package com.phoneuse.phone

import com.phoneuse.i18n.tr
import kotlinx.coroutines.delay

/** The phone's outgoing calls; Android's is [AndroidCallSource]. */
interface CallSource {
    /** The CALL_PHONE permission is granted. */
    fun canCall(): Boolean

    /** Asks the phone to place the call; false when this device cannot make phone calls. */
    fun call(number: String): Boolean

    /** A call is being placed or is connected. */
    fun inCall(): Boolean
}

/**
 * call: places a phone call to a number or to the one contact a name matches. [confirm] decides whether the
 * call goes out (the confirmation card, or the user's own words); afterwards the tool checks that a call really
 * started, because the phone answers nothing when it refuses one.
 */
class CallTool(
    private val source: CallSource,
    private val resolver: RecipientResolver,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    /** [confirm] gets the recipient and the card's summary; nothing is dialled unless it returns true. */
    suspend fun call(to: String, confirm: suspend (Recipient.Found, String) -> Boolean): PhoneReply {
        val who = when (val r = resolver.resolve(to)) {
            is Recipient.Found -> r
            is Recipient.Unclear -> return PhoneReply.Failed(r.reason)
            Recipient.NeedsContacts -> return PhoneReply.PermissionNeeded(ContactsTool.LABEL, ContactsTool.PERMISSIONS)
        }
        if (!source.canCall()) return NEEDED
        if (source.inCall()) return PhoneReply.Failed("이미 통화 중이라 새로 걸지 않았어요")
        if (!confirm(who, confirmSummary(who))) return PhoneReply.Cancelled
        try {
            if (!source.call(who.number)) return PhoneReply.Failed("이 기기에서는 전화를 걸 수 없어요")
        } catch (e: SecurityException) {
            return NEEDED
        }
        repeat(VERIFY_TRIES) {
            wait(VERIFY_INTERVAL_MS)
            if (source.inCall()) {
                return PhoneReply.Ok("OK: ${who.shown}에 전화를 걸고 있어요. 통화 화면이 떠 있으니 더 조작하지 말고 stay_in_app=true로 finish 하세요.")
            }
        }
        // Not seen: never dial again (a second call on top of a slow first one).
        return PhoneReply.Ok(
            "확인 못 함: ${who.shown}에 전화를 요청했지만 통화가 시작됐는지 확인하지 못했어요. 다시 걸지 말고, " +
                "사용자에게 화면을 확인해 달라고 하세요 (긴급 번호는 다이얼 화면까지만 열려요).",
        )
    }

    companion object {
        val LABEL get() = tr("Phone calls", "전화 걸기")
        val PERMISSIONS = listOf("android.permission.CALL_PHONE")
        private val NEEDED get() = PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)
        private const val VERIFY_TRIES = 8
        private const val VERIFY_INTERVAL_MS = 500L

        /** Starts the card summary of a call (see confirmCopy): the number, then the contact's name on its own line. */
        val CONFIRM_PREFIX get() = "" + tr("Call → ", "전화 걸기 → ")

        fun confirmSummary(who: Recipient.Found) = "$CONFIRM_PREFIX${who.number}\n${who.name.orEmpty()}"
    }
}
