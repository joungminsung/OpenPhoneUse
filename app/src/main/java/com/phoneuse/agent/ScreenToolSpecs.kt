package com.phoneuse.agent

import com.phoneuse.model.ToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The screen tools' specs. Their logic is ToolExecutor's own screen module (it shares the executor's screen
 * state); delegate is run by AgentLoop's helper and is offered only while that helper works.
 */

private val index = prop("integer", "Element number shown as [n] on the screen.")
private val coordX = prop("integer", "X pixel coordinate.")
private val coordY = prop("integer", "Y pixel coordinate.")

private val direction = enumProp("Scroll direction.", listOf("up", "down", "left", "right"))

private val batchActions = buildJsonObject {
    put("type", "array")
    put(
        "description",
        "Steps run in order. Allowed tools: tap, long_press, type_text, scroll, swipe, pinch, press_key, " +
            "open_app, open_uri, wait. A step names its element by args.index (on the screen you saw) or by " +
            "args.target, the element's visible label (button text, tab name, field hint). target works on screens " +
            "you have not seen yet: the step waits up to 3 s for the label to appear, so one batch can run through " +
            "several screens. A step that pays, sends, deletes or changes a setting carries risk and effect in its " +
            "args like the single tool. Stops where a target never appears, an index is gone or a step fails, and " +
            "returns the screen there.",
    )
    putJsonObject("items") {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("tool") { put("type", "string") }
            putJsonObject("args") { put("type", "object") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("tool"))
            add(JsonPrimitive("args"))
        }
    }
}

internal object ScreenToolSpecs {
    val all: List<ToolSpec> = listOf(
        ToolSpec(
            "tap",
            "Tap an element by index, or a point by x and y. Set user_authorized only if the user explicitly asked for this send/post action.",
            schema(
                props = mapOf(
                    "index" to index, "x" to coordX, "y" to coordY,
                    "user_authorized" to prop("boolean", "True if the user explicitly asked for this send/post action."),
                ) + RiskJudge.PROPS,
            ),
        ),
        ToolSpec(
            "long_press",
            "Long-press an element by index, or a point by x and y.",
            schema(props = mapOf("index" to index, "x" to coordX, "y" to coordY) + RiskJudge.PROPS),
        ),
        ToolSpec(
            "type_text",
            "Type text into an editable element. Set submit to press the keyboard action key afterwards.",
            schema(
                required = listOf("index", "text"),
                props = mapOf(
                    "index" to index,
                    "text" to prop("string", "Text to enter."),
                    "submit" to prop("boolean", "Press Enter/Search after typing."),
                    "user_authorized" to prop("boolean", "True if the user explicitly asked to send this text."),
                ) + RiskJudge.PROPS,
            ),
        ),
        ToolSpec(
            "scroll",
            "Scroll a scrollable element by index, or the screen center if no index is given.",
            schema(required = listOf("direction"), props = mapOf("index" to index, "direction" to direction)),
        ),
        ToolSpec(
            "swipe",
            "Swipe between two points. Start away from the screen edges: an edge start is the system back/home " +
                "gesture, so starts near an edge are moved inward. Prefer scroll for lists.",
            schema(
                required = listOf("x1", "y1", "x2", "y2"),
                props = mapOf(
                    "x1" to coordX, "y1" to coordY,
                    "x2" to coordX, "y2" to coordY,
                    "duration_ms" to prop("integer", "Swipe duration in milliseconds (default 300)."),
                ) + RiskJudge.PROPS,
            ),
        ),
        ToolSpec(
            "pinch",
            "Two-finger pinch centered on a point: zoom in (fingers spread) or out (fingers close) on a map, photo, " +
                "document or game board. Repeat it to zoom further.",
            schema(
                required = listOf("x", "y", "zoom"),
                props = mapOf(
                    "x" to coordX, "y" to coordY,
                    "zoom" to enumProp("in makes things bigger, out smaller.", listOf("in", "out")),
                ),
            ),
        ),
        ToolSpec(
            "press_key",
            "Press a system key.",
            schema(
                required = listOf("key"),
                props = mapOf(
                    "key" to enumProp(
                        "System key.",
                        listOf("back", "home", "recents", "notifications", "quick_settings"),
                    ),
                ),
            ),
        ),
        ToolSpec(
            "open_app",
            "Open an app by package name, or by a part of its label.",
            schema(
                props = mapOf(
                    "package" to prop("string", "Package name."),
                    "name" to prop("string", "App label or part of it."),
                    "fresh" to prop("boolean", "Start the app over at its start screen instead of where it was last left."),
                ),
            ),
        ),
        ToolSpec(
            "open_uri",
            "Open a URI: a web or app link (https:, tel:, geo:, ...), an intent: deep link " +
                "(intent://...#Intent;...;end), or a bare Settings action such as android.settings.DISPLAY_SETTINGS.",
            schema(
                required = listOf("uri"),
                props = mapOf("uri" to prop("string", "URI, intent: URI, or android.settings.* action name.")),
            ),

        ),
        ToolSpec(
            "list_apps",
            "List installed apps, optionally filtered by label or package.",
            schema(props = mapOf("query" to prop("string", "Case-insensitive filter."))),
        ),
        ToolSpec(
            "screenshot",
            "Capture a screenshot together with the current screen elements.",
            schema(),
        ),
        ToolSpec(
            "wait",
            "Wait before looking at the screen again.",
            schema(required = listOf("ms"), props = mapOf("ms" to prop("integer", "Milliseconds, at most 5000."))),
        ),
        ToolSpec(
            "batch",
            "Run several steps, across several screens, in one call. Every call you save is seconds saved.",
            schema(required = listOf("actions"), props = mapOf("actions" to batchActions)),
        ),
        ToolSpec(
            "delegate",
            "Hand one mechanical, bounded piece of work in the open app to a fast helper model, e.g. scroll the " +
                "whole result list and collect each item's name and price, page through reviews, or repeat a tap. " +
                "The helper acts through the same safety checks, reports back with the screen it ended on, and you " +
                "keep every decision: what to pick, what to answer. Do not delegate judgement, sending or paying.",
            schema(
                required = listOf("task"),
                props = mapOf(
                    "task" to prop("string", "Exactly what to do and what to collect, in one or two sentences."),
                    "done_when" to prop("string", "When to stop, e.g. 'the list ends' or '30 items collected'."),
                    "max_steps" to prop("integer", "Helper model calls allowed (default 15, at most 30)."),
                ),
            ),
        ),
        ToolSpec(
            "get_screen",
            "Look at the phone's current screen (element tree), optionally with a screenshot. Call it when you need the phone state.",
            schema(props = mapOf("screenshot" to prop("boolean", "Also capture a screenshot."))),
        ),
    )
}
