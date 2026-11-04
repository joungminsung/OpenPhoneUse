package com.phoneuse.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.phoneuse.app.AppGraph
import com.phoneuse.app.Source
import com.phoneuse.chat.MessageRole
import com.phoneuse.i18n.tr
import com.phoneuse.ui.LaunchToken
import com.phoneuse.ui.chat.copyToClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The buttons of OpenPhoneUse's notifications ([Notifier]). Not exported, and every intent must carry a valid
 * [LaunchToken] for its action and key as well, so only our own PendingIntents act.
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val token = intent.getStringExtra(LaunchToken.EXTRA)
        fun signed(key: String) = LaunchToken.of(context).verify(LaunchToken.notifyAction(action, key), token)
        when (action) {
            ACTION_STOP -> if (signed("run")) AppGraph.runner.stop()
            ACTION_APPROVE, ACTION_DENY, ACTION_OPTION -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val option = intent.getIntExtra(EXTRA_OPTION, -1)
                if (!signed(if (action == ACTION_OPTION) "$key-$option" else key)) return
                val button = when (action) {
                    ACTION_APPROVE -> NeedsOk.Button.Approve
                    ACTION_DENY -> NeedsOk.Button.Deny
                    else -> NeedsOk.Button.Option(option)
                }
                // Answered through the chat card's own path; a card that is gone or changed is left alone.
                if (!AppGraph.runner.answerFromNotification(key, button)) {
                    Notifier.cancelNeedsOk(context)
                    toast(context, tr("That request is no longer waiting", "이미 끝난 요청이에요"))
                }
            }
            ACTION_FOLLOW_UP -> followUp(context, intent, ::signed)
            ACTION_COPY -> {
                val id = intent.getLongExtra(EXTRA_CONVERSATION, -1L).takeIf { it >= 0 } ?: return
                if (!signed(id.toString())) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        // The newest answer is read from the conversation, so the intent never carries reply text.
                        val answer = AppGraph.chats.messages(id).first().lastOrNull { it.role == MessageRole.ASSISTANT }?.text
                        if (answer != null) withContext(Dispatchers.Main) { copyToClipboard(context, NotifyText.plain(answer)) }
                    } catch (e: Exception) {
                        Log.w(TAG, "copy failed: ${e.javaClass.simpleName}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    /**
     * The inline reply: the user typed it in the shade, so it is sent like a message typed in the app (a send it
     * names still follows the usual rules; money, security and delete are always confirmed on a card).
     */
    private fun followUp(context: Context, intent: Intent, signed: (String) -> Boolean) {
        val id = intent.getLongExtra(EXTRA_CONVERSATION, -1L).takeIf { it >= 0 } ?: return
        if (!signed(id.toString())) return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        val tag = intent.getStringExtra(EXTRA_TAG) ?: Notifier.TAG_RESULT
        val notificationId = intent.getIntExtra(EXTRA_ID, id.toInt())
        if (text.isNullOrEmpty()) {
            Notifier.cancelResult(context, tag, notificationId)
            return
        }
        if (AppGraph.runner.send(id, text, Source.APP)) {
            // The new run brings its own result.
            Notifier.cancelResult(context, tag, notificationId)
            toast(context, tr("Sent. You'll get the answer here", "보냈어요. 답이 오면 알려 드릴게요"))
            return
        }
        // Another task is running: keep the result and the typed text, and send it as soon as the runner is free.
        Notifier.markReplyQueued(context, tag, notificationId)
        val app = context.applicationContext
        queueScope.launch {
            val sent = withTimeoutOrNull(QUEUE_WAIT_MS) {
                AppGraph.runner.running.first { !it }
                AppGraph.runner.send(id, text, Source.APP)
            } == true
            if (sent) {
                Notifier.cancelResult(app, tag, notificationId)
            } else {
                toast(app, tr("Couldn't send your reply (busy). Reply from the app", "답장을 보내지 못했어요. 앱에서 보내 주세요"))
            }
        }
    }

    private fun toast(context: Context, text: String) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "NotifyActions"
        private const val QUEUE_WAIT_MS = 10 * 60_000L
        private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val ACTION_STOP = "com.phoneuse.notify.STOP"
        const val ACTION_APPROVE = "com.phoneuse.notify.APPROVE"
        const val ACTION_DENY = "com.phoneuse.notify.DENY"
        const val ACTION_OPTION = "com.phoneuse.notify.OPTION"
        const val ACTION_FOLLOW_UP = "com.phoneuse.notify.FOLLOW_UP"
        const val ACTION_COPY = "com.phoneuse.notify.COPY"
        const val EXTRA_KEY = "com.phoneuse.extra.CARD_KEY"
        const val EXTRA_OPTION = "com.phoneuse.extra.OPTION"
        const val EXTRA_CONVERSATION = "com.phoneuse.extra.CONVERSATION"
        const val EXTRA_TAG = "com.phoneuse.extra.NOTIFICATION_TAG"
        const val EXTRA_ID = "com.phoneuse.extra.NOTIFICATION_ID"
        const val KEY_REPLY = "follow_up"
    }
}
