package com.phoneuse.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI

/** One hosted web search the model ran. [queries] is empty while the call is still in progress. */
data class WebSearchCall(val id: String?, val queries: List<String>)

/** A source the answer cites (`url_citation` annotation). */
data class Citation(val title: String, val url: String)

/** The model-hosted web search tool and the parts of a response it adds. */
object WebSearch {
    /** Added to the request's `tools` when web search is on. */
    val TOOL: JsonObject = buildJsonObject {
        put("type", "web_search")
        put("external_web_access", true)
    }

    /** A `web_search_call` output item, or null for any other item. Reads `action.queries`, else `action.query`. */
    fun parseCall(item: JsonObject): WebSearchCall? {
        if (item.string("type") != "web_search_call") return null
        val action = item["action"] as? JsonObject
        val many = (action?.get("queries") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
        val queries = many.ifEmpty { listOfNotNull(action?.string("query")?.trim()?.takeIf(String::isNotEmpty)) }
        return WebSearchCall(item.string("id"), queries)
    }

    /** The `url_citation`s of the response's message items, first occurrence of each URL, http(s) only. */
    fun citations(output: JsonArray): List<Citation> {
        val seen = LinkedHashMap<String, Citation>()
        for (item in output) {
            val obj = item as? JsonObject ?: continue
            if (obj.string("type") != "message") continue
            for (part in obj["content"] as? JsonArray ?: continue) {
                for (a in (part as? JsonObject)?.get("annotations") as? JsonArray ?: continue) {
                    val ann = a as? JsonObject ?: continue
                    if (ann.string("type") != "url_citation") continue
                    val url = ann.string("url")?.trim() ?: continue
                    if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                    if (url in seen) continue
                    val title = ann.string("title")?.trim()?.takeIf(String::isNotEmpty) ?: host(url)
                    seen[url] = Citation(title, url)
                }
            }
        }
        return seen.values.toList()
    }

    /**
     * The hosted search's inline citation marks removed from [text]: "\uE200cite\uE202turn0search1\uE201" and any
     * stray private-use character of that range. A mark still open at the end (mid-stream) is dropped too.
     */
    fun stripMarks(text: String): String {
        if (text.none { it in '\uE200'..'\uE2FF' }) return text
        return text.replace(MARK, "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trimEnd()
    }

    private val MARK = Regex("\uE200[^\uE200\uE201]*(?:\uE201|$)|[\uE200-\uE2FF]")

    private fun host(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
