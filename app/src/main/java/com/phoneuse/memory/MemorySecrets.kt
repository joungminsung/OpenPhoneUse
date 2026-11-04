package com.phoneuse.memory

import com.phoneuse.i18n.tr

/** A memory refused because it holds a secret; the message says why (the model relays it to the user). */
class SecretInMemoryException(message: String) : IllegalArgumentException(message)

/**
 * Secrets never go into memory: memories are stored as plain text in the chat database and are sent to the model
 * with every request they match, and the model must never type a password. Refused: a password, PIN, passcode or
 * one-time code given with its value, a card number (Luhn-valid 13-19 digits), and a resident registration number.
 * A fact that only mentions such a thing ("uses a password manager") is fine.
 */
object MemorySecrets {
    private val secretWord = Regex(
        """(?i)비밀\s*번호|비번|암호|패스워드|핀\s*번호|인증\s*번호|보안\s*코드|일회용|\b(?:password|passwd|pwd|passcode|pin|otp|cvc|cvv|verification code|security code|one[- ]time code)\b""",
    )

    /** A value next to the word: 4+ digits, or "word: value" / "word is value" with a digit or 6+ characters. */
    private val secretValue = Regex(
        """\d{4,}|(?i)(?:[:=]|\bis\b|(?:는|은|이|가)\s)\s*\S*\d\S*|[:=]\s*\S{6,}""",
    )

    private val cardLike = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")
    private val rrn = Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})\s*-?\s*([1-8])\d{6}(?!\d)""")

    /** Why [text] may not be remembered, or null when it may. */
    fun refusal(text: String): String? = when {
        rrn.findAll(text).any { m -> plausibleBirth(m.groupValues[2], m.groupValues[3]) } ->
            tr(
                "Not remembered: it contains a resident registration number. Memories are plain text sent to the model, so ID numbers are never kept",
                "기억하지 않았어요: 주민등록번호가 들어 있어요. 기억은 암호화되지 않고 모델에게 보내지므로 주민등록번호는 저장하지 않아요",
            )
        cardLike.findAll(text).any { luhn(it.value.filter(Char::isDigit)) } ->
            tr(
                "Not remembered: it contains a card number. Memories are plain text sent to the model, so card numbers are never kept",
                "기억하지 않았어요: 카드 번호가 들어 있어요. 기억은 암호화되지 않고 모델에게 보내지므로 카드 번호는 저장하지 않아요",
            )
        secretWord.containsMatchIn(text) && secretValue.containsMatchIn(text) ->
            tr(
                "Not remembered: it looks like a password, PIN or verification code. Memories are plain text sent to the model, and the app never types passwords, so secrets are never kept",
                "기억하지 않았어요: 비밀번호·PIN·인증번호로 보여요. 기억은 암호화되지 않고 모델에게 보내지며, 앱은 비밀번호를 직접 입력하지 않으므로 비밀 정보는 저장하지 않아요",
            )
        else -> null
    }

    /** Throws [SecretInMemoryException] when [text] holds a secret. */
    fun check(text: String) {
        refusal(text)?.let { throw SecretInMemoryException(it) }
    }

    private fun plausibleBirth(month: String, day: String): Boolean =
        month.toInt() in 1..12 && day.toInt() in 1..31

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        for ((i, ch) in digits.reversed().withIndex()) {
            var d = ch - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
