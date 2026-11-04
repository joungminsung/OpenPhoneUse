package com.phoneuse.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import com.phoneuse.R
import com.phoneuse.app.ChatCard
import com.phoneuse.app.QuietChannel
import com.phoneuse.auth.PlanConfig
import com.phoneuse.i18n.tr
import com.phoneuse.ui.LaunchTargets
import com.phoneuse.ui.LaunchToken
import com.phoneuse.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every notification OpenPhoneUse posts goes through here: one set of channels, one look (the app's mark as the
 * status-bar icon, the accent colour, a timestamp) and a tap target for each ([Tap]). Inside the quiet hours,
 * channels that may make a sound go to [QuietChannel] instead.
 */
object Notifier {
    private const val TAG = "Notifier"

    /** The app's accent (ui/theme: accent), used to tint the icon and the action buttons. */
    private const val ACCENT = 0xFFE4572E.toInt()

    /** The ids are the ones the app always used, so the user's per-channel settings survive. */
    enum class Channel(val id: String, val importance: Int, private val en: String, private val ko: String, val quietable: Boolean = true) {
        RESULTS("phoneuse_results", NotificationManager.IMPORTANCE_DEFAULT, "Task results", "작업 결과"),
        NEEDS_OK("phoneuse_needs_ok", NotificationManager.IMPORTANCE_HIGH, "Needs your OK", "확인 필요"),
        PROGRESS("phoneuse_progress", NotificationManager.IMPORTANCE_LOW, "Running task", "진행 중인 작업", quietable = false),
        ROUTINES("phoneuse_routines", NotificationManager.IMPORTANCE_DEFAULT, "Routines", "루틴"),
        DAILY_SUMMARY("phoneuse_daily_summary", NotificationManager.IMPORTANCE_DEFAULT, "Today's summary", "오늘 요약"),
        NUDGES("phoneuse_nudges", NotificationManager.IMPORTANCE_DEFAULT, "Smart nudges", "알아서 챙기기"),
        REMINDERS("phoneuse_reminders", NotificationManager.IMPORTANCE_HIGH, "Reminders", "알림", quietable = false),
        ;

        val label: String get() = tr(en, ko)
    }

    const val TAG_RESULT = "result"
    const val TAG_ROUTINE_RESULT = "routine_result"
    private const val TAG_NEEDS_OK = "needs_ok"
    private const val TAG_PROGRESS = "progress"
    private const val GROUP_RESULTS = "com.phoneuse.RESULTS"
    private const val RESULTS_SUMMARY_ID = 0
    private val retiredChannelGone = AtomicBoolean(false)

    /** Id of a result that has no conversation (a run that failed before it had one); never the group summary's. */
    const val NO_CONVERSATION_ID = Int.MIN_VALUE

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * A builder with the app's look on [channel] (created or renamed on the way): icon, accent and a timestamp.
     * [quiet]: inside the quiet hours, so it arrives without sound.
     */
    fun builder(context: Context, channel: Channel, quiet: Boolean = false): Notification.Builder {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Upgraded installs keep the old "Usage" channel, which nothing posts to any more.
        if (retiredChannelGone.compareAndSet(false, true)) nm?.deleteNotificationChannel("phoneuse_usage")
        nm?.createNotificationChannel(NotificationChannel(channel.id, channel.label, channel.importance))
        val id = if (nm != null && channel.quietable) QuietChannel.pick(nm, quiet, channel.id) else channel.id
        return Notification.Builder(context, id)
            .setSmallIcon(R.drawable.ic_stat_phoneuse)
            .setColor(ACCENT)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
    }

    /** Posts [notification]; false when notifications are off (or were turned off just now). */
    fun post(context: Context, tag: String?, id: Int, notification: Notification): Boolean {
        if (!canPost(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            nm.notify(tag, id, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notification refused")
            false
        }
    }

    fun cancel(context: Context, tag: String?, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag, id)
    }

    /** What tapping goes to, as a PendingIntent; each target is its own (LaunchTargets), so none overwrites another. */
    fun open(context: Context, tap: Tap): PendingIntent = when (tap) {
        is Tap.Conversation -> PendingIntent.getActivity(
            context,
            LaunchTargets.requestCode(LaunchTargets.Kind.CONVERSATION, tap.id),
            MainActivity.openIntent(context, tap.id, tap.reveal),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        is Tap.Screen -> PendingIntent.getActivity(
            context,
            LaunchTargets.requestCode(LaunchTargets.Kind.SCREEN, tap.screen.ordinal.toLong()),
            MainActivity.screenIntent(context, tap.screen),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ---- results ---------------------------------------------------------------------------------

    /**
     * A finished run: the conversation's title, the answer's first line, and the whole answer (trimmed, without
     * Markdown marks) when expanded. With a conversation it offers Open, Follow up (an inline reply sent to the same
     * conversation) and Copy. Results stack per conversation and group together. Returns false when not posted.
     * [planUsageLimit]: the run met the ChatGPT plan's usage limit, so Manage usage comes first, in Copy's place (the
     * shade shows three buttons).
     */
    fun result(
        context: Context,
        conversationId: Long?,
        title: String?,
        answer: String,
        quiet: Boolean,
        tag: String = TAG_RESULT,
        id: Int = conversationId?.toInt() ?: NO_CONVERSATION_ID,
        planUsageLimit: Boolean = false,
    ): Boolean {
        if (!canPost(context)) return false
        val plain = NotifyText.plain(answer).ifBlank { answer.trim() }
        val tap = open(context, Taps.result(conversationId))
        val builder = builder(context, Channel.RESULTS, quiet)
            .setContentTitle(NotifyText.title(title, "OpenPhoneUse"))
            .setContentText(NotifyText.firstLine(plain))
            .setStyle(Notification.BigTextStyle().bigText(NotifyText.clip(plain, NotifyText.BIG_CHARS)))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup(GROUP_RESULTS)
        if (planUsageLimit) builder.addAction(manageUsageAction(context))
        if (conversationId != null) {
            builder.addAction(Notification.Action.Builder(null as Icon?, tr("Open", "열기"), tap).build())
            builder.addAction(followUpAction(context, conversationId, tag, id))
            if (!planUsageLimit) {
                builder.addAction(
                    Notification.Action.Builder(
                        null as Icon?, tr("Copy", "복사"),
                        broadcast(context, NotifyActionReceiver.ACTION_COPY, conversationId.toString(), mutable = false) {
                            putExtra(NotifyActionReceiver.EXTRA_CONVERSATION, conversationId)
                        },
                    ).build(),
                )
            }
        }
        if (!post(context, tag, id, builder.build())) return false
        postResultsSummary(context, quiet)
        return true
    }

    /** Opens where the user sees and manages the ChatGPT plan usage this app spends. */
    fun manageUsageAction(context: Context): Notification.Action {
        val page = Intent(Intent.ACTION_VIEW, Uri.parse(PlanConfig.MANAGE_USAGE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, 0, page, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null as Icon?, tr("Manage usage", "사용량 관리"), pending).build()
    }

    /** An inline reply: the text the user types goes to [conversationId] as their next message. */
    private fun followUpAction(context: Context, conversationId: Long, tag: String, id: Int): Notification.Action {
        val input = RemoteInput.Builder(NotifyActionReceiver.KEY_REPLY)
            .setLabel(tr("Ask a follow-up", "이어서 물어보기"))
            .build()
        // Mutable, as RemoteInput requires: the system fills in the typed text. The token signs the conversation,
        // so a filled-in intent cannot point the reply at another one.
        // Its own PendingIntent per notification (tag + id), so two results of one conversation do not share extras.
        val pending = broadcast(context, NotifyActionReceiver.ACTION_FOLLOW_UP, conversationId.toString(), mutable = true, unique = "$tag/$id") {
            putExtra(NotifyActionReceiver.EXTRA_CONVERSATION, conversationId)
            putExtra(NotifyActionReceiver.EXTRA_TAG, tag)
            putExtra(NotifyActionReceiver.EXTRA_ID, id)
        }
        return Notification.Action.Builder(null as Icon?, tr("Follow up", "이어서 묻기"), pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    /** The group's header once two or more results are showing; Android draws a lone result without one. */
    private fun postResultsSummary(context: Context, quiet: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val shown = try {
            nm.activeNotifications.count { it.notification.group == GROUP_RESULTS && it.id != RESULTS_SUMMARY_ID }
        } catch (e: Exception) {
            0
        }
        if (shown < 2) return
        val summary = builder(context, Channel.RESULTS, quiet)
            .setContentTitle(tr("$shown results", "결과 ${shown}개"))
            .setContentText(tr("$shown tasks finished", "작업 ${shown}개가 끝났어요"))
            .setStyle(Notification.InboxStyle().setSummaryText(tr("Results", "작업 결과")))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open(context, Tap.Screen(AppScreen.HOME)))
            .setGroup(GROUP_RESULTS)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .build()
        post(context, TAG_RESULT, RESULTS_SUMMARY_ID, summary)
    }

    /** Takes a result away (its follow-up was sent: the new run brings its own). */
    fun cancelResult(context: Context, tag: String, id: Int) = cancel(context, tag, id)

    /**
     * The reply typed in the shade is waiting for the running task: the result stays (and its reply spinner stops)
     * with a note, instead of vanishing with the text.
     */
    fun markReplyQueued(context: Context, tag: String, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            val sbn = nm.activeNotifications.firstOrNull { it.id == id && it.tag == tag } ?: return
            val updated = Notification.Builder.recoverBuilder(context, sbn.notification)
                .setSubText(tr("Reply queued: sends when the current task ends", "답장 대기 중: 지금 작업이 끝나면 보내요"))
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(tag, id, updated)
        } catch (e: Exception) {
            Log.w(TAG, "marking the reply queued failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- needs your OK ---------------------------------------------------------------------------

    /**
     * A card waiting in the chat while nobody is looking. A confirmation gets Approve and Deny (Approve only for
     * sends and changes, and only after unlocking: money, security and delete get "Review", which opens the card
     * in the app; see [NeedsOk.inlineApprove]). A question with a few options gets one button per option.
     */
    fun needsOk(context: Context, card: ChatCard, conversationId: Long?, title: String?, quiet: Boolean): Boolean {
        if (!canPost(context)) return false
        val key = NeedsOk.key(card)
        val tap = open(context, Taps.needsOk(conversationId))
        val text = NotifyText.plain(NeedsOk.text(card))
        val heading = when (card) {
            is ChatCard.Confirm -> tr("Needs your OK", "확인이 필요해요")
            is ChatCard.Ask -> tr("Question for you", "물어볼 게 있어요")
            is ChatCard.HandOff -> tr("Your turn", "직접 해 주세요")
        }
        val builder = builder(context, Channel.NEEDS_OK, quiet)
            .setContentTitle(title?.let { "$heading · ${NotifyText.title(it, "")}" } ?: heading)
            .setContentText(NotifyText.firstLine(text))
            .setStyle(Notification.BigTextStyle().bigText(NotifyText.clip(text, NotifyText.BIG_CHARS)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        when (card) {
            is ChatCard.Confirm -> {
                if (NeedsOk.inlineApprove(card.category)) {
                    builder.addAction(answerAction(context, tr("Approve", "허용"), NotifyActionReceiver.ACTION_APPROVE, key, null))
                } else {
                    // Money, security, delete: approving takes the whole card in the app, never a shade button.
                    builder.addAction(Notification.Action.Builder(null as Icon?, tr("Review", "확인하러 가기"), tap).build())
                }
                builder.addAction(answerAction(context, tr("Deny", "거부"), NotifyActionReceiver.ACTION_DENY, key, null))
            }
            is ChatCard.Ask -> {
                val options = NeedsOk.options(card)
                options.forEachIndexed { i, option ->
                    builder.addAction(answerAction(context, NotifyText.clip(option, 24), NotifyActionReceiver.ACTION_OPTION, key, i))
                }
                if (options.isEmpty()) builder.addAction(Notification.Action.Builder(null as Icon?, tr("Open", "열기"), tap).build())
            }
            is ChatCard.HandOff -> builder.addAction(Notification.Action.Builder(null as Icon?, tr("Open", "열기"), tap).build())
        }
        return post(context, TAG_NEEDS_OK, 0, builder.build())
    }

    fun cancelNeedsOk(context: Context) = cancel(context, TAG_NEEDS_OK, 0)

    private fun answerAction(context: Context, label: String, action: String, key: String, option: Int?): Notification.Action {
        val pending = broadcast(context, action, option?.let { "$key-$it" } ?: key, mutable = false) {
            putExtra(NotifyActionReceiver.EXTRA_KEY, key)
            if (option != null) putExtra(NotifyActionReceiver.EXTRA_OPTION, option)
        }
        val builder = Notification.Action.Builder(null as Icon?, label, pending)
        // Answering from the lock screen asks to unlock first; the platform enforces it from Android 12.
        if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true)
        return builder.build()
    }

    // ---- running task ----------------------------------------------------------------------------

    /**
     * The run in progress, while the app is not in front: the task's title, the step it is on (the overlay's
     * progress lines), an indeterminate bar, the time since it started, and Stop. Silent; updated in place.
     */
    fun progress(context: Context, conversationId: Long?, title: String, line: String, steps: Int, startedAtWall: Long): Boolean {
        if (!canPost(context)) return false
        val builder = builder(context, Channel.PROGRESS)
            .setContentTitle(NotifyText.title(title, tr("Working", "작업 중")))
            .setContentText(NotifyText.clip(NotifyText.plain(line), NotifyText.LINE_CHARS))
            .setSubText(if (steps > 0) tr("Step $steps", "${steps}단계") else null)
            .setProgress(0, 0, true)
            .setWhen(startedAtWall)
            .setUsesChronometer(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, conversationId?.let { Tap.Conversation(it) } ?: Tap.Screen(AppScreen.HOME)))
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, tr("Stop", "중단"),
                    broadcast(context, NotifyActionReceiver.ACTION_STOP, "run", mutable = false) { },
                ).build(),
            )
        return post(context, TAG_PROGRESS, 0, builder.build())
    }

    fun cancelProgress(context: Context) = cancel(context, TAG_PROGRESS, 0)

    // ---- buttons ---------------------------------------------------------------------------------

    /**
     * A button handled by [NotifyActionReceiver]: explicit, its own data URI per action and [key], and signed with
     * [LaunchToken] like every intent that starts something. Immutable unless it carries a RemoteInput.
     */
    private fun broadcast(context: Context, action: String, key: String, mutable: Boolean, unique: String? = null, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, NotifyActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse(LaunchTargets.uri(LaunchTargets.Kind.NOTIFY_ACTION, "$action/$key" + (unique?.let { "/$it" } ?: ""))))
            .apply(extras)
            .putExtra(LaunchToken.EXTRA, LaunchToken.of(context).sign(LaunchToken.notifyAction(action, key)))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
