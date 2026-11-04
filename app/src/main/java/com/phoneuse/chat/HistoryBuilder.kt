package com.phoneuse.chat

import com.phoneuse.cards.CardJson
import com.phoneuse.model.InputItem

/**
 * Turns stored chat messages into model input, keeping the newest messages that fit a character budget.
 * Attachments are sent in full (images, file text) only for the [RECENT_ATTACHMENTS] newest messages that have
 * any; older ones become "[사진]" / "[파일: 이름]" marks. Images do not count toward the budget.
 */
object HistoryBuilder {
    /** Only the last steps are kept: they show where the run ended. */
    private const val MAX_STEPS = 10
    private const val ELLIPSIS = "…"
    const val RECENT_ATTACHMENTS = 2
    const val PHOTO_MARK = "[사진]"

    /** [loadImage] reads a stored image for the model; null (e.g. the file is gone) sends [PHOTO_MARK] instead. */
    fun build(
        messages: List<ChatMessage>,
        maxChars: Int = 24_000,
        loadImage: (Attachment) -> InputItem.UserImage? = { null },
    ): List<InputItem> {
        val recent = messages.asReversed().filter { it.attachments.isNotEmpty() }.take(RECENT_ATTACHMENTS).map { it.id }.toSet()
        val kept = ArrayList<InputItem>()
        var used = 0
        for (message in messages.asReversed()) {
            val user = if (message.role == MessageRole.USER) {
                userItem(message.text, message.attachments, message.id in recent, loadImage)
            } else {
                null
            }
            val text = user?.text ?: withSteps(message)
            // Older messages are dropped once the budget is spent. The newest one is always kept, cut to its tail.
            if (used + text.length > maxChars) {
                if (kept.isEmpty() && maxChars > 0) {
                    val cut = ELLIPSIS + text.takeLast(maxChars - 1)
                    kept += user?.copy(text = cut) ?: InputItem.AssistantText(cut)
                }
                break
            }
            used += text.length
            kept += user ?: InputItem.AssistantText(text)
        }
        return kept.reversed()
    }

    /**
     * A user message with its attachments. With [withContent] images are attached and file text is appended;
     * otherwise each attachment is only a mark before the text. A named image (a PDF page) always gets its mark.
     */
    fun userItem(
        text: String,
        attachments: List<Attachment>,
        withContent: Boolean,
        loadImage: (Attachment) -> InputItem.UserImage?,
    ): InputItem.UserText {
        val marks = ArrayList<String>()
        val images = ArrayList<InputItem.UserImage>()
        val files = StringBuilder()
        for (a in attachments) {
            when (a.kind) {
                AttachmentKind.IMAGE -> {
                    val image = if (withContent) loadImage(a) else null
                    if (image != null) images += image
                    if (a.name != null) marks += "[파일: ${a.name}]" else if (image == null) marks += PHOTO_MARK
                }
                AttachmentKind.TEXT ->
                    if (withContent) files.append("\n\n[파일: ").append(a.name).append("]\n").append(a.text.orEmpty())
                    else marks += "[파일: ${a.name}]"
            }
        }
        val body = (marks + listOfNotNull(text.takeIf { it.isNotEmpty() })).joinToString(" ")
        return InputItem.UserText(if (body.isEmpty()) files.toString().trimStart('\n') else body + files, images)
    }

    private fun withSteps(message: ChatMessage): String {
        val sb = StringBuilder(message.text)
        if (message.steps.isNotEmpty()) {
            val summary = message.steps.takeLast(MAX_STEPS).joinToString("; ") { it.text }
            sb.append("\n(수행한 단계: ").append(summary).append(')')
        }
        // The cards' content is not in the text; without it later turns could not refer to "this event".
        for (card in message.cards) sb.append("\n(보여준 카드: ").append(CardJson.summary(card)).append(')')
        // Domains only: a page's title and path are text the user did not write.
        val domains = message.sources.mapNotNull { CardJson.domain(it.url) }.distinct()
        if (domains.isNotEmpty()) sb.append("\n(출처: ").append(domains.joinToString("; ")).append(')')
        return sb.toString()
    }
}

/**
 * Caps the attached-image data of one model request: [load] passes images through until their base64 adds up
 * to [remaining] characters; later ones read as missing, so they become "[사진]". Use one budget for the
 * current message first, then the history, so the newest photos win.
 */
class ImageBudget(private var remaining: Int = MAX_CHARS) {
    fun load(loader: (Attachment) -> InputItem.UserImage?): (Attachment) -> InputItem.UserImage? = { a ->
        loader(a)?.takeIf { it.base64.length <= remaining }?.also { remaining -= it.base64.length }
    }

    companion object {
        /** About 6 MB of base64 per request. */
        const val MAX_CHARS = 6_000_000
    }
}
