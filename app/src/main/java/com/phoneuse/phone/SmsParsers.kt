package com.phoneuse.phone

/**
 * One-time codes in notification and SMS text: [maskCodes] hides them on the phone before anything is stored or
 * reaches the model. Reading what a notification means is the model's job (com.phoneuse.understand).
 */
object SmsParsers {
    // ---- verification codes ----------------------------------------------------------------------

    private val CODE_CONTEXT = Regex("인증\\s*번호|인증\\s*코드|확인\\s*번호|본인\\s*확인|보안\\s*코드|OTP|[Vv]erification|[Cc]ode|코드")
    private val CODE = Regex("""(?<![\d,])\d{4,8}(?![\d,원])""")

    /** True for a one-time code text (인증번호, OTP...). */
    fun isVerification(text: String): Boolean = CODE_CONTEXT.containsMatchIn(text)

    /** In a one-time code text ([context] decides, e.g. title and text together), every 4–8 digit code becomes "●●●●●●"; other texts come back unchanged. */
    fun maskCodes(text: String, context: String = text): String =
        if (!isVerification(context)) text else CODE.replace(text) { "●".repeat(it.value.length) }
}
