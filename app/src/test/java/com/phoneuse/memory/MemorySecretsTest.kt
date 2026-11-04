package com.phoneuse.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemorySecretsTest {
    @Test fun refusesSecrets() {
        for (text in listOf(
            "내 은행 비번 4821",
            "사용자의 카드 비밀번호는 1234",
            "PIN: 0000",
            "wifi password is hunter22",
            "Netflix password: s3cretpass",
            "OTP 482913",
            "인증번호 123456",
            "카드 번호 4111 1111 1111 1111",
            "4111-1111-1111-1111",
            "주민번호 900101-1234567",
            "9001011234567",
        )) {
            assertNotNull(text, MemorySecrets.refusal(text))
        }
    }

    @Test fun keepsOrdinaryFacts() {
        for (text in listOf(
            "사용자는 비밀번호 관리자를 쓴다",
            "엄마 전화번호 010-1234-5678",
            "집 주소는 서울시 강남구 테헤란로 123",
            "생일은 11월 20일",
            "회사 사번 20231234",
            "좋아하는 숫자는 7",
            "Pin the weather widget",
        )) {
            assertNull(text, MemorySecrets.refusal(text))
        }
    }

    @Test fun storeRefusesAndSaysWhy() = runBlocking {
        val store = InMemoryMemoryStore()
        try {
            store.add(NewMemory("내 은행 비번 4821", MemoryKind.ME))
            fail("expected a refusal")
        } catch (e: SecretInMemoryException) {
            assertTrue(e.message!!, e.message!!.contains("비밀번호") || e.message!!.contains("password"))
        }
        assertEquals(0, store.observeOnce().size)
        val id = store.add(NewMemory("단골 은행은 국민은행", MemoryKind.ME)).id
        try {
            store.update(id, "국민은행 비밀번호 9999", MemoryKind.ME)
            fail("expected a refusal")
        } catch (e: SecretInMemoryException) {
            // An edit cannot slip a secret in either.
        }
        assertEquals("단골 은행은 국민은행", store.get(id)!!.text)
    }
}
