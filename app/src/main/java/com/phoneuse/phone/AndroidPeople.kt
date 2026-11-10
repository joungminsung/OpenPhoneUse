package com.phoneuse.phone

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract

private fun Context.holds(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** The system call log (CallLog.Calls). */
class AndroidCallLogSource(private val context: Context) : CallLogSource {
    override fun canRead() = context.holds(Manifest.permission.READ_CALL_LOG)

    override fun recent(since: Long, kinds: Set<CallKind>?, limit: Int): List<CallEntry> {
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val out = ArrayList<CallEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, "${CallLog.Calls.DATE} >= ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            // The kind is filtered here, not in SQL: the log is short and several type values share a kind.
            while (out.size < limit && c.moveToNext()) {
                val kind = kind(c.getInt(3))
                if (kinds != null && kind !in kinds) continue
                val number = c.getString(1)?.trim().orEmpty()
                val shown = c.isNull(2) || c.getInt(2) == CallLog.Calls.PRESENTATION_ALLOWED
                // Older rows mark a hidden caller with a negative number ("-1", "-2") instead.
                val hidden = !shown || number.isEmpty() || number.startsWith("-")
                out += CallEntry(
                    name = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() },
                    number = if (hidden) "" else number,
                    kind = kind,
                    at = c.getLong(4),
                    durationSec = c.getLong(5),
                )
            }
        }
        return out
    }

    private fun kind(type: Int): CallKind = when (type) {
        // Answered on another device of the same account: still a call that was taken.
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallKind.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
        CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
        CallLog.Calls.VOICEMAIL_TYPE -> CallKind.VOICEMAIL
        CallLog.Calls.REJECTED_TYPE -> CallKind.REJECTED
        CallLog.Calls.BLOCKED_TYPE -> CallKind.BLOCKED
        else -> CallKind.OTHER
    }
}

/** New contacts through ContactsContract, kept on the phone itself (no account). */
class AndroidContactWriter(private val context: Context) : ContactWriter {
    override fun canWrite() = context.holds(Manifest.permission.WRITE_CONTACTS)

    override fun insert(name: String, phone: String, email: String?): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        ops += data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        ops += data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build()
        if (email != null) {
            ops += data(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                .build()
        }
        // One batch: the contact is saved whole or not at all.
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return results.isNotEmpty() && results[0].uri != null
    }

    /** A data row of [mimeType] for the raw contact the batch's first operation inserts. */
    private fun data(mimeType: String): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)
}
