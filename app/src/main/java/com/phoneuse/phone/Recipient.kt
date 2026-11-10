package com.phoneuse.phone

/** Who a call or a text goes to. */
sealed interface Recipient {
    /**
     * One number. When it came from the contacts, [name] is the contact's name and [asked] the name as it was
     * asked for (they differ when "민수" matched "김민수"); [asked] is null when the contact was found through
     * something other than its name (a nickname, a company), so it never stands for the user's word.
     */
    data class Found(val number: String, val name: String? = null, val asked: String? = null) : Recipient {
        /** As a confirmation card shows it. */
        val shown: String get() = if (name == null) number else "$name ($number)"
    }

    /** Not exactly one number; [reason] tells the model what to do next. */
    data class Unclear(val reason: String) : Recipient

    /** A name was given and the contacts cannot be read yet. */
    data object NeedsContacts : Recipient
}

/**
 * Turns the "to" of a call or a text into one number. A phone number is taken as it is. A name is looked up in
 * [contacts] and accepted only when it leads to one contact with one number, so a call or a text never goes to
 * a guess: with several matches the model has to ask the user.
 */
class RecipientResolver(private val contacts: ContactsSource?) {
    fun resolve(to: String): Recipient {
        val asked = to.replace(Regex("\\s+"), " ").trim()
        if (asked.isEmpty()) return Recipient.Unclear("받는 사람(to)이 비었어요")
        SmsTool.normalizeNumber(asked)?.let { return Recipient.Found(it) }
        // Digits and signs only, but not a number (too short or long, or a * or # code): never dialled.
        if (asked.none(Char::isLetter)) return Recipient.Unclear("전화번호가 올바르지 않아요 ($asked)")
        val source = contacts ?: return Recipient.Unclear("to는 전화번호여야 해요 ($asked). 이름이면 연락처에서 번호를 먼저 찾으세요.")
        val found = try {
            if (!source.hasPermission()) return Recipient.NeedsContacts
            source.search(asked, ContactsTool.MAX)
        } catch (e: SecurityException) {
            return Recipient.NeedsContacts
        }
        val reachable = found
            .map { it.copy(phones = lines(it.phones.mapNotNull { n -> SmsTool.normalizeNumber(n) })) }
            .filter { it.phones.isNotEmpty() }
        if (reachable.isEmpty()) {
            return Recipient.Unclear(
                if (found.isEmpty()) "연락처에서 '$asked'을(를) 찾지 못했어요. 사용자에게 전화번호를 물어보세요."
                else "연락처 '$asked'에 전화번호가 없어요. 사용자에게 전화번호를 물어보세요.",
            )
        }
        // "민수" also finds "김민수" and "박민수"; a contact called exactly "민수" wins over them.
        val exact = reachable.filter { key(it.name) == key(asked) }
        val pool = exact.ifEmpty { reachable }
        val one = pool.singleOrNull()
        if (one != null && one.phones.size == 1) {
            return Recipient.Found(one.phones[0], one.name, asked.takeIf { key(it) in key(one.name) })
        }
        val list = pool.joinToString("; ") { "${it.name}: ${it.phones.joinToString(", ")}" }
        return Recipient.Unclear(
            "'$asked'에 해당하는 번호가 여러 개예요 ($list). 추측하지 말고 ask_user로 어느 쪽인지 물어본 뒤, 고른 전화번호를 to에 넣어 다시 호출하세요.",
        )
    }

    private fun key(name: String) = name.filterNot(Char::isWhitespace).lowercase()

    /** [numbers] without the ones that are a line already there written another way (010… and +82 10…). */
    private fun lines(numbers: List<String>): List<String> =
        numbers.fold(emptyList()) { kept, n -> if (kept.any { ContactAdder.sameNumber(it, n) }) kept else kept + n }
}
