package com.phoneuse.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId

class ChatExportTest {
    @Test fun writesConversationsWithMessages() {
        val c = Conversation(3, "엄마 생신 선물", 1_000L)
        val messages = listOf(
            ChatMessage(1, 3, MessageRole.USER, "선물 추천해 줘", emptyList(), 1_000L),
            ChatMessage(2, 3, MessageRole.ASSISTANT, "등산 스틱 어때요?", listOf(StepLog("검색")), 2_000L),
        )
        val root = Json.parseToJsonElement(ChatExport.json(listOf(c to messages), 3_000L)).jsonObject
        val conv = root["conversations"]!!.jsonArray.single().jsonObject
        assertEquals("엄마 생신 선물", conv["title"]!!.jsonPrimitive.content)
        val m = conv["messages"]!!.jsonArray
        assertEquals("user", m[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse(m[0].jsonObject.containsKey("steps"))
        assertEquals("검색", m[1].jsonObject["steps"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:03Z", root["exported_at"]!!.jsonPrimitive.content)
    }

    @Test fun fileNameHasTheTime() {
        assertEquals("phone-use-chats-19700101-0900.json", ChatExport.fileName(0L, ZoneId.of("Asia/Seoul")))
    }
}
