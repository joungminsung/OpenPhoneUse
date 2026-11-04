package com.phoneuse.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Reads JWT payload claims without verifying the signature; [JwtSignature] does that. */
object JwtClaims {
    /** Every payload claim, unverified (see the object comment); null when [jwt] is not a JWT. */
    fun claims(jwt: String): JsonObject? = payload(jwt)

    private fun payload(jwt: String): JsonObject? {
        val parts = jwt.split('.')
        if (parts.size != 3) return null
        return try {
            Json.parseToJsonElement(base64Url(parts[1]).toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            null
        }
    }
}

/** RS256 only, the one algorithm OpenAI's OpenID configuration lists: `none` or a symmetric `alg` never passes. */
object JwtSignature {
    /** Whether [jwt] is signed by the key of [jwks] its `kid` names. Anything malformed is simply false. */
    fun verifies(jwt: String, jwks: JsonObject): Boolean = try {
        val parts = jwt.split('.')
        val header = Json.parseToJsonElement(base64Url(parts[0]).toString(Charsets.UTF_8)).jsonObject
        val keys = (jwks["keys"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.text("kty") == "RSA" }
        val kid = header.text("kid")
        val key = if (kid != null) keys.firstOrNull { it.text("kid") == kid } else keys.singleOrNull()
        if (parts.size != 3 || header.text("alg") != "RS256" || key == null) {
            false
        } else {
            val modulus = BigInteger(1, base64Url(key.text("n")!!))
            val exponent = BigInteger(1, base64Url(key.text("e")!!))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)))
                update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
                verify(base64Url(parts[2]))
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}

/** java.util.Base64's URL decoder rejects padding-free input of some lengths, so re-pad first. */
private fun base64Url(segment: String): ByteArray {
    val seg = segment.trimEnd('=')
    return Base64.getUrlDecoder().decode(seg + "=".repeat((4 - seg.length % 4) % 4))
}
