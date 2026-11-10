package com.phoneuse.phone

import com.phoneuse.i18n.tr

/** Saving to the phone's contacts; Android's is [AndroidContactWriter]. */
interface ContactWriter {
    fun canWrite(): Boolean

    /** Saves a new contact with one mobile number and, when given, one email; false when nothing was saved. */
    fun insert(name: String, phone: String, email: String?): Boolean
}

/**
 * add_contact. Needs WRITE_CONTACTS, asked for on first use. A contact that [existing] already holds under the
 * same name and number is not saved twice; anything else is saved only after [add]'s confirm allows it.
 */
class ContactAdder(private val writer: ContactWriter, private val existing: ContactsSource? = null) {
    /** [confirm] gets the number that will be saved and the card's summary; nothing is saved unless it returns true. */
    suspend fun add(name: String, phone: String, email: String?, confirm: suspend (number: String, summary: String) -> Boolean): PhoneReply {
        val who = name.replace(Regex("\\s+"), " ").trim()
        if (who.isEmpty()) return PhoneReply.Failed("name required")
        if (who.length > MAX_NAME) return PhoneReply.Failed("이름이 너무 길어요 (최대 ${MAX_NAME}자)")
        val number = SmsTool.normalizeNumber(phone) ?: return PhoneReply.Failed("phone은 전화번호여야 해요 ($phone)")
        val mail = email?.trim()?.takeIf { it.isNotEmpty() }
        if (mail != null && ('@' !in mail || mail.length > MAX_EMAIL || mail.any { it.isWhitespace() })) {
            return PhoneReply.Failed("email은 이메일 주소여야 해요 ($mail)")
        }
        if (!writer.canWrite()) return PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)
        if (alreadySaved(who, number)) return PhoneReply.Ok("이미 저장됨: '$who' 연락처에 $number 번호가 이미 있어요. 새로 저장하지 않았어요.")
        if (!confirm(number, confirmSummary(who, number, mail))) return PhoneReply.Cancelled
        val saved = try {
            writer.insert(who, number, mail)
        } catch (e: SecurityException) {
            // Revoked between the check and the write.
            return PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)
        } catch (e: Exception) {
            false
        }
        if (!saved) return PhoneReply.Failed("연락처를 저장하지 못했어요")
        return PhoneReply.Ok("OK: '$who' ($number) 연락처를 저장했어요.")
    }

    /** Whether a contact found by [name] already has [number]. Not readable (no READ_CONTACTS) counts as no. */
    private fun alreadySaved(name: String, number: String): Boolean {
        val source = existing ?: return false
        return try {
            source.hasPermission() &&
                source.search(name, ContactsTool.MAX).any { c -> c.phones.any { p -> SmsTool.normalizeNumber(p)?.let { sameNumber(it, number) } == true } }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        val LABEL get() = tr("Save contacts", "연락처 저장")
        val PERMISSIONS = listOf("android.permission.WRITE_CONTACTS")
        const val MAX_NAME = 60
        private const val MAX_EMAIL = 254

        /**
         * Starts the CHANGE summary of a contact save; ConfirmCopy shows the name and the number from it. It begins
         * with a private-use marker, so a summary that merely reads "연락처 저장 → …" is not drawn as this card.
         */
        val CONFIRM_PREFIX get() = "\uE004" + tr("Save contact → ", "연락처 저장 → ")

        /** "연락처 저장 → 민수", then the number that will be saved on the next line and the email, if any, on the one after. */
        fun confirmSummary(name: String, number: String, email: String? = null) =
            "$CONFIRM_PREFIX$name\n$number" + if (email == null) "" else "\n$email"

        /**
         * Whether two normalized numbers (see [SmsTool.normalizeNumber]) are the same line: equal, or one is the
         * other written with a country code (+821012345678 and 01012345678).
         */
        fun sameNumber(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.startsWith("+") == b.startsWith("+")) return false
            val abroad = (if (a.startsWith("+")) a else b).drop(1)
            val national = (if (a.startsWith("+")) b else a).removePrefix("0")
            return national.length >= MIN_NATIONAL && abroad.endsWith(national) && abroad.length - national.length in 1..3
        }

        private const val MIN_NATIONAL = 7
    }
}
