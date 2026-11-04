package com.phoneuse.ui.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the user changed on a card (places selection, photo filter and selection). One object per card, shared
 * by every copy of it (chat, unfolded result pane, flex mode), so the state moves with the card.
 */
class CardUiState {
    var placeSelected by mutableIntStateOf(0)
    var photoFilter by mutableIntStateOf(-1)
    var photoSelected by mutableStateOf(listOf<String>())
    var photosExpanded by mutableStateOf(false)
}

/**
 * Card states by PaneCard.key, held above every layout. Keeps the [max] most recently used cards. One store per
 * conversation on screen (MainActivity remembers it keyed by the conversation), so switching drops the old states.
 */
class CardStateStore<T>(private val max: Int = MAX_CARDS, private val create: () -> T) {
    private val states = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > max
    }
    @Synchronized
    fun get(key: String): T = states.getOrPut(key, create)

    @get:Synchronized
    val size: Int get() = states.size

    companion object {
        const val MAX_CARDS = 20
    }
}

/** The card states of the activity; null (each card keeps its own) where nothing provides it. */
val LocalCardStates = staticCompositionLocalOf<CardStateStore<CardUiState>?> { null }

/** Provided by ResultCard for its stateKey. */
internal val LocalCardUiState = staticCompositionLocalOf<CardUiState?> { null }

/** The state of the card being drawn: the shared one when ResultCard has a key, else this copy's own. */
@Composable
internal fun rememberCardUi(): CardUiState {
    val shared = LocalCardUiState.current
    val own = remember { CardUiState() }
    return shared ?: own
}
