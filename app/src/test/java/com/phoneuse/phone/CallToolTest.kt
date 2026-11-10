package com.phoneuse.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallToolTest {
    private var allowed = true
    private var canDial = true
    private var connects = true
    private var busy = false
    private var revoked = false
    private var contactsAllowed = true
    private val dialled = mutableListOf<String>()
    private val source = object : CallSource {
        override fun canCall() = allowed
        override fun call(number: String): Boolean {
            if (revoked) throw SecurityException("revoked")
            dialled += number
            if (canDial && connects) busy = true
            return canDial
        }
        override fun inCall() = busy
    }
    private val contacts = object : ContactsSource {
        override fun hasPermission() = contactsAllowed
        override fun search(name: String, limit: Int) =
            listOf(Contact("김민수", listOf("010-1234-5678"), emptyList())).filter { name in it.name }
    }
    private var waits = 0
    private val asked = mutableListOf<String>()
    private val tool = CallTool(source, RecipientResolver(contacts)) { waits++ }

    private fun call(to: String, allow: Boolean = true) = runBlocking { tool.call(to) { _, summary -> asked += summary; allow } }

    @Test
    fun callsAfterTheCardSaysYes() {
        val r = call("김민수")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("OK: 김민수 (01012345678)에 전화를 걸고 있어요"))
        assertEquals(listOf(CallTool.confirmSummary(Recipient.Found("01012345678", "김민수", "김민수"))), asked)
        assertEquals(listOf("01012345678"), dialled)
    }

    @Test
    fun aDeclinedCallIsNotDialled() {
        assertEquals(PhoneReply.Cancelled, call("010-1234-5678", allow = false))
        assertEquals(listOf(CallTool.confirmSummary(Recipient.Found("01012345678"))), asked)
        assertTrue(dialled.isEmpty())
    }

    @Test
    fun permissionsComeBeforeTheCard() {
        allowed = false
        assertEquals(PhoneReply.PermissionNeeded(CallTool.LABEL, listOf("android.permission.CALL_PHONE")), call("01012345678"))
        allowed = true
        contactsAllowed = false
        assertEquals(PhoneReply.PermissionNeeded(ContactsTool.LABEL, ContactsTool.PERMISSIONS), call("김민수"))
        // Revoked between the check and the call.
        contactsAllowed = true
        revoked = true
        assertEquals(PhoneReply.PermissionNeeded(CallTool.LABEL, CallTool.PERMISSIONS), call("김민수"))
        assertEquals(1, asked.size)
        assertTrue(dialled.isEmpty())
    }

    @Test
    fun anUnclearRecipientNeverAsksOrDials() {
        val r = call("박민수")
        assertTrue(r.text, r is PhoneReply.Failed && "찾지 못했어요" in r.text)
        assertTrue(call("12") is PhoneReply.Failed)
        assertTrue(asked.isEmpty())
        assertTrue(dialled.isEmpty())
    }

    @Test
    fun noSecondCallOnTopOfOne() {
        busy = true
        val r = call("01012345678")
        assertTrue(r.text, r is PhoneReply.Failed && "이미 통화 중" in r.text)
        assertTrue(asked.isEmpty())
        assertTrue(dialled.isEmpty())
    }

    @Test
    fun aDeviceThatCannotCallSaysSo() {
        canDial = false
        val r = call("01012345678")
        assertTrue(r.text, r is PhoneReply.Failed && "전화를 걸 수 없어요" in r.text)
    }

    @Test
    fun aCallThatNeverShowsUpIsReportedNotRetried() {
        connects = false
        val r = call("01012345678")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("확인 못 함") && "다시 걸지 말고" in r.text)
        assertEquals(listOf("01012345678"), dialled)
        assertEquals(8, waits)
    }
}
