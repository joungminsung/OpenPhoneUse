package com.phoneuse.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretBox {
    fun seal(plain: String): String

    /** null when the value cannot be decrypted (bad format, key reset, tampering). */
    fun open(sealed: String): String?
}

/** Storage format: "v1:" + base64(iv) + ":" + base64(ciphertext). Pure, JVM-testable. */
object CipherCodec {
    private const val PREFIX = "v1:"

    fun encode(iv: ByteArray, ct: ByteArray): String {
        val enc = java.util.Base64.getEncoder()
        return PREFIX + enc.encodeToString(iv) + ":" + enc.encodeToString(ct)
    }

    fun decode(s: String): Pair<ByteArray, ByteArray>? {
        if (!s.startsWith(PREFIX)) return null
        val parts = s.removePrefix(PREFIX).split(":")
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        return try {
            val dec = java.util.Base64.getDecoder()
            dec.decode(parts[0]) to dec.decode(parts[1])
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** AES-256-GCM with a non-exportable AndroidKeyStore key. */
class KeystoreSecretBox(private val alias: String = "phoneuse_secrets") : SecretBox {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return CipherCodec.encode(cipher.iv, ct)
    }

    override fun open(sealed: String): String? {
        val (iv, ct) = CipherCodec.decode(sealed) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
