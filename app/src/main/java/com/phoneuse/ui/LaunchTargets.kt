package com.phoneuse.ui

import android.content.Context
import com.phoneuse.security.KeystoreSecretBox
import com.phoneuse.security.SecretBox
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Identity of the PendingIntents that open the app. Android treats intents that differ only in extras as one
 * PendingIntent, and FLAG_UPDATE_CURRENT then overwrites the extras: so each target gets its own data URI, and
 * each kind its own request-code range.
 */
object LaunchTargets {
    enum class Kind(val path: String) {
        CONVERSATION("conversation"),
        ROUTINE("routine"),
        REMINDER("reminder"),
        ROUTINES_BROKEN("routines-broken"),
        SUMMARY("summary"),
        NUDGE("nudge"),
        SCREEN("screen"),
        NOTIFY_ACTION("notify"),
        WIDGET("widget"),
    }

    fun uri(kind: Kind, key: String): String = "phoneuse://${kind.path}/$key"

    /** The kind in the top bits, the id below; ids past 2^24 wrap, which the data URI still tells apart. */
    fun requestCode(kind: Kind, id: Long): Int = (kind.ordinal shl 24) or (id and 0xFFFFFF).toInt()
}

/**
 * Proves an intent to the exported MainActivity came from one of our own PendingIntents: another app can put
 * a nudge or conversation extra on an intent, but cannot sign it. The key is random per install and stored sealed
 * with the Keystore (SecretBox), like every other secret of the app.
 */
class LaunchToken(key: ByteArray) {
    private val spec = SecretKeySpec(key, ALGORITHM)

    fun sign(payload: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(spec) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
    }

    fun verify(payload: String, token: String?): Boolean =
        token != null && MessageDigest.isEqual(sign(payload).toByteArray(), token.toByteArray())

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val PREFS = "launch_token"
        private const val KEY = "sealed_key"
        private const val KEY_BYTES = 32
        private const val KEYSTORE_ALIAS = "phoneuse_launch_token"
        const val EXTRA = "com.phoneuse.extra.LAUNCH_TOKEN"

        fun conversation(id: Long) = "conversation:$id"

        fun nudge(id: String, action: Int) = "nudge:$id:$action"

        /** An app screen a notification opens (notify/AppScreen). */
        fun screen(name: String) = "screen:$name"

        /** A notification button handled by NotifyActionReceiver: the action and what it acts on. */
        fun notifyAction(action: String, key: String) = "notify:$action:$key"

        /** A home-screen widget's prompt: [typed] (the user's words) or a chip/tile, to [conversationId] or a new chat. */
        fun widgetPrompt(typed: Boolean, conversationId: Long?, text: String) =
            "widget-prompt:${if (typed) "typed" else "card"}:${conversationId ?: -1}:$text"

        /** A widget button that acts without opening the app ("stop", "routine:<id>", "quick-ask:<mode>:<id>"). */
        fun widgetAction(action: String) = "widget-action:$action"

        @Volatile private var instance: LaunchToken? = null

        /**
         * The key from its [stored] sealed form; when there is none or it no longer opens (Keystore reset, an old
         * plain value), a [fresh] one. The second value is what to store, or null when [stored] is still good.
         */
        fun loadKey(stored: String?, box: SecretBox, fresh: () -> ByteArray): Pair<ByteArray, String?> {
            val opened = stored?.let { box.open(it) }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            if (opened != null && opened.size == KEY_BYTES) return opened to null
            val key = fresh()
            return key to box.seal(Base64.getEncoder().encodeToString(key))
        }

        /** This install's token signer (key created on first use, kept sealed in private preferences). */
        fun of(context: Context): LaunchToken = instance ?: synchronized(this) {
            instance ?: run {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val (key, toStore) = loadKey(prefs.getString(KEY, null), KeystoreSecretBox(KEYSTORE_ALIAS)) {
                    ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
                }
                // Asynchronous: the key is already in memory for this process.
                if (toStore != null) prefs.edit().putString(KEY, toStore).apply()
                LaunchToken(key).also { instance = it }
            }
        }
    }
}
