package com.phoneuse.cards

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed interface CardParse {
    data class Ok(val card: CardSpec) : CardParse
    data class Error(val reason: String) : CardParse
}

/** JSON for cards: what the model sends to `show_card`, and `messages.cards_json`. */
object CardJson {
    /** Prefix of the tool result when a card is rejected. */
    const val ERROR_PREFIX = "카드 오류: "

    private const val MAX_REASON = 200

    /** Longest text field a card may carry. */
    const val MAX_TEXT = 2000

    /** Longest array of any kind (photo URIs); other lists are capped lower by validate(). */
    private const val MAX_ARRAY = 60

    val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        // Lets the model send a number where a string is expected (e.g. stat value 8231).
        isLenient = true
        explicitNulls = false
    }

    private val KINDS = listOf("places", "route", "event", "photos", "results", "draft", "digest", "stat", "parcel")

    fun parse(text: String): CardParse {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return CardParse.Error("JSON이 아니에요")
        }
        return parse(element)
    }

    /** Strict: the model's show_card input. Checks sizes, decodes and runs [CardSpec.validate]. */
    fun parse(element: JsonElement): CardParse {
        val obj = element as? JsonObject ?: return CardParse.Error("card는 객체여야 해요")
        sizeError(obj, "card")?.let { return CardParse.Error(it) }
        val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull
            ?: return CardParse.Error("kind가 없어요 (${KINDS.joinToString("|")})")
        if (kind !in KINDS) return CardParse.Error("알 수 없는 kind: $kind (${KINDS.joinToString("|")})")
        val card = try {
            json.decodeFromJsonElement(CardSpec.serializer(), obj)
        } catch (e: MissingFieldException) {
            return CardParse.Error("필수 항목 없음: ${e.missingFields.joinToString(", ")}")
        } catch (e: SerializationException) {
            return CardParse.Error(shorten(e.message ?: "형식이 맞지 않아요"))
        } catch (e: IllegalArgumentException) {
            return CardParse.Error(shorten(e.message ?: "형식이 맞지 않아요"))
        }
        return card.validate()?.let { CardParse.Error(it) } ?: CardParse.Ok(card)
    }

    /** Null for no cards, so the column stays NULL. */
    fun encodeList(cards: List<CardSpec>): String? =
        if (cards.isEmpty()) null else json.encodeToString(ListSerializer(CardSpec.serializer()), cards)

    /**
     * Stored cards, decoded leniently: they were validated when shown, and re-validating would hide old cards
     * whenever a rule gets stricter. Cards that no longer decode at all (unknown kind) are skipped. Anything
     * that acts on card data (links, map points, thumbnails) checks it again where it is used.
     */
    fun decodeList(raw: String?): List<CardSpec> {
        if (raw.isNullOrEmpty()) return emptyList()
        val array = try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return array.mapNotNull {
            try {
                json.decodeFromJsonElement(CardSpec.serializer(), it)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** First text over [MAX_TEXT] chars or array over [MAX_ARRAY] entries, anywhere in the card. */
    private fun sizeError(e: JsonElement, path: String): String? = when (e) {
        is JsonPrimitive -> if (e.isString && e.content.length > MAX_TEXT) "$path 글자가 너무 길어요 (최대 ${MAX_TEXT}자)" else null
        is JsonArray -> if (e.size > MAX_ARRAY) {
            "$path 항목이 너무 많아요 (최대 ${MAX_ARRAY}개)"
        } else {
            e.withIndex().firstNotNullOfOrNull { (i, v) -> sizeError(v, "$path[$i]") }
        }
        is JsonObject -> e.entries.firstNotNullOfOrNull { (k, v) -> sizeError(v, if (path == "card") k else "$path.$k") }
    }

    /** One short text per card, added to the conversation history so later turns can refer to "this event". */
    fun summary(card: CardSpec): String = when (card) {
        is PlacesCard -> "장소 '${card.title}': " + card.items.mapIndexed { i, p -> "${i + 1}. ${p.name} (${p.lat},${p.lng})" }.joinToString("; ")
        is RouteCard -> "출발 ${card.departAt} → 도착 ${card.arriveAt} ${card.dest.name}" +
            (if (card.summary.isNotBlank()) " (${card.summary})" else "") + ": " +
            card.legs.joinToString(" / ") { "${it.at} ${it.label}" }
        is EventCard -> "일정 '${card.title}' ${card.start}–${card.end}" +
            (card.location?.let { " @ $it" } ?: "") + (card.reminderMin?.let { ", ${it}분 전 알림" } ?: "") +
            card.conflicts.mapIndexed { i, c -> "; 겹치는 일정 ${i + 1}. ${c.title} ${c.start}–${c.end}" }.joinToString("")
        is PhotosCard -> "사진 '${card.title}' ${card.uris.size}장: " + card.uris.mapIndexed { i, u -> "${i + 1}. $u" }.joinToString(" ")
        // Digest, parcel and results cards carry third-party text (notifications, texts, web pages). Their history
        // line keeps only structure and our own fields; a follow-up re-reads through a tool, which taints that run.
        is ResultsCard -> "검색 결과 ${card.items.size}개: " +
            card.items.mapIndexed { i, r -> "${i + 1}." + (r.url?.let { domain(it) }?.let { " $it" } ?: "") }.joinToString("; ")
        is DraftCard -> "초안(${card.channel}${card.to?.let { " → $it" } ?: ""}): ${card.body}"
        is DigestCard -> "알림 요약: " + card.groups.withIndex().joinToString(" / ") { (gi, g) ->
            val label = g.label.takeIf { it in DIGEST_LABELS } ?: "그룹 ${gi + 1}"
            "'$label' ${g.items.size}개" + g.items.mapIndexed { i, it ->
                " ${i + 1}." + (it.at?.takeIf { a -> CLOCK.matches(a) }?.let { a -> " $a" } ?: "")
            }.joinToString(";", prefix = ":")
        }
        is StatCard -> "${card.label}: ${card.value}" + (card.delta?.let { " ($it)" } ?: "")
        is ParcelCard -> "택배: ${card.status.takeIf { it in ParcelCard.STAGES } ?: "?"}" +
            (card.carrier?.takeIf { it in CARRIERS }?.let { " · $it" } ?: "") +
            (card.tracking?.takeIf { TRACKING.matches(it) }?.let { " $it" } ?: "") +
            (if (card.events.isEmpty()) "" else ", 단계 ${card.events.size}개 " + card.events.mapNotNull { it.at.takeIf { a -> CLOCK.matches(a) } }.joinToString(" "))
        is PermissionCard -> "권한 요청 카드: ${card.label} [권한 허용]"
        is RoutineCard -> card.parsed()?.let { r ->
            "루틴 미리보기 '${r.title}': ${com.phoneuse.routine.RoutineText.whenText(r.trigger)} → " +
                com.phoneuse.routine.RoutineText.steps(r.action).joinToString(", ") { it.text } + " [루틴 저장]"
        } ?: "루틴 미리보기"
    }

    /** The digest card's group labels we assign (DigestBucket); anything else is "그룹 n". */
    private val DIGEST_LABELS = setOf("답장 필요", "돈 나간 것", "오늘 도착", "나머지")

    /** Carrier names the SMS parser assigns (SmsParsers). */
    private val CARRIERS = setOf(
        "CJ대한통운", "한진택배", "롯데택배", "우체국택배", "로젠택배", "경동택배", "CU 편의점택배", "GS 편의점택배", "쿠팡",
    )
    private val CLOCK = Regex("""(\d{2}/\d{2} )?\d{1,2}:\d{2}""")
    private val TRACKING = Regex("""[\d-]{6,20}""")
    private val HOST = Regex("""^[a-z][a-z0-9+.-]*://([^/?#:@\s]+)""", RegexOption.IGNORE_CASE)

    /** The host of an http(s) URL ("m.example.com"), without "www."; null for anything else. */
    fun domain(url: String): String? = HOST.find(url.trim())?.groupValues?.get(1)?.lowercase()
        ?.removePrefix("www.")?.takeIf { it.matches(Regex("[a-z0-9.-]{1,80}")) }

    private fun shorten(s: String): String {
        val line = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: s
        return if (line.length <= MAX_REASON) line else line.take(MAX_REASON) + "…"
    }
}
