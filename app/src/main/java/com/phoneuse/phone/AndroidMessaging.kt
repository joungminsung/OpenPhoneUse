package com.phoneuse.phone

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.phoneuse.agent.MessagingAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** The Android side of [MessagingAccess]. */
object AndroidMessaging {
    fun access(context: Context): MessagingAccess {
        val app = context.applicationContext
        return MessagingAccess(
            NotificationTool(AndroidNotificationSource(app, NotificationStore.get(app))),
            SmsTool(AndroidSmsSource(app), AndroidContactsSource(app)),
        )
    }
}

/** The listener's history in chat.db; dismissing also clears the shade while the listener is connected. */
class AndroidNotificationSource(private val context: Context, private val store: NotificationStore) : NotificationSource {
    override fun accessEnabled() = PhoneUseNotificationListener.enabled(context)

    override suspend fun recent(hours: Int) = store.recent(hours)

    override suspend fun dismiss(keys: List<String>) {
        store.remove(keys)
        PhoneUseNotificationListener.cancel(keys)
    }
}

/** Telephony's SMS and MMS providers (inbox and sent) and [SmsManager]. */
class AndroidSmsSource(private val context: Context) : SmsSource {
    override fun canRead() = granted(Manifest.permission.READ_SMS)

    override fun canSend() = granted(Manifest.permission.SEND_SMS)

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun query(since: Long, query: String?, limit: Int): List<SmsMessage> = withContext(Dispatchers.IO) {
        val sms = smsRows(since, query, limit)
        // Long texts (LMS: courier and bank notices) and MMS live in the MMS provider.
        val mms = try {
            mmsRows(since, query, limit)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AndroidSmsSource", "MMS query failed", e)
            emptyList()
        }
        MmsMerge.merge(sms, mms, since, query, limit)
    }

    private fun smsRows(since: Long, query: String?, limit: Int): List<SmsMessage> {
        val where = StringBuilder("${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} IN (?, ?)")
        val args = mutableListOf(
            since.toString(),
            Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
            Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
        )
        if (query != null) {
            val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where.append(" AND (${Telephony.Sms.BODY} LIKE ? ESCAPE '\\' OR ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\')")
            args += like
            args += like
        }
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            where.toString(),
            args.toTypedArray(),
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            buildList {
                while (size < limit && c.moveToNext()) {
                    add(
                        SmsMessage(
                            address = c.getString(0).orEmpty(),
                            body = c.getString(1).orEmpty(),
                            at = c.getLong(2),
                            incoming = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /**
     * MMS rows since [since] (the provider's date is in seconds), newest first, with their text/plain parts joined.
     * The query runs on the joined text ([MmsMerge.matches]), so up to [MMS_SCAN] rows are read.
     */
    private fun mmsRows(since: Long, query: String?, limit: Int): List<SmsMessage> {
        val rows = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            "${Telephony.Mms.DATE} >= ? AND ${Telephony.Mms.MESSAGE_BOX} IN (?, ?)",
            arrayOf((since / 1000).toString(), MmsMerge.BOX_INBOX.toString(), MmsMerge.BOX_SENT.toString()),
            "${Telephony.Mms.DATE} DESC",
        )?.use { c ->
            buildList { while (size < MMS_SCAN && c.moveToNext()) add(Triple(c.getLong(0), c.getLong(1), c.getInt(2))) }
        }.orEmpty()
        val out = ArrayList<SmsMessage>()
        for ((id, dateSec, box) in rows) {
            val parts = context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf(Telephony.Mms.Part.TEXT),
                "${Telephony.Mms.Part.MSG_ID} = ? AND ${Telephony.Mms.Part.CONTENT_TYPE} = ?",
                arrayOf(id.toString(), "text/plain"),
                null,
            )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.orEmpty()
            val text = MmsMerge.message(dateSec, box, null, parts) ?: continue
            // The address is a provider query of its own: only for a row that can still match.
            if (query != null && !MmsMerge.matches(text, query) && query.none { it.isDigit() || it == '+' }) continue
            val m = text.copy(address = mmsAddress(id, box)?.trim().orEmpty())
            if (MmsMerge.matches(m, query)) out += m
            if (out.size >= limit) break
        }
        return out
    }

    /** The sender (inbox) or the recipient (sent) of MMS [id]. */
    private fun mmsAddress(id: Long, box: Int): String? = context.contentResolver.query(
        Uri.parse("content://mms/$id/addr"),
        arrayOf(Telephony.Mms.Addr.ADDRESS),
        "${Telephony.Mms.Addr.TYPE} = ?",
        arrayOf((if (box == MmsMerge.BOX_INBOX) PDU_FROM else PDU_TO).toString()),
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /**
     * Sends and waits up to [SENT_WAIT_MS] for the radio's result, so "보냈어요" is not said with no SIM, no service
     * or airplane mode: a failure is an IOException. No answer in time counts as sent.
     */
    override suspend fun send(to: String, body: String) {
        val manager = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            ) ?: throw IOException("문자를 보낼 수 없는 기기예요")
        val parts = manager.divideMessage(body)
        val action = "${context.packageName}.SMS_SENT.${System.nanoTime()}"
        val results = Channel<Int>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList<PendingIntent>()
            for (i in 0 until maxOf(1, parts.size)) {
                sent += PendingIntent.getBroadcast(
                    context,
                    i,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            }
            withContext(Dispatchers.IO) {
                if (parts.size <= 1) {
                    manager.sendTextMessage(to, null, body, sent[0], null)
                } else {
                    manager.sendMultipartTextMessage(to, null, parts, sent, null)
                }
            }
            val codes = ArrayList<Int>()
            withTimeoutOrNull(SENT_WAIT_MS) { repeat(sent.size) { codes += results.receive() } }
            val failed = codes.firstOrNull { it != Activity.RESULT_OK }
            if (failed != null) {
                val ok = codes.count { it == Activity.RESULT_OK }
                // Some parts of a long text may already be out: a resend would deliver them twice.
                throw IOException(if (ok > 0) "${sendError(failed)}, ${sent.size}개 중 ${ok}개 부분은 이미 보냈을 수 있어요. 다시 보내기 전에 사용자에게 물어보세요" else sendError(failed))
            }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
        }
    }

    private fun sendError(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "서비스 지역이 아니에요"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "비행기 모드이거나 통신이 꺼져 있어요"
        else -> "전송 실패 ($code)"
    }

    private companion object {
        /** PduHeaders FROM and TO in the MMS addr table. */
        const val PDU_FROM = 137
        const val PDU_TO = 151
        const val MMS_SCAN = 200
        const val SENT_WAIT_MS = 15_000L
    }
}
