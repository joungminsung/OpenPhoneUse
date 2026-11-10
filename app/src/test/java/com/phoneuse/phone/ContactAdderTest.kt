package com.phoneuse.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactAdderTest {
    private class Writer(var writable: Boolean = true, var saves: Boolean = true, var failure: Exception? = null) : ContactWriter {
        val inserted = mutableListOf<Triple<String, String, String?>>()
        override fun canWrite() = writable
        override fun insert(name: String, phone: String, email: String?): Boolean {
            failure?.let { throw it }
            if (saves) inserted += Triple(name, phone, email)
            return saves
        }
    }

    private class Book(var readable: Boolean = true) : ContactsSource {
        val asked = mutableListOf<String>()
        override fun hasPermission() = readable
        override fun search(name: String, limit: Int): List<Contact> {
            asked += name
            return listOf(Contact("민수", listOf("02-555-0000", "010-1234-5678"), emptyList())).filter { name in it.name }
        }
    }

    private val writer = Writer()
    private val book = Book()
    private val adder = ContactAdder(writer, book)
    private var allow = true
    private val cards = mutableListOf<Pair<String, String>>()

    private fun add(name: String, phone: String, email: String? = null) = runBlocking {
        adder.add(name, phone, email) { number, summary ->
            cards += number to summary
            allow
        }
    }

    @Test
    fun badValuesAskNothing() {
        // Checked before the permission, so a wrong call is not answered with a permission card.
        writer.writable = false
        assertTrue(add("  \n ", "010-1234-5678") is PhoneReply.Failed)
        assertTrue(add("가".repeat(ContactAdder.MAX_NAME + 1), "010-1234-5678") is PhoneReply.Failed)
        assertTrue(add("지현", "지현이 번호") is PhoneReply.Failed)
        assertTrue(add("지현", "12") is PhoneReply.Failed)
        assertTrue(add("지현", "010-9876-5432", "jh.example.com") is PhoneReply.Failed)
        assertTrue(cards.isEmpty())
        assertTrue(book.asked.isEmpty())
        assertTrue(writer.inserted.isEmpty())
    }

    @Test
    fun returnsPermissionNeededWhenDenied() {
        writer.writable = false
        val r = add("지현", "010-9876-5432")
        assertTrue(r.text, r.text.startsWith("권한 필요: 연락처 저장"))
        assertEquals(listOf("android.permission.WRITE_CONTACTS"), (r as PhoneReply.PermissionNeeded).permissions)
        assertTrue(cards.isEmpty())
        assertTrue(writer.inserted.isEmpty())
    }

    @Test
    fun sameNameAndNumberIsAlreadySaved() {
        val r = add("민수", "010 1234 5678")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("이미 저장됨"))
        assertEquals(listOf("민수"), book.asked)
        assertTrue(cards.isEmpty())
        assertTrue(writer.inserted.isEmpty())
        // The same name with another number is a new save.
        assertTrue(add("민수", "010-2222-3333").text.startsWith("OK"))
        assertEquals(listOf(Triple("민수", "01022223333", null)), writer.inserted)
    }

    @Test
    fun sameNumberAllowsACountryCode() {
        assertTrue(ContactAdder.sameNumber("01012345678", "01012345678"))
        assertTrue(ContactAdder.sameNumber("+821012345678", "01012345678"))
        assertTrue(ContactAdder.sameNumber("01012345678", "+821012345678"))
        assertFalse(ContactAdder.sameNumber("01012345678", "01012345679"))
        assertFalse(ContactAdder.sameNumber("+821012345678", "+811012345678"))
        // Too short to be the same line by its ending alone.
        assertFalse(ContactAdder.sameNumber("+82105678", "5678"))
    }

    @Test
    fun unreadableContactsSkipTheDuplicateCheck() {
        book.readable = false
        assertTrue(add("민수", "010-1234-5678").text.startsWith("OK"))
        assertTrue(book.asked.isEmpty())
        assertEquals(1, cards.size)
        // No contacts source at all works the same way.
        val bare = Writer()
        val r = runBlocking { ContactAdder(bare).add("민수", "010-1234-5678", null) { _, _ -> true } }
        assertTrue(r.text, r.text.startsWith("OK"))
        assertEquals(1, bare.inserted.size)
    }

    @Test
    fun declinedCardSavesNothing() {
        allow = false
        assertTrue(add("지현", "010-9876-5432") is PhoneReply.Cancelled)
        assertTrue(writer.inserted.isEmpty())
        // The card names the contact and the number that would be saved.
        assertEquals(listOf("01098765432" to "\uE004연락처 저장 → 지현\n01098765432"), cards)
        assertEquals(ContactAdder.confirmSummary("지현", "01098765432"), cards[0].second)
    }

    @Test
    fun acceptedCardSavesTheNormalizedNumber() {
        val r = add("  김   지현 ", "+82 10-9876-5432", " jh@example.com ")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("OK") && r.text.contains("김 지현"))
        assertEquals(listOf(Triple("김 지현", "+821098765432", "jh@example.com")), writer.inserted)
        assertEquals("+821098765432", cards.single().first)
        // The card shows the address that would be saved too.
        assertEquals(ContactAdder.confirmSummary("김 지현", "+821098765432", "jh@example.com"), cards.single().second)
        // A blank email is no email.
        add("수아", "010-1111-2222", "  ")
        assertEquals(Triple("수아", "01011112222", null), writer.inserted.last())
    }

    @Test
    fun failedInsertIsReported() {
        writer.saves = false
        assertTrue(add("지현", "010-9876-5432") is PhoneReply.Failed)
        writer.saves = true
        writer.failure = IllegalStateException("provider died")
        assertTrue(add("지현", "010-9876-5432") is PhoneReply.Failed)
        // Revoked between the check and the write.
        writer.failure = SecurityException("revoked")
        assertTrue(add("지현", "010-9876-5432") is PhoneReply.PermissionNeeded)
        assertTrue(writer.inserted.isEmpty())
    }
}
