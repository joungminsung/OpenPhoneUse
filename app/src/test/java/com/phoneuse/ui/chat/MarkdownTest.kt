package com.phoneuse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun plain(t: String) = MdSpan(t)
    private fun bold(t: String) = MdSpan(t, bold = true)

    @Test
    fun paragraphKeepsLineBreaksAndSplitsOnBlankLines() {
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(plain("첫 줄\n둘째 줄"))),
                MdBlock.Paragraph(listOf(plain("다음 문단"))),
            ),
            Markdown.parse("첫 줄\n둘째 줄\n\n\n다음 문단\n"),
        )
    }

    @Test
    fun inlineBoldItalicAndCode() {
        assertEquals(
            listOf(plain("PDF "), bold("12개"), plain("를 "), MdSpan("문서", italic = true), plain(" 폴더 "), MdSpan("a_b.pdf", code = true)),
            Markdown.inline("PDF **12개**를 *문서* 폴더 `a_b.pdf`"),
        )
    }

    @Test
    fun codeInsideBoldAndUnderscoreBold() {
        assertEquals(
            listOf(MdSpan("x ", bold = true), MdSpan("y", bold = true, code = true), plain(" "), bold("z")),
            Markdown.inline("**x `y`** __z__"),
        )
    }

    @Test
    fun unclosedMarkersStayLiteral() {
        // A reply that is still streaming may end mid-marker.
        assertEquals(listOf(plain("값은 **12")), Markdown.inline("값은 **12"))
        assertEquals(listOf(plain("2 * 3 = 6, `x")), Markdown.inline("2 * 3 = 6, `x"))
        assertEquals(listOf(plain("file_name_here")), Markdown.inline("file_name_here"))
    }

    @Test
    fun headings() {
        assertEquals(
            listOf(MdBlock.Heading(1, listOf(plain("제목"))), MdBlock.Heading(3, listOf(plain("재료")))),
            Markdown.parse("# 제목\n### 재료"),
        )
        assertEquals(listOf(MdBlock.Paragraph(listOf(plain("#해시태그")))), Markdown.parse("#해시태그"))
    }

    @Test
    fun bulletListWithNesting() {
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(plain("할 일:"))),
                MdBlock.Bullets(
                    listOf(
                        MdListItem(listOf(plain("우유")), 0),
                        MdListItem(listOf(bold("두부")), 0),
                        MdListItem(listOf(plain("연두부")), 1),
                        MdListItem(listOf(plain("대파")), 0),
                    ),
                ),
            ),
            Markdown.parse("할 일:\n- 우유\n* **두부**\n  - 연두부\n• 대파"),
        )
    }

    @Test
    fun numberedListKeepsNumbers() {
        assertEquals(
            listOf(
                MdBlock.Numbered(
                    listOf(
                        MdNumberedItem(1, listOf(plain("고기를 볶아요."))),
                        MdNumberedItem(2, listOf(plain("김치를 넣어요."))),
                        MdNumberedItem(5, listOf(plain("끓여요."))),
                    ),
                ),
            ),
            Markdown.parse("1. 고기를 볶아요.\n2) 김치를 넣어요.\n5. 끓여요."),
        )
    }

    @Test
    fun listItemContinuationLineJoinsItem() {
        assertEquals(
            listOf(MdBlock.Numbered(listOf(MdNumberedItem(1, listOf(plain("물 붓기\n센 불로")))))),
            Markdown.parse("1. 물 붓기\n   센 불로"),
        )
    }

    @Test
    fun simpleTable() {
        val parsed = Markdown.parse("재료:\n| 이름 | 양 |\n|---|:--:|\n| 묵은지 | 1/4포기 |\n| **돼지고기** | 200g |\n\n끝")
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(plain("재료:"))),
                MdBlock.Table(
                    header = listOf(listOf(plain("이름")), listOf(plain("양"))),
                    rows = listOf(
                        listOf(listOf(plain("묵은지")), listOf(plain("1/4포기"))),
                        listOf(listOf(bold("돼지고기")), listOf(plain("200g"))),
                    ),
                ),
                MdBlock.Paragraph(listOf(plain("끝"))),
            ),
            parsed,
        )
    }

    @Test
    fun tableRowsArePaddedToHeaderWidth() {
        assertEquals(
            MdBlock.Table(listOf(listOf(plain("a")), listOf(plain("b"))), listOf(listOf(listOf(plain("1")), emptyList()))),
            Markdown.parse("a | b\n--- | ---\n| 1\n").single(),
        )
    }

    @Test
    fun tableEndsAtLineWithoutPipe() {
        assertEquals(
            listOf(
                MdBlock.Table(listOf(listOf(plain("a")), listOf(plain("b"))), listOf(listOf(listOf(plain("1")), listOf(plain("2"))))),
                MdBlock.Paragraph(listOf(plain("표 다음 문단"))),
            ),
            Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |\n표 다음 문단"),
        )
    }

    @Test
    fun pipeWithoutSeparatorIsParagraph() {
        assertEquals(listOf(MdBlock.Paragraph(listOf(plain("a | b")))), Markdown.parse("a | b"))
    }

    @Test
    fun fencedCodeBlockIsVerbatim() {
        assertEquals(
            listOf(MdBlock.Code("val x = **1**\n\n- y"), MdBlock.Paragraph(listOf(plain("뒤")))),
            Markdown.parse("```kotlin\nval x = **1**\n\n- y\n```\n뒤"),
        )
        // Unclosed while streaming: everything after the fence is code.
        assertEquals(listOf(MdBlock.Code("a\nb")), Markdown.parse("```\na\nb"))
    }

    @Test
    fun horizontalRule() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(plain("위"))), MdBlock.Rule, MdBlock.Paragraph(listOf(plain("아래")))),
            Markdown.parse("위\n\n---\n\n아래"),
        )
    }

    @Test fun quoteLinesBecomeOneHighlightedBlock() {
        val blocks = Markdown.parse("결론이에요.\n\n> **추천: A**\n> 가장 싸요\n\n끝")
        assertEquals(3, blocks.size)
        val quote = blocks[1] as MdBlock.Quote
        assertEquals("추천: A", quote.spans.first().text)
        assertTrue(quote.spans.first().bold)
        assertEquals("추천: A\n가장 싸요", quote.spans.joinToString("") { it.text })
    }

    private fun link(t: String, url: String) = MdSpan(t, url = url)

    @Test
    fun linkBecomesATappableSpan() {
        assertEquals(
            listOf(plain("날씨 ("), link("example.com", "https://example.com/a"), plain(") 끝")),
            Markdown.inline("날씨 ([example.com](https://example.com/a)) 끝"),
        )
        assertEquals(listOf(link("a", "http://a.example")), Markdown.inline("[a](http://a.example)"))
        assertEquals(listOf(link("a", "HTTPS://a.example")), Markdown.inline("[a](HTTPS://a.example)"))
    }

    @Test
    fun linkUrlKeepsBalancedParentheses() {
        assertEquals(
            listOf(plain("("), link("위키", "https://en.wikipedia.org/wiki/Seoul_(city)"), plain(")")),
            Markdown.inline("([위키](https://en.wikipedia.org/wiki/Seoul_(city)))"),
        )
        assertEquals(listOf(link("a", "https://x.example/(a(b))c")), Markdown.inline("[a](https://x.example/(a(b))c)"))
    }

    @Test
    fun onlyHttpLinksAreLinks() {
        for (text in listOf(
            "[a](intent://x#Intent;end)",
            "[a](javascript:alert(1))",
            "[a](tel:112)",
            "[a](file:///sdcard/x)",
            "[a](/relative)",
            "[a](httpsx://b)",
            "[a](https://)",
            "[a](https://b c)",
            "[](https://b)",
        )) {
            assertEquals(text, listOf(plain(text)), Markdown.inline(text))
        }
    }

    @Test
    fun bracketsThatAreNotLinksStayLiteral() {
        assertEquals(listOf(plain("배열[0] (참고)")), Markdown.inline("배열[0] (참고)"))
        assertEquals(listOf(plain("[a]\n(https://b)")), Markdown.inline("[a]\n(https://b)"))
    }

    @Test
    fun linkInATableCell() {
        assertEquals(
            MdBlock.Table(
                listOf(listOf(plain("곳")), listOf(plain("링크"))),
                listOf(listOf(listOf(plain("서울")), listOf(link("예보", "https://w.example/s")))),
            ),
            Markdown.parse("| 곳 | 링크 |\n|---|---|\n| 서울 | [예보](https://w.example/s) |").single(),
        )
    }

    @Test
    fun boldAroundAndInsideALink() {
        assertEquals(
            listOf(MdSpan("참고 ", bold = true), MdSpan("글", bold = true, url = "https://a.example")),
            Markdown.inline("**참고 [글](https://a.example)**"),
        )
        assertEquals(
            listOf(MdSpan("글", bold = true, url = "https://a.example"), link(" 끝", "https://a.example")),
            Markdown.inline("[**글** 끝](https://a.example)"),
        )
    }

    @Test
    fun linkInsideCodeStaysCode() {
        assertEquals(listOf(MdSpan("[a](https://b)", code = true)), Markdown.inline("`[a](https://b)`"))
    }

    @Test
    fun neighbouringLinksDoNotMerge() {
        assertEquals(
            listOf(link("a", "https://a.example"), link("b", "https://b.example"), plain("c")),
            Markdown.inline("[a](https://a.example)[b](https://b.example)c"),
        )
    }

    @Test
    fun unfinishedLinkStaysLiteralInTheParser() {
        assertEquals(listOf(plain("날씨 [a](https://b.example/c")), Markdown.inline("날씨 [a](https://b.example/c"))
    }

    @Test
    fun streamingHidesALinkStillBeingWritten() {
        for (tail in listOf("[", "[city", "[city]", "[city](", "[city](h", "[city](https", "[city](https:/", "[city](https://a.example/(b)")) {
            assertEquals(tail, "맑아요. (", Markdown.withoutOpenLink("맑아요. ($tail"))
        }
    }

    @Test
    fun streamingKeepsEverythingElse() {
        for (text in listOf(
            "맑아요.",
            "[a](https://b.example)",
            "[a](https://b.example) 그리고",
            "배열[0] 끝",
            "[a] 다음",
            "[a](tel:112",
            "[a](/relative",
            "[첫 줄\n둘째 줄",
        )) {
            assertEquals(text, text, Markdown.withoutOpenLink(text))
        }
    }

    @Test
    fun onlyOpenAiTrackingIsRemovedFromTheUrl() {
        fun url(raw: String) = Markdown.inline("[a]($raw)").single().url
        assertEquals("https://a.example/7-day/", url("https://a.example/7-day/?utm_source=openai"))
        assertEquals("https://a.example/?x=1", url("https://a.example/?utm_source=openai&x=1"))
        assertEquals("https://a.example/?x=1", url("https://a.example/?x=1&utm_source=openai"))
        assertEquals("https://a.example/?x=1&y=2", url("https://a.example/?x=1&utm_source=openai&y=2"))
        assertEquals("https://a.example/#f", url("https://a.example/?utm_source=openai#f"))
        // Anything else is the site's own.
        assertEquals("https://a.example/?utm_source=news", url("https://a.example/?utm_source=news"))
        assertEquals("https://a.example/?utm_source=openai2", url("https://a.example/?utm_source=openai2"))
        assertEquals("https://a.example/?xutm_source=openai", url("https://a.example/?xutm_source=openai"))
    }
}
