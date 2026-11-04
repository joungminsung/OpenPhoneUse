package com.phoneuse.phone

import com.phoneuse.cards.CardSpec

/** What a phone data tool answers; [text] is the tool output. */
sealed interface PhoneReply {
    val text: String

    /** [card] is shown under the reply (an event preview, a photo grid). */
    data class Ok(override val text: String, val card: CardSpec? = null) : PhoneReply

    /** The app lacks a runtime permission; the chat shows a [권한 허용] card for [permissions]. */
    data class PermissionNeeded(val label: String, val permissions: List<String>) : PhoneReply {
        override val text get() = "권한 필요: $label"
    }

    data class Failed(val reason: String) : PhoneReply {
        override val text get() = "ERROR: $reason"
    }

    /** The user declined the confirmation card. */
    data object Cancelled : PhoneReply {
        override val text = "CANCELLED by user"
    }
}
