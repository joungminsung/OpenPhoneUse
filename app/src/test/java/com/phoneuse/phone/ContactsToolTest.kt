package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsToolTest {
    private class Source(var permitted: Boolean = true) : ContactsSource {
        val asked = mutableListOf<String>()
        override fun hasPermission() = permitted
        override fun search(name: String, limit: Int): List<Contact> {
            asked += name
            return if (name == "민수") listOf(Contact("김민수", listOf("010-1234-5678"), listOf("minsu@example.com"))) else emptyList()
        }
    }

    private val source = Source()
    private val tool = ContactsTool(source)

    @Test
    fun findsContactsAsJson() {
        val r = tool.find(" 민수 ")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.contains("\"name\":\"김민수\"") && r.text.contains("\"phones\":[\"010-1234-5678\"]"))
        assertEquals(listOf("민수"), source.asked)
        assertTrue(tool.find("없는사람").text.startsWith("결과 없음"))
        assertTrue(tool.find(" ") is PhoneReply.Failed)
    }

    @Test
    fun returnsPermissionNeededWhenDenied() {
        source.permitted = false
        val r = tool.find("민수")
        assertTrue(r.text, r.text.startsWith("권한 필요: 연락처"))
        assertEquals(listOf("android.permission.READ_CONTACTS"), (r as PhoneReply.PermissionNeeded).permissions)
        assertTrue(source.asked.isEmpty())
    }
}
