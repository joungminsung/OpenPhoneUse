package com.phoneuse.phone

import com.phoneuse.i18n.tr
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class Contact(val name: String, val phones: List<String>, val emails: List<String>)

/** The phone's contacts; Android's is [AndroidContactsSource]. */
interface ContactsSource {
    fun hasPermission(): Boolean

    /** Contacts whose name contains [name], at most [limit]. */
    fun search(name: String, limit: Int): List<Contact>
}

/** find_contact. The user's own address book, so its output is not third-party text. */
class ContactsTool(private val source: ContactsSource) {
    fun find(name: String): PhoneReply {
        val query = name.replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty()) return PhoneReply.Failed("name required")
        if (!source.hasPermission()) return PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)
        val hits = try {
            source.search(query, MAX)
        } catch (e: SecurityException) {
            // Revoked between the check and the read.
            return PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)
        }
        if (hits.isEmpty()) return PhoneReply.Ok("결과 없음: '$query' 이름의 연락처가 없어요.")
        return PhoneReply.Ok(
            buildJsonArray {
                for (c in hits.take(MAX)) {
                    add(
                        buildJsonObject {
                            put("name", c.name)
                            putJsonArray("phones") { c.phones.take(MAX_EACH).forEach { add(it) } }
                            putJsonArray("emails") { c.emails.take(MAX_EACH).forEach { add(it) } }
                        },
                    )
                }
            }.toString(),
        )
    }

    companion object {
        val LABEL get() = tr("Contacts", "연락처")
        val PERMISSIONS = listOf("android.permission.READ_CONTACTS")
        const val MAX = 10
        private const val MAX_EACH = 5
    }
}
