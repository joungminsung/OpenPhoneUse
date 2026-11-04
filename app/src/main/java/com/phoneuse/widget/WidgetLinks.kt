package com.phoneuse.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.phoneuse.ui.LaunchTargets
import com.phoneuse.ui.LaunchToken
import com.phoneuse.ui.MainActivity

/**
 * A prompt a widget hands to the chat: [typed] is the user's own words (the quick-ask field), otherwise a chip or
 * tile (started like a result-card button, so it never authorizes a send by itself). [conversationId] continues
 * that conversation ("Follow up"); null starts a new one.
 */
data class WidgetPrompt(val text: String, val typed: Boolean, val conversationId: Long? = null)

/**
 * The intents behind widget taps. Every one that reaches an activity carries a [LaunchToken] over its payload,
 * like the notification intents: MainActivity is exported, and only our own PendingIntents can sign. Each target
 * gets its own data URI so two PendingIntents never merge their extras.
 */
object WidgetLinks {
    const val EXTRA_PROMPT = "com.phoneuse.extra.WIDGET_PROMPT"
    const val EXTRA_TYPED = "com.phoneuse.extra.WIDGET_TYPED"
    const val EXTRA_TARGET = "com.phoneuse.extra.WIDGET_TARGET"
    const val MAX_PROMPT = 1_000

    /** Opens the chat and starts [prompt] there, through the same path as text sent from the composer. */
    fun prompt(context: Context, prompt: WidgetPrompt): Intent {
        val text = prompt.text.trim().take(MAX_PROMPT)
        val payload = LaunchToken.widgetPrompt(prompt.typed, prompt.conversationId, text)
        return Intent(context, MainActivity::class.java)
            .setData(Uri.parse(LaunchTargets.uri(LaunchTargets.Kind.WIDGET, "prompt/" + Integer.toHexString(payload.hashCode()))))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PROMPT, text)
            .putExtra(EXTRA_TYPED, prompt.typed)
            .putExtra(EXTRA_TARGET, prompt.conversationId ?: -1L)
            .putExtra(LaunchToken.EXTRA, LaunchToken.of(context).sign(payload))
    }

    /** The verified prompt on an intent MainActivity received, or null (none, or not signed by us). */
    fun promptOf(context: Context, intent: Intent?): WidgetPrompt? {
        val text = intent?.getStringExtra(EXTRA_PROMPT) ?: return null
        return accept(
            LaunchToken.of(context), text, intent.getBooleanExtra(EXTRA_TYPED, false),
            intent.getLongExtra(EXTRA_TARGET, -1L), intent.getStringExtra(LaunchToken.EXTRA),
        )
    }

    /** Pure check behind [promptOf]: the signature must cover the text, the kind and the target. */
    fun accept(token: LaunchToken, text: String, typed: Boolean, target: Long, signature: String?): WidgetPrompt? {
        val conversation = target.takeIf { it >= 0 }
        if (text.isBlank() || text.length > MAX_PROMPT) return null
        if (!token.verify(LaunchToken.widgetPrompt(typed, conversation, text), signature)) return null
        return WidgetPrompt(text, typed, conversation)
    }

    /** The app on the conversation, or on its home screen when there is none. */
    fun open(context: Context, conversationId: Long?): Intent =
        if (conversationId != null) MainActivity.openIntent(context, conversationId) else MainActivity.screenIntent(context, com.phoneuse.notify.AppScreen.HOME)

    /** The compact quick-ask window in [mode]; [conversationId] for a follow-up. */
    fun quickAsk(context: Context, mode: QuickAskMode, conversationId: Long? = null): Intent {
        val action = quickAskAction(mode, conversationId)
        return Intent(context, QuickAskActivity::class.java)
            .setData(Uri.parse(LaunchTargets.uri(LaunchTargets.Kind.WIDGET, "quick-ask/${mode.wire}/${conversationId ?: -1}")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(QuickAskActivity.EXTRA_MODE, mode.wire)
            .putExtra(EXTRA_TARGET, conversationId ?: -1L)
            .putExtra(LaunchToken.EXTRA, LaunchToken.of(context).sign(LaunchToken.widgetAction(action)))
    }

    fun quickAskAction(mode: QuickAskMode, conversationId: Long?) = "quick-ask:${mode.wire}:${conversationId ?: -1}"
}

/** What the quick-ask window is for. */
enum class QuickAskMode(val wire: String) {
    ASK("ask"),
    VOICE("voice"),
    FOLLOW_UP("follow"),
    REMEMBER("remember"),
    ;

    companion object {
        fun fromWire(s: String?): QuickAskMode? = entries.firstOrNull { it.wire == s }
    }
}
