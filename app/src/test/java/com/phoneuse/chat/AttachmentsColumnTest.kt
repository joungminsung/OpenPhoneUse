package com.phoneuse.chat

import com.phoneuse.app.Source
import com.phoneuse.model.InputItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentsColumnTest {
    @Test fun roundTripsIncludingIds() {
        val list = listOf(Attachment.image("a.jpg"), Attachment.text("메모.txt", "내용"))
        assertEquals(list, AttachmentsColumn.decode(AttachmentsColumn.encode(list)))
    }

    @Test fun sameFileTwiceGetsDistinctKeys() {
        val a = Attachment.text("notes.txt", "x")
        val b = Attachment.text("notes.txt", "x")
        assertNotEquals(a.id, b.id)
        assertNotEquals(a, b)
        // Rows written without an id still read with distinct ones.
        val old = AttachmentsColumn.decode("""[{"kind":"TEXT","name":"n","text":"t"},{"kind":"TEXT","name":"n","text":"t"}]""")
        assertEquals(2, old.map { it.id }.toSet().size)
    }

    @Test fun noneStoresNullAndBadValuesReadAsNone() {
        assertNull(AttachmentsColumn.encode(emptyList()))
        assertEquals(emptyList<Attachment>(), AttachmentsColumn.decode(null))
        assertEquals(emptyList<Attachment>(), AttachmentsColumn.decode("not json"))
    }

    @Test fun textIsCappedAt20000Chars() {
        assertEquals(Attachment.MAX_TEXT_CHARS, Attachment.text("big.txt", "가".repeat(30_000)).text!!.length)
        assertEquals(20_000, Attachment.MAX_TEXT_CHARS)
    }

    @Test fun titleOfAMessageWithoutText() {
        assertEquals("잎 질문", Attachment.title("잎 질문", listOf(Attachment.image("a.jpg"))))
        assertEquals("사진", Attachment.title(" ", listOf(Attachment.image("a.jpg"), Attachment.image("b.jpg"))))
        assertEquals("보고서.pdf 1쪽", Attachment.title("", listOf(Attachment.image("a.jpg", "보고서.pdf 1쪽"))))
        assertEquals("메모.txt", Attachment.title("", listOf(Attachment.text("메모.txt", "x"))))
    }

    @Test fun cardMessagesNeverCarryAttachments() {
        val list = listOf(Attachment.image("a.jpg"))
        assertEquals(emptyList<Attachment>(), Source.CARD.allowed(list))
        assertEquals(list, Source.APP.allowed(list))
        assertEquals(list, Source.ASSIST.allowed(list))
    }

    @Test fun imageBudgetLetsTheFirstImagesThrough() {
        val load = ImageBudget(remaining = 10).load { a -> InputItem.UserImage(a.file!!) }
        assertEquals(InputItem.UserImage("123456"), load(Attachment.image("123456")))
        assertNull(load(Attachment.image("12345")))
        assertEquals(InputItem.UserImage("1234"), load(Attachment.image("1234")))
        // Over budget, the history falls back to the photo mark.
        val items = HistoryBuilder.build(
            listOf(ChatMessage(1, 1, MessageRole.USER, "q", emptyList(), 0, attachments = listOf(Attachment.image("abc")))),
            loadImage = ImageBudget(remaining = 2).load { a -> InputItem.UserImage(a.file!!) },
        )
        assertEquals(listOf(InputItem.UserText("[사진] q")), items)
    }
}

class AttachmentFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun resolvesOnlyInsideTheAttachmentsFolder() {
        val dir = tmp.newFolder("attachments")
        val inside = File(dir, "a.jpg").apply { writeText("x") }
        assertEquals(inside.canonicalFile, AttachmentFiles.resolve(Attachment.image("a.jpg"), dir))
        // An absolute path resolves by its name, inside the folder.
        assertEquals(inside.canonicalFile, AttachmentFiles.resolve(Attachment.image("/elsewhere/a.jpg"), dir))
        assertNull(AttachmentFiles.resolve(Attachment.image(".."), dir))
        assertNull(AttachmentFiles.resolve(Attachment.text("t", "x"), dir))
    }

    @Test fun deletesOnlyFilesInsideTheAttachmentsFolder() {
        val dir = tmp.newFolder("attachments")
        val inside = File(dir, "a.jpg").apply { writeText("x") }
        val outside = tmp.newFile("keep.jpg").apply { writeText("x") }
        AttachmentFiles.delete(
            listOf(Attachment.image("a.jpg"), Attachment.image(outside.path), Attachment.image("../keep.jpg"), Attachment.text("t", "x")),
            dir,
        )
        assertFalse(inside.exists())
        assertTrue(outside.exists())
    }

    @Test fun sweepRemovesOnlyOldUnreferencedFiles() {
        val dir = tmp.newFolder("attachments")
        val now = 10L * AttachmentFiles.SWEEP_AGE_MS
        fun file(name: String, age: Long) = File(dir, name).apply {
            writeText("x")
            setLastModified(now - age)
        }
        val kept = file("kept.jpg", 2 * AttachmentFiles.SWEEP_AGE_MS)
        val orphan = file("orphan.jpg", 2 * AttachmentFiles.SWEEP_AGE_MS)
        val draft = file("draft.jpg", 1000)
        assertEquals(1, AttachmentFiles.sweep(dir, setOf("kept.jpg"), now))
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
        assertTrue(draft.exists())
        assertEquals(0, AttachmentFiles.sweep(File(dir, "missing"), emptySet(), now))
    }
}
