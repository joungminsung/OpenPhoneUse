package com.phoneuse.app

import com.phoneuse.agent.MemoryAccess
import com.phoneuse.chat.ConversationSearch
import com.phoneuse.memory.MemoryStore

/**
 * What one run may do with memory, from the settings and where the message came from. Kept pure so the rules
 * are tested: memory off turns everything off (prompt block, every memory tool, conversation search, app tips);
 * a card button's text is not the user's own words, and neither is a routine that fired by itself, so such
 * runs can neither remember nor forget.
 */
data class RunMemoryPolicy(
    val inject: Boolean,
    val remember: Boolean,
    val forget: Boolean,
    val searchConversations: Boolean,
    val appTips: Boolean,
    /** remember/forget stay in the tool list (refused when not allowed), so card runs keep the prompt cache. */
    val offerRemember: Boolean = remember,
    val offerForget: Boolean = forget,
) {
    fun access(
        store: MemoryStore,
        search: ConversationSearch,
        conversationId: Long?,
        origin: String?,
    ): MemoryAccess = MemoryAccess(
        store = store.takeIf { inject },
        search = search.takeIf { searchConversations },
        allowRemember = remember,
        conversationId = conversationId,
        origin = origin,
        allowForget = forget,
        offerRemember = offerRemember && inject,
        offerForget = offerForget && inject,
    )

    companion object {
        fun of(memoryOn: Boolean, autoRemember: Boolean, source: Source): RunMemoryPolicy {
            val userWords = source != Source.CARD && source != Source.ROUTINE
            return RunMemoryPolicy(
                inject = memoryOn,
                remember = memoryOn && autoRemember && userWords,
                forget = memoryOn && userWords,
                searchConversations = memoryOn,
                appTips = memoryOn,
                offerRemember = memoryOn && autoRemember,
                offerForget = memoryOn,
            )
        }
    }
}
