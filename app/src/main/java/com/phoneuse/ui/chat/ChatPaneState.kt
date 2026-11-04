package com.phoneuse.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Chat pane state that must outlive the pane leaving composition: flex mode (tabletop) draws no chat, and
 * coming back must keep the scroll position and the attach sheet. Held by the activity's root above the fold layouts.
 */
@Stable
class ChatPaneState {
    var attachOpen by mutableStateOf(false)

    /**
     * A conversation whose newest answer should show from its start (opened from a result notification); the
     * chat clears it once done. The list is reversed, so jumping to the newest message shows a long answer's end.
     */
    var reveal by mutableStateOf<Long?>(null)

    internal val follow = ScrollFollow()

    private var listConversation: Long? = null
    private var list: LazyListState? = null

    /** The list state of [conversation]; a different conversation starts at the newest message. */
    fun listState(conversation: Long?): LazyListState {
        val current = list
        if (current != null && listConversation == conversation) return current
        return LazyListState().also {
            list = it
            listConversation = conversation
        }
    }
}

/**
 * Decides when the chat jumps to the newest message: once per new key (conversation, newest message, live),
 * not again when the same pane comes back into composition after flex mode.
 */
class ScrollFollow {
    private var seen = false
    private var last: Any? = null

    fun shouldFollow(key: Any?): Boolean {
        if (seen && key == last) return false
        seen = true
        last = key
        return true
    }
}
