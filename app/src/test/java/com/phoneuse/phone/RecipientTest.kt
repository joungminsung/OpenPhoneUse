package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientTest {
    private var allowed = true
    private var throws = false
    private val book = listOf(
        Contact("김민수", listOf("010-1234-5678"), emptyList()),
        Contact("박민수", listOf("010 9999 8888"), emptyList()),
        Contact("민", listOf("010-7777-0000"), emptyList()),
        Contact("이지은", listOf("010-2222-3333", "02-555-0000"), emptyList()),
        // The same number saved twice in two spellings is one number.
        Contact("최수진", listOf("010-4444-5555", "01044445555"), emptyList()),
        // And so is one line written with and without its country code.
        Contact("한서연", listOf("010-3333-4444", "+82 10-3333-4444"), emptyList()),
        Contact("정하늘", listOf("010-5555-6666"), emptyList()),
        Contact("메일만", emptyList(), listOf("a@b.c")),
    )
    // The phone's search also matches nicknames and the like, which are not in the name.
    private val nicknames = mapOf("자기" to "정하늘")
    private val contacts = object : ContactsSource {
        override fun hasPermission() = allowed
        override fun search(name: String, limit: Int): List<Contact> {
            if (throws) throw SecurityException("revoked")
            return book.filter { name in it.name || nicknames[name] == it.name }.take(limit)
        }
    }
    private val resolver = RecipientResolver(contacts)

    private fun unclear(to: String, r: RecipientResolver = resolver) = (r.resolve(to) as Recipient.Unclear).reason

    @Test
    fun aNumberIsUsedAsIs() {
        assertEquals(Recipient.Found("01012345678"), resolver.resolve(" 010-1234-5678 "))
        assertEquals(Recipient.Found("+821012345678"), resolver.resolve("+82 10 1234 5678"))
        assertEquals(Recipient.Found("01012345678"), resolver.resolve("010.1234.5678"))
        // The (0) of an international number is not dialled.
        assertEquals(Recipient.Found("+821012345678"), resolver.resolve("+82 (0)10-1234-5678"))
        // No contacts needed for a number.
        allowed = false
        assertEquals(Recipient.Found("114"), RecipientResolver(null).resolve("114"))
        assertEquals(Recipient.Found("01012345678"), resolver.resolve("01012345678"))
        assertEquals("01012345678", Recipient.Found("01012345678").shown)
    }

    @Test
    fun whatIsNeitherANumberNorANameIsRefused() {
        assertTrue(unclear("   ").contains("비었어요"))
        assertTrue(unclear("12").contains("전화번호가 올바르지 않아요"))
        // A version or a date, not a number.
        assertTrue(unclear("1.5.25").contains("전화번호가 올바르지 않아요"))
        assertTrue(unclear("엄마", RecipientResolver(null)).contains("전화번호여야"))
    }

    @Test
    fun aNameNeedsTheContactsPermission() {
        allowed = false
        assertEquals(Recipient.NeedsContacts, resolver.resolve("김민수"))
        allowed = true
        throws = true
        assertEquals(Recipient.NeedsContacts, resolver.resolve("김민수"))
    }

    @Test
    fun oneContactWithOneNumberIsFound() {
        assertEquals(Recipient.Found("01012345678", "김민수", "김민수"), resolver.resolve("김민수"))
        // A part of the name, when only one contact has it.
        val partial = resolver.resolve(" 수진 ") as Recipient.Found
        assertEquals(Recipient.Found("01044445555", "최수진", "수진"), partial)
        assertEquals("최수진 (01044445555)", partial.shown)
        assertEquals(Recipient.Found("01033334444", "한서연", "한서연"), resolver.resolve("한서연"))
    }

    @Test
    fun aContactFoundByAnythingButItsNameIsNotWhatTheUserNamed() {
        assertEquals(Recipient.Found("01055556666", "정하늘", null), resolver.resolve("자기"))
    }

    @Test
    fun anExactNameWinsOverPartialMatches() {
        assertEquals(Recipient.Found("01077770000", "민", "민"), resolver.resolve("민"))
    }

    @Test
    fun severalNumbersAreNeverGuessed() {
        val people = unclear("민수")
        assertTrue(people, "김민수: 01012345678; 박민수: 01099998888" in people && "ask_user" in people)
        val numbers = unclear("이지은")
        assertTrue(numbers, "이지은: 01022223333, 025550000" in numbers)
    }

    @Test
    fun nobodyOrNoNumberSaysSo() {
        assertTrue(unclear("엄마").contains("찾지 못했어요"))
        assertTrue(unclear("메일만").contains("전화번호가 없어요"))
    }
}
