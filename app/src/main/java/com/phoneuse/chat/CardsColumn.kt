package com.phoneuse.chat

import com.phoneuse.cards.CardJson
import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.SourceLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * `messages.cards_json`: the card array, plus at most one trailing `{"kind":"reply_extras",…}` element that
 * holds [ReplyExtras]. [CardJson.decodeList] skips the unknown kind, so cards read the same either way and
 * no schema change is needed.
 */
object CardsColumn {
    const val EXTRAS_KIND = "reply_extras"

    @Serializable
    private data class Stored(
        // No default: defaults are not encoded, and the kind must be written.
        val kind: String,
        val searches: Int = 0,
        val sources: List<SourceLink> = emptyList(),
        val notice: String? = null,
        val usedMemories: List<MemoryRef> = emptyList(),
        val remembered: List<MemoryRef> = emptyList(),
        val forgotten: List<ForgottenMemory> = emptyList(),
        val planUsageLimit: Boolean = false,
    )

    /** Null when there is nothing to store, so the column stays NULL. */
    fun encode(cards: List<CardSpec>, extras: ReplyExtras): String? {
        if (extras.isEmpty) return CardJson.encodeList(cards)
        val json = CardJson.json
        return buildJsonArray {
            val list = json.encodeToJsonElement(ListSerializer(CardSpec.serializer()), cards) as JsonArray
            list.forEach { add(it) }
            add(
                json.encodeToJsonElement(
                    Stored.serializer(),
                    Stored(
                        EXTRAS_KIND,
                        searches = extras.webSearches,
                        sources = extras.sources,
                        notice = extras.notice,
                        usedMemories = extras.usedMemories,
                        remembered = extras.remembered,
                        forgotten = extras.forgotten,
                        planUsageLimit = extras.planUsageLimit,
                    ),
                ),
            )
        }.toString()
    }

    fun decode(raw: String?): Pair<List<CardSpec>, ReplyExtras> {
        val cards = CardJson.decodeList(raw)
        if (raw.isNullOrEmpty() || !raw.contains(EXTRAS_KIND)) return cards to ReplyExtras.NONE
        val array = try {
            CardJson.json.parseToJsonElement(raw) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return cards to ReplyExtras.NONE
        val element = array.lastOrNull { ((it as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull == EXTRAS_KIND }
            ?: return cards to ReplyExtras.NONE
        val stored = try {
            CardJson.json.decodeFromJsonElement(Stored.serializer(), element.jsonObject)
        } catch (e: Exception) {
            return cards to ReplyExtras.NONE
        }
        return cards to ReplyExtras(stored.searches, stored.sources, stored.notice, stored.usedMemories, stored.remembered, stored.forgotten, stored.planUsageLimit)
    }
}
