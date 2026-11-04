package com.phoneuse.auth

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private val enc = Base64.getUrlEncoder().withoutPadding()

/** Builds an unsigned JWT with the given JSON payload (base64url, no padding). */
fun fakeJwt(payload: String): String =
    enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
        enc.encodeToString(payload.toByteArray()) + ".sig"

fun rsaKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** A JWT signed with SHA256withRSA by [key], whatever [alg] its header claims; a null [kid] leaves it out of the header. */
fun signedJwt(payload: String, key: PrivateKey, kid: String? = "k1", alg: String = "RS256"): String {
    val kidField = if (kid != null) ",\"kid\":\"$kid\"" else ""
    val header = "{\"alg\":\"$alg\",\"typ\":\"JWT\"$kidField}"
    val signingInput = enc.encodeToString(header.toByteArray()) + "." + enc.encodeToString(payload.toByteArray())
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(key)
        update(signingInput.toByteArray())
        sign()
    }
    return signingInput + "." + enc.encodeToString(signature)
}

/** A JWKS document holding [keys] by kid. */
fun jwksOf(vararg keys: Pair<String, KeyPair>): String = keys.joinToString(",", "{\"keys\":[", "]}") { (kid, pair) ->
    val pub = pair.public as RSAPublicKey
    val n = enc.encodeToString(pub.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
    val e = enc.encodeToString(pub.publicExponent.toByteArray())
    "{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"$kid\",\"n\":\"$n\",\"e\":\"$e\"}"
}
