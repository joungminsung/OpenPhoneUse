package com.phoneuse.agent

import com.phoneuse.cards.CardJson
import com.phoneuse.cards.CardParse
import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.LatLng
import com.phoneuse.cards.PlacesCard
import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.PlacesDistance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * show_card and finish: what the user reads at the end of a run. [lastLocation] is this run's get_location fix,
 * which measures a places card that has no center of its own.
 */
internal class ReplyModule(private val lastLocation: () -> LatLng? = { null }) : ToolModule {
    override val specs: List<ToolSpec> get() = SPECS

    /** The model's own reply, not text from outside. */
    override val userText: Set<String> get() = USER_TEXT

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult = when (name) {
        "show_card" -> showCard(args)
        "finish" -> ToolResult(
            "OK", null, null, args.requireStr("summary"),
            stayInApp = args.bool("stay_in_app") == true,
            finishedOk = args.bool("success") != false,
        )
        else -> ToolResult("ERROR: unknown tool $name", null, null, null)
    }

    /**
     * A places card is measured by the app: from its center, or else this run's get_location fix (which then
     * becomes the center, the blue dot); without either the model's distances stay.
     */
    private fun measured(card: CardSpec): CardSpec {
        if (card !is PlacesCard) return card
        val center = card.center ?: lastLocation() ?: return card
        return card.copy(center = center, items = PlacesDistance.fill(center, card.items))
    }

    /** Accepts the card as an object, or as a JSON string of one (some models stringify nested objects). */
    private fun showCard(args: JsonObject): ToolResult {
        val raw = args["card"] ?: throw ArgException("card required")
        val element = (raw as? JsonPrimitive)?.takeIf { it.isString }?.let {
            try {
                Json.parseToJsonElement(it.content)
            } catch (e: Exception) {
                null
            }
        } ?: raw
        return when (val parsed = CardJson.parse(element)) {
            is CardParse.Ok -> ToolResult(
                "OK: 카드를 보여줬어요. 답에는 핵심 요약과 추천 하나를 이유와 함께 2~4문장으로 쓰고, 카드 항목을 다시 다 늘어놓지는 마세요.",
                null, null, null, card = measured(parsed.card),
            )
            is CardParse.Error -> ToolResult(CardJson.ERROR_PREFIX + parsed.reason, null, null, null)
        }
    }

    private companion object {
        private val USER_TEXT = setOf("show_card", "finish")

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                "show_card",
                "Show a result card under your reply: places on a map, a route with when to leave, an event preview, " +
                    "photos, search results, a draft text, a digest of notifications, a parcel's delivery stage, or a stat. The app draws it with " +
                    "action buttons. Still write a text reply: the key point and one recommendation with its reason in 2-4 " +
                    "sentences, without re-listing the card's items. Several cards may be shown in one answer.",
                schema(required = listOf("card"), props = mapOf("card" to cardSchema)),
            ),
            ToolSpec(
                "finish",
                "End the task. The summary is the reply the user reads, so it must be a complete answer, not just 'done'.",
                schema(
                    required = listOf("summary"),
                    props = mapOf(
                        "summary" to prop(
                            "string",
                            "The reply to the user, in their language, in Markdown: the result first (key values in " +
                                "bold), then only what is useful - the values found, what changed, what to do next; a table " +
                                "when comparing 3+ items. No list of screens visited, no generic advice or filler. If it " +
                                "failed, why and what they can do instead.",
                        ),
                        "success" to prop("boolean", "False when the task could not be done (default true)."),
                        "stay_in_app" to prop(
                            "boolean",
                            "True when the result IS the other app on screen - music/video playing, a page or map " +
                                "the user wants to look at, a call in progress. Otherwise leave it unset: the app " +
                                "returns to the OpenPhoneUse chat when you finish.",
                        ),
                    ),
                ),
            ),
        )
    }
}

private val latLng = obj("lat" to num(), "lng" to num(), required = listOf("lat", "lng"))

/** show_card's `card`: one object whose `kind` decides which other fields apply (checked by CardSpec.validate). */
private val cardSchema = buildJsonObject {
    put("type", "object")
    put(
        "description",
        "The card. Fields by kind - " +
            "places: title, center?{lat,lng} (the user's location or search origin, drawn as a blue dot), " +
            "items[1-10]{name, lat, lng, distance_m?, walk_min?, status_text?, phone?, address?} (nearest/best first). " +
            "route: depart_at (HH:mm), arrive_at (HH:mm), summary, legs[{at (HH:mm), kind: walk|bus|subway|train|car, label, detail}], " +
            "dest{name, lat?, lng?}. " +
            "event: title, start (ISO 8601), end (ISO 8601), location?, reminder_min?, conflicts[{title, start, end}]. " +
            "photos: title, uris[1-60] (content:// URIs), filters?[{label, uris[] (subset of uris)}]. " +
            "results: title, items[1-10]{title, subtitle?, meta?, url?}, sources[{title, url}]. " +
            "draft: channel: sms|email|note, to?, subject?, body. " +
            "digest: groups[{label, tone: urgent|money|info|muted, items[{who?, text, at?, action?}]}]. " +
            "stat: label, value, delta?, bars?[{label, value (number)}]. " +
            "parcel: title (the item), status: 접수|이동 중|배송 출발|도착, carrier?, tracking?, source? (e.g. 문자에서 송장 찾음), " +
            "events?[{at, text}] (newest first).",
    )
    putJsonObject("properties") {
        put("kind", enumProp("Card type.", listOf("places", "route", "event", "photos", "results", "draft", "digest", "stat", "parcel")))
        put("title", str())
        put("center", latLng)
        put(
            "items",
            arr(
                buildJsonObject { put("type", "object") },
                "places: {name, lat, lng, distance_m?, walk_min?, status_text?, phone?, address?}; " +
                    "results: {title, subtitle?, meta?, url?}.",
            ),
        )
        put("depart_at", str("HH:mm"))
        put("arrive_at", str("HH:mm"))
        put("summary", str())
        put(
            "legs",
            arr(
                obj(
                    "at" to str("HH:mm"),
                    "kind" to enumProp("Leg type.", listOf("walk", "bus", "subway", "train", "car")),
                    "label" to str(),
                    "detail" to str(),
                    required = listOf("at", "kind", "label"),
                ),
            ),
        )
        put("dest", obj("name" to str(), "lat" to num(), "lng" to num(), required = listOf("name")))
        put("start", str("ISO 8601 date-time"))
        put("end", str("ISO 8601 date-time"))
        put("location", str())
        put("reminder_min", buildJsonObject { put("type", "integer") })
        put("conflicts", arr(obj("title" to str(), "start" to str(), "end" to str())))
        put("uris", arr(str(), "content:// URIs of photos."))
        put("filters", arr(obj("label" to str(), "uris" to arr(str()))))
        put("sources", arr(obj("title" to str(), "url" to str(), required = listOf("title", "url"))))
        put("channel", enumProp("Draft channel.", listOf("sms", "email", "note")))
        put("to", str())
        put("subject", str())
        put("body", str())
        put(
            "groups",
            arr(
                obj(
                    "label" to str(),
                    "tone" to enumProp("Group tone.", listOf("urgent", "money", "info", "muted")),
                    "items" to arr(obj("who" to str(), "text" to str(), "at" to str(), "action" to str())),
                ),
            ),
        )
        put("label", str())
        put("value", str())
        put("delta", str())
        put("bars", arr(obj("label" to str(), "value" to num())))
        put("status", enumProp("Parcel stage.", listOf("접수", "이동 중", "배송 출발", "도착")))
        put("carrier", str())
        put("tracking", str())
        put("source", str())
        put("events", arr(obj("at" to str(), "text" to str(), required = listOf("at", "text"))))
    }
    putJsonArray("required") { add(JsonPrimitive("kind")) }
}
