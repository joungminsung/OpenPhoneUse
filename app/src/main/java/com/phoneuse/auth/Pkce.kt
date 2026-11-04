package com.phoneuse.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PkceCodes(val verifier: String, val challenge: String) {
    override fun toString() = "PkceCodes(<redacted>)"
}

object Pkce {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** Verifier: base64url of 64 random bytes (86 chars). Challenge: S256 of the verifier. */
    fun generate(random: SecureRandom = SecureRandom()): PkceCodes {
        val bytes = ByteArray(64).also(random::nextBytes)
        val verifier = b64.encodeToString(bytes)
        return PkceCodes(verifier, challengeOf(verifier))
    }

    fun challengeOf(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Random URL-safe value for the OAuth `state` parameter. */
    fun state(random: SecureRandom = SecureRandom()): String =
        b64.encodeToString(ByteArray(32).also(random::nextBytes))
}
